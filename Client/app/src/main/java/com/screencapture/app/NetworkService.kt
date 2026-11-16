package com.screencapture.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.genymobile.scrcpy.agent.AgentInput
import com.genymobile.scrcpy.agent.AgentInputService
import com.genymobile.scrcpy.agent.SilentScreenStreamer
import com.screencapture.app.remote.RemoteScreenService
import com.screencapture.app.screencapture.HiddenApi
import com.screencapture.app.net.Protocol
import com.screencapture.app.net.ServerConnection
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a TCP connection to the Qt server and routes remote commands.
 * Polls every 1s to keepalive and auto-reconnect when the socket drops.
 */
class NetworkService : Service(), ServerConnection.Listener {

    private var connection: ServerConnection? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wantConnected = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val sharingFinished = AtomicBoolean(false)

    // Registered once to reconnect the instant a usable network appears, instead
    // of only on the next 1s poll — cuts the first-screen delay after a reboot and
    // speeds recovery after a mid-session drop.
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    // When the in-flight connect attempt started, so a lost completion callback
    // cannot latch reconnects off permanently.
    @Volatile
    private var connectingSince = 0L

    @Volatile
    private var targetHost: String = ""

    @Volatile
    private var targetPort: Int = DEFAULT_PORT

    @Volatile
    private var connectedHost: String = ""

    @Volatile
    private var connectedPort: Int = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!wantConnected.get()) {
                return
            }
            // The relay is the clock. This catches the hour when that message
            // never arrives, including if the single delayed callback was dropped.
            val deadline = shareDeadlineElapsed
            if (deadline != 0L && SystemClock.elapsedRealtime() >= deadline) {
                finishSharing(closeApp = true)
                return
            }
            ensureConnectedOrPoll()
            publishReadiness()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Whether this phone can serve a session at this moment. Both halves are
     * required and neither can be assumed from the other: the capture token dies
     * with the process or when the user revokes it, and the accessibility service
     * can be switched off in Settings at any time, including mid-session.
     */
    private fun servicesReady(): Boolean = captureReady() && AgentInputService.get() != null

    /**
     * A live streamer counts even without a usable projection token: the boards
     * that capture through the platform APIs never hold one, and judging them by
     * the token alone would hide a device that is streaming perfectly well.
     */
    private fun captureReady(): Boolean =
        ProjectionResultHolder.canCaptureWithoutDialog()
            || try { SilentScreenStreamer.isCapturingLive() } catch (_: Throwable) { false }

    private fun readinessReason(): String = when {
        !captureReady() -> "screen capture"
        AgentInputService.get() == null -> "accessibility"
        else -> ""
    }

    /** Consecutive polls that have found one of the services down. */
    private var notReadyTicks = 0

    /**
     * Keep the relay's idea of this phone in step with reality, so operators only
     * ever see a device they can actually open. Sent every tick rather than only
     * on change: it is tiny next to the video, and a dropped update would
     * otherwise leave the phone hidden until something else happened to it.
     *
     * Reporting is all this does. Taking control away from the operator is the
     * relay's job and it does it through control_revoked once it decides the
     * phone can no longer serve. Doing it here as well meant a single unlucky
     * poll threw away the control key and the punched input sockets underneath a
     * healthy session, which the operator saw as clicks going nowhere.
     */
    private fun publishReadiness() {
        val live = connection ?: return
        if (!live.isConnected()) {
            return
        }
        val ready = servicesReady() && !sharingFinished.get()
        // A projection being rebuilt reads as "down" for a moment. Believing that
        // instantly would pull the device off every operator's list and end a
        // session that was about to carry on, so a drop has to persist before it
        // counts. Recovery is reported straight away.
        notReadyTicks = if (ready) 0 else notReadyTicks + 1
        if (!ready && notReadyTicks < NOT_READY_GRACE_TICKS) {
            return
        }
        live.sendReady(ready, readinessReason())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A fresh service is a fresh run of sharing: the user opened the app again
        // after it was stopped. That is the one thing that reopens this phone to
        // the controllers and grants a new hour, so it gets a new share id. Plain
        // reconnects reuse this one and resume the session they already had.
        // A restart while Stop is latched must not mint an id — nothing may register.
        if (!ServerConfig.isStoppedByHost(this)) {
            ServerConnection.DeviceInfo.newShare()
        }
        try {
            createNotificationChannel()
        } catch (t: Throwable) {
        }
        try {
            HiddenApi.unseal()
        } catch (t: Throwable) {
        }
        try {
            AgentInput.ensureAccessibility(this)
        } catch (t: Throwable) {
        }
        registerConnectivityCallback()
        startAsForeground(
            if (targetHost.isNotBlank()) targetHost else ServerConfig.host(this),
            if (targetPort != 0) targetPort else ServerConfig.port(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_SHARING) {
            finishSharing()
            return START_NOT_STICKY
        }
        // Stop latches this until the user presses Start. A sticky restart or a
        // start that was already queued must not open a new TCP socket, or the
        // status flips between "TCP connected" and "Disconnected".
        if (ServerConfig.isStoppedByHost(this)) {
            wantConnected.set(false)
            stopPolling()
            connecting.set(false)
            connection?.retire()
            connection = null
            connectedHost = ""
            connectedPort = 0
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground(
            intent?.getStringExtra(EXTRA_HOST)?.trim()?.ifBlank { null }
                ?: targetHost.ifBlank { ServerConfig.host(this) },
            intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }
                ?: targetPort.takeIf { it in 1..65535 }
                ?: ServerConfig.port(this)
        )
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                wantConnected.set(false)
                stopPolling()
                connecting.set(false)
                connection?.retire()
                connection = null
                connectedHost = ""
                connectedPort = 0
                FrameBridge.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                // Stop latches the previous instance. Start is a new offer on
                // that same object when the process has not been recreated yet.
                sharingFinished.set(false)
                notReadyTicks = 0
                val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                if (host.isBlank()) {
                    if (!applySavedEndpoint()) {
                        broadcastStatus(false, "Host is empty")
                        return START_STICKY
                    }
                } else {
                    targetHost = host
                    targetPort = port
                }
            }

            ACTION_RENAME -> {
                applySavedEndpoint()
                ServerConnection.loadDeviceInfo(this)
                val live = connection
                if (live != null && live.isConnected()) {
                    live.sendDeviceName(ServerConnection.DeviceInfo.device)
                    return START_STICKY
                }
                // Sharing is on, but the socket is down. The next register frame
                // is what carries the name until the rename line can be sent.
                wantConnected.set(true)
                startPolling()
                connecting.set(false)
                connection?.retire()
                connectedHost = ""
                connectedPort = 0
                if (targetHost.isNotBlank()) {
                    connectNow("Re-registering as ${ServerConnection.DeviceInfo.device}…")
                }
                return START_STICKY
            }

            ACTION_REREGISTER -> {
                applySavedEndpoint()
                // The device name only travels in the register frame, so a live
                // socket would keep showing the old one on the desktop until it
                // happened to drop. Reload and reconnect to re-send it.
                ServerConnection.loadDeviceInfo(this)
                wantConnected.set(true)
                startPolling()
                connecting.set(false)
                connection?.retire()
                connectedHost = ""
                connectedPort = 0
                if (targetHost.isNotBlank()) {
                    connectNow("Re-registering as ${ServerConnection.DeviceInfo.device}…")
                }
                return START_STICKY
            }

            else -> {
                applySavedEndpoint()
            }
        }

        if (targetHost.isBlank() && !applySavedEndpoint()) {
            return START_STICKY
        }

        wantConnected.set(true)
        ServerConnection.loadDeviceInfo(this)
        startPolling()
        if (isLiveOnTarget()) {
            connecting.set(false)
            return START_STICKY
        }
        if (!connecting.get()) {
            connectNow("Connecting to $targetHost:$targetPort…")
        }
        return START_STICKY
    }

    private fun isLiveOnTarget(): Boolean {
        return connection?.isConnected() == true &&
            connectedHost == targetHost &&
            connectedPort == targetPort
    }

    private fun applySavedEndpoint(): Boolean {
        if (!ServerConfig.isConfigured(this)) {
            return targetHost.isNotBlank()
        }
        targetHost = ServerConfig.host(this)
        targetPort = ServerConfig.port(this)
        return targetHost.isNotBlank()
    }

    /**
     * The customer swiped the app out of recents. The activity is already gone or
     * about to be, so this service is the last place that still has the socket.
     * Ending the share here is what stops capture and input and tells the
     * controller to close the window it still has open.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        endShareBecauseAppClosed()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wantConnected.set(false)
        stopPolling()
        mainHandler.removeCallbacks(shareEnded)
        unregisterConnectivityCallback()
        connecting.set(false)
        connection?.retire()
        connection = null
        connectedHost = ""
        connectedPort = 0
        FrameBridge.clear()
        super.onDestroy()
    }

    private fun endShareBecauseAppClosed() {
        if (sharingFinished.get()) {
            return
        }
        if (!ShareSessionState.sharing && connection?.isConnected() != true) {
            return
        }
        finishSharing(closeApp = false)
    }

    private fun startPolling() {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(pollRunnable)
    }

    /** True when we hold ACCESS_NETWORK_STATE and can therefore query connectivity. */
    private fun hasNetworkStatePermission(): Boolean {
        return try {
            checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /** True when the device currently has a network with an internet route. */
    private fun isNetworkAvailable(): Boolean {
        // Without ACCESS_NETWORK_STATE we cannot query state; never block connecting.
        if (!hasNetworkStatePermission()) {
            return true
        }
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            // Never let a capability-check failure block connecting; fall back to
            // the old always-try behaviour.
            true
        }
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallback != null) {
            return
        }
        // registerDefaultNetworkCallback enforces ACCESS_NETWORK_STATE; skip cleanly
        // (rather than throwing a SecurityException) when the platform withholds it.
        if (!hasNetworkStatePermission()) {
            return
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // A usable network just appeared — reconnect immediately rather
                    // than waiting for the next poll tick.
                    mainHandler.post {
                        if (!wantConnected.get() || connection?.isConnected() == true) {
                            return@post
                        }
                        // An attempt already in flight is bound to the network that
                        // just went away, so it can only end in its connect timeout.
                        // Clear the latch and let connectNow() retire it, which closes
                        // its socket and aborts the doomed handshake at once.
                        connecting.set(false)
                        connectNow("Network available — connecting…")
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            connectivityCallback = cb
        } catch (t: Throwable) {
        }
    }

    private fun unregisterConnectivityCallback() {
        val cb = connectivityCallback ?: return
        connectivityCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
            // Already gone; ignore.
        }
    }

    private fun ensureConnectedOrPoll() {
        if (!wantConnected.get() || targetHost.isBlank()) {
            return
        }
        val conn = connection
        if (conn != null && conn.isConnected()) {
            // ServerConnection already sends a 1s TCP heartbeat; poll only watches liveness.
            return
        }
        if (connecting.get()) {
            // Self-heal: if an attempt never reported back (killed thread, lost
            // callback), do not stay latched forever and stop reconnecting.
            if (SystemClock.elapsedRealtime() - connectingSince < CONNECT_STALE_MS) {
                return
            }
            connecting.set(false)
        }
        if (!isNetworkAvailable()) {
            // Boots frequently start before the network is up. Don't burn a connect
            // attempt while offline — the connectivity callback reconnects the
            // moment a network appears.
            return
        }
        connectNow("Reconnecting to $targetHost:$targetPort…")
    }

    private fun connectNow(status: String) {
        if (!wantConnected.get() || sharingFinished.get() || targetHost.isBlank()) {
            return
        }
        if (ServerConfig.isStoppedByHost(this)) {
            wantConnected.set(false)
            return
        }
        if (isLiveOnTarget()) {
            connecting.set(false)
            return
        }
        if (!connecting.compareAndSet(false, true)) {
            return
        }
        connectingSince = SystemClock.elapsedRealtime()
        // retire(), not disconnect(): the outgoing connection may still be blocked
        // in socket.connect() and must never report its late failure, which used to
        // clear the latch mid-attempt and pile up a new socket every poll tick.
        connection?.retire()
        connectedHost = ""
        connectedPort = 0
        val conn = ServerConnection(this)
        connection = conn
        FrameBridge.attach(conn)
        broadcastStatus(false, status)
        updateNotification(status)
        conn.connect(targetHost, targetPort)
    }

    override fun onConnected(host: String, port: Int) {
        connecting.set(false)
        // A socket that finished its handshake after Stop must not announce
        // itself or start capture. Drop it and leave the status disconnected.
        if (!wantConnected.get() || sharingFinished.get() || ServerConfig.isStoppedByHost(this)) {
            wantConnected.set(false)
            val late = connection
            connection = null
            connectedHost = ""
            connectedPort = 0
            late?.retire()
            return
        }
        connectedHost = host
        connectedPort = port
        broadcastStatus(true, "TCP connected to $host:$port")
        updateNotification("TCP connected to $host:$port")
        HiddenApi.unseal()
        AgentInput.ensureAccessibility(this)
        // Do not setStreaming(true) here. Capture must stay warm after reboot, but
        // pushing JPEG into the relay with no desktop watching fills the TCP send
        // buffer and ratchets adaptive pacing down — then the first monitor open
        // looks frozen until that backlog drains and the frame counter jumps.
        RemoteScreenService.start(this)
    }

    override fun onDisconnected(reason: String) {
        connecting.set(false)
        connectedHost = ""
        connectedPort = 0
        AgentInput.cursor(this, 0f, 0f, false)
        FrameBridge.setStreaming(false)
        if (!wantConnected.get() || sharingFinished.get()) {
            broadcastStatus(false, "Disconnected: $reason")
            return
        }
        broadcastStatus(false, "Disconnected: $reason (auto-reconnect in ${POLL_INTERVAL_MS / 1000}s)")
        updateNotification("Reconnecting…")
    }

    override fun onRegistered(clientId: String) {
        ShareSessionState.markSharing(this, true)
        broadcastStatus(true, "Registered ($clientId)")
        AgentInput.ensureAccessibility(this)
        RemoteScreenService.start(this)
        // Registering only means the socket is up. Say straight away whether this
        // phone can actually serve a session, because until the relay hears that
        // it keeps the device off every operator's list.
        publishReadiness()
    }

    override fun onSessionOwner(
        remainMs: Long,
        name: String,
        detail: String,
        device: String
    ) {
        ensureShareDeadline(if (remainMs > 0) remainMs else SESSION_LIMIT_MS)
        AgentInput.releasePointer()
        ShareSessionState.markController(this, name, detail, device)
        broadcastStatus(true, "Controller connected: $name")
    }

    /**
     * The hour ran out, or the controller vanished without handing the phone back.
     * Nobody may take over, so this run is finished and the app closes.
     */
    override fun onShareEnd() {
        finishSharing(closeApp = true)
    }

    /**
     * Stop from the controller, matching the handset's own Stop button. Sharing
     * and remote control end. The activity stays on screen.
     */
    override fun onShareStopped() {
        finishSharing(closeApp = false)
    }

    override fun onStatus(message: String) {
        broadcastStatus(connection?.isConnected() == true, message)
    }

    override fun onError(message: String) {
        broadcastStatus(connection?.isConnected() == true, message)
    }

    override fun onCommand(action: String, payload: JSONObject) {
        // A malformed or out-of-range command must never take the process down.
        // An unclamped wheel scroll used to throw IllegalArgumentException out of
        // the accessibility gesture builder and kill the app mid-session, which
        // looked like "streaming stops working after a few minutes".
        try {
            dispatchCommand(action, payload)
        } catch (t: Throwable) {
        }
    }

    private fun dispatchCommand(action: String, payload: JSONObject) {
        when (action) {
            Protocol.ACTION_SCREEN_START -> {
                handleScreenStart(payload)
            }
            Protocol.ACTION_SCREEN_STOP -> {
                handleScreenStop()
            }
            Protocol.ACTION_INPUT_TOUCH -> {
                handleInputTouch(payload)
                SilentScreenStreamer.nudge()
            }
            Protocol.ACTION_INPUT_KEY -> {
                handleInputKey(payload)
                // Only nudge on key-up so Back/Home/Recents refresh once per press.
                if (!payload.optBoolean("d", false)) {
                    SilentScreenStreamer.nudge()
                }
            }
            Protocol.ACTION_INPUT_TEXT -> AgentInput.text(this, payload.optString("t"))
            Protocol.ACTION_INPUT_SCROLL -> {
                handleInputScroll(payload)
                SilentScreenStreamer.nudge()
            }
            Protocol.ACTION_INPUT_MOUSE -> {
                handleInputMouse(payload)
                SilentScreenStreamer.nudge()
            }
            else -> connection?.sendResponse(
                action,
                JSONObject().put("error", "Unsupported action")
            )
        }
    }

    private fun handleInputTouch(payload: JSONObject) {
        val action = payload.optString("a")
        val x = payload.optDouble("x", 0.0).toFloat()
        val y = payload.optDouble("y", 0.0).toFloat()
        // Hover only moves the yellow circle. It must not become a finger press.
        // Hide until streaming is live so a connect attempt cannot paint the pointer
        // before the desktop has a video session.
        if (action == "hover") {
            if (StreamingFlag.isOn()) {
                AgentInput.cursor(this, x, y, true)
            }
            return
        }
        if (action == "hover_end") {
            AgentInput.cursor(this, x, y, false)
            return
        }
        AgentInput.touch(this, action, x, y)
    }

    private fun handleInputScroll(payload: JSONObject) {
        AgentInput.scroll(
            this,
            payload.optDouble("x", 0.0).toFloat(),
            payload.optDouble("y", 0.0).toFloat(),
            payload.optDouble("v", 0.0).toFloat()
        )
    }

    private fun handleInputMouse(payload: JSONObject) {
        val type = payload.optString("type").lowercase()
        val x = payload.optDouble("x", 0.5).toFloat()
        val y = payload.optDouble("y", 0.5).toFloat()
        when (type) {
            "scroll" -> AgentInput.scroll(
                this, x, y, payload.optDouble("deltaY", payload.optDouble("v", 0.0)).toFloat()
            )
            "swipe", "drag" -> {
                AgentInput.touch(this, "down", x, y)
                AgentInput.touch(
                    this,
                    "move",
                    payload.optDouble("x2", x.toDouble()).toFloat(),
                    payload.optDouble("y2", y.toDouble()).toFloat()
                )
                AgentInput.touch(
                    this,
                    "up",
                    payload.optDouble("x2", x.toDouble()).toFloat(),
                    payload.optDouble("y2", y.toDouble()).toFloat()
                )
            }
            else -> {
                AgentInput.touch(this, "down", x, y)
                AgentInput.touch(this, "up", x, y)
            }
        }
    }

    private fun handleInputKey(payload: JSONObject) {
        val clip = payload.optString("clip")
        if (clip.isNotEmpty()) {
            AgentInput.clipboard(this, clip, payload.optString("t"))
            return
        }
        if (payload.has("k")) {
            AgentInput.key(
                this,
                payload.optInt("k", 0),
                payload.optBoolean("d", false),
                payload.optInt("m", 0)
            )
            return
        }
        val text = payload.optString("text")
        if (text.isNotEmpty()) {
            AgentInput.text(this, text)
            return
        }
        val name = payload.optString("key").ifBlank {
            payload.optString("name")
        }.lowercase()
        val code = when (name) {
            "back", "escape" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "recents", "app_switch", "menu" -> KeyEvent.KEYCODE_APP_SWITCH
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "backspace" -> KeyEvent.KEYCODE_DEL
            "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "tab" -> KeyEvent.KEYCODE_TAB
            else -> 0
        }
        if (code != 0) {
            AgentInput.key(this, code, true)
            AgentInput.key(this, code, false)
        }
    }

    private fun handleScreenStart(payload: JSONObject) {
        connection?.setStreaming(true)
        FrameBridge.setStreaming(true)
        AgentInput.ensureAccessibility(this)
        RemoteScreenService.start(this)
        // If capture is already live (common: it started at launch), force fresh
        // frames now so the desktop window opens immediately on a static screen
        // instead of waiting until the user clicks something to change it.
        SilentScreenStreamer.onMonitorStart()
        ensureShareDeadline()
        connection?.sendResponse(
            Protocol.ACTION_SCREEN_START,
            JSONObject().put("status", "ok")
        )
        broadcastStatus(true, "Screen streaming started")
    }

    /** Elapsed-realtime deadline for this sharing session. 0 until a controller takes it. */
    private var shareDeadlineElapsed = 0L

    private val shareEnded = Runnable { finishSharing(closeApp = true) }

    /**
     * The hour belongs to the sharing session and starts when a controller actually
     * takes this phone, not when it merely appears on the list — a phone nobody
     * connects to should still be there an hour later.
     *
     * The relay runs the authoritative clock and will end the session on its own.
     * This is the backstop for the case where the relay cannot be reached, so it is
     * armed once and a reconnect keeps the same deadline rather than extending it.
     */
    private fun ensureShareDeadline(remainMs: Long = SESSION_LIMIT_MS) {
        if (sharingFinished.get()) return
        val now = SystemClock.elapsedRealtime()
        if (shareDeadlineElapsed == 0L) {
            shareDeadlineElapsed = now + remainMs.coerceIn(1_000L, SESSION_LIMIT_MS)
        }
        mainHandler.removeCallbacks(shareEnded)
        val delay = (shareDeadlineElapsed - now).coerceAtLeast(0L)
        mainHandler.postDelayed(shareEnded, delay)
    }

    /**
     * Sharing is over: the customer stopped it, the operator pressed Stop, or the
     * hour elapsed. Capture stops now and the phone leaves the relay so every
     * controller drops the row. It stays gone until the app is opened again.
     *
     * [closeApp] is only for the hour running out. Stop — on the handset or on the
     * controller — leaves the process running and just ends sharing and control.
     */
    private fun finishSharing(closeApp: Boolean = false) {
        if (!sharingFinished.compareAndSet(false, true)) return
        ServerConfig.setStoppedByHost(this, true)
        ShareSessionState.reset(this)
        wantConnected.set(false)
        stopPolling()
        connecting.set(false)
        shareDeadlineElapsed = 0L
        mainHandler.removeCallbacks(shareEnded)
        // Close the socket before the rest of the teardown. A handshake that
        // completes afterwards is retired and must not report "TCP connected".
        val closing = connection
        connection = null
        connectedHost = ""
        connectedPort = 0
        try {
            closing?.revokeControl()
        } catch (_: Throwable) {
        }
        try {
            closing?.sendSessionEnd()
        } catch (_: Throwable) {
        }
        closing?.retire()
        com.screencapture.app.input.InputPump.clear()
        FrameBridge.setStreaming(false)
        AgentInput.releasePointer()
        AgentInput.cursor(this, 0f, 0f, false)
        try {
            SilentScreenStreamer.stop()
        } catch (_: Throwable) {
        }
        try {
            ProjectionResultHolder.clearConsent(this)
        } catch (_: Throwable) {
        }
        try {
            stopService(Intent(this, RemoteScreenService::class.java))
        } catch (_: Throwable) {
        }
        FrameBridge.clear()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
        if (!closeApp) {
            return
        }
        // Let the dashboard dismiss itself first so the task does not linger with a
        // dead window, then end the process — the service alone stopping would keep
        // the activity and its MediaProjection alive in the background.
        ShareSessionState.requestShutdown(this)
        mainHandler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 400L)
    }

    private fun handleScreenStop() {
        endControllerSession()
        connection?.sendResponse(
            Protocol.ACTION_SCREEN_STOP,
            JSONObject().put("status", "ok")
        )
    }

    /**
     * The relay says the controller no longer holds this phone. Same teardown as a
     * stop command, and it arrives whether or not that controller managed to send
     * one — which it often does not, since Stop closes its window and kills its
     * sending threads in the same breath.
     */
    override fun onControlRevoked() {
        AgentInput.releasePointer()
        endControllerSession()
    }

    /**
     * Everything that belongs to the controller that was here, undone in one place.
     * The sharing offer itself survives: the phone stays registered and goes back
     * on the list so the next controller can take it without the customer touching
     * anything.
     */
    private fun endControllerSession() {
        // A stop queued for the controller that just left must not run once a new
        // one has been granted: it would take the new key away and freeze the
        // stream that controller has already started.
        if (connection?.finishTeardown() != true) {
            return
        }
        com.screencapture.app.input.InputPump.clear()
        AgentInput.cursor(this, 0f, 0f, false)
        connection?.setStreaming(false)
        FrameBridge.setStreaming(false)
        ShareSessionState.clearController(this)
        RemoteScreenService.stop(this)
        broadcastStatus(true, "Screen streaming stopped")
    }

    private fun startAsForeground(host: String, port: Int) {
        createNotificationChannel()
        val text = if (host.isBlank()) {
            getString(R.string.network_notification_title)
        } else {
            "Connecting to $host:$port"
        }
        val notification = buildNotification(text)
        // Android 12+ throws ForegroundServiceStartNotAllowedException when an FGS
        // is started from the background (e.g. right after install/boot). Catch it
        // so the process is not killed; the service keeps running in the background
        // and is promoted to foreground on the next foreground start.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException for a
            // background start (e.g. right after boot without a battery-optimization
            // exemption). Swallowing alone is not enough: because we were started via
            // startForegroundService, failing to enter the foreground within ~5s
            // triggers the platform "did not call startForeground" crash. Stop the
            // service cleanly to cancel that obligation; the retry alarms/jobs and the
            // next foreground launch re-establish the connection.
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.network_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.network_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.network_notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_capture)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun broadcastStatus(connected: Boolean, status: String) {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, connected)
                .putExtra(EXTRA_STATUS_TEXT, status)
        )
    }

    companion object {
        const val ACTION_CONNECT = "com.screencapture.app.action.NETWORK_CONNECT"
        const val ACTION_DISCONNECT = "com.screencapture.app.action.NETWORK_DISCONNECT"
        const val ACTION_END_SHARING = "com.screencapture.app.action.NETWORK_END_SHARING"
        const val ACTION_REREGISTER = "com.screencapture.app.action.NETWORK_REREGISTER"
        const val ACTION_RENAME = "com.screencapture.app.action.NETWORK_RENAME"
        const val ACTION_STATUS = "com.screencapture.app.action.NETWORK_STATUS"
        const val ACTION_NEED_PROJECTION = "com.screencapture.app.action.NEED_PROJECTION"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_CONNECTED = "extra_connected"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        private const val CHANNEL_ID = "network_service"
        private const val NOTIFICATION_ID = 1003
        private const val DEFAULT_PORT = 9000
        private const val POLL_INTERVAL_MS = 1_000L

        /** Polls a service must stay down before the phone reports itself blocked. */
        private const val NOT_READY_GRACE_TICKS = 3
        private const val SESSION_LIMIT_MS = 60 * 60 * 1000L
        // Must exceed ServerConnection's 12 s socket connect timeout.
        // Must stay above ServerConnection's connect timeout so a normal attempt
        // reports back on its own, but close enough that a lost callback costs only
        // one extra retry instead of a quarter minute offline.
        private const val CONNECT_STALE_MS = 8_000L

        /**
         * Push a name the user just typed. A live share sends it on the open
         * socket. While sharing is stopped the saved name is used on the next Start.
         */
        fun rename(context: Context) {
            val app = context.applicationContext
            if (ServerConfig.isStoppedByHost(app) || !ShareSessionState.sharing) {
                return
            }
            val intent = Intent(app, NetworkService::class.java).setAction(ACTION_RENAME)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (t: Throwable) {
                try {
                    app.startService(intent)
                } catch (_: Throwable) {
                }
            }
        }

        /** Re-send the register frame, e.g. after the device name was just set. */
        fun reregister(context: Context) {
            val app = context.applicationContext
            if (ServerConfig.isStoppedByHost(app)) {
                return
            }
            val intent = Intent(app, NetworkService::class.java).setAction(ACTION_REREGISTER)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (t: Throwable) {
                try {
                    app.startService(intent)
                } catch (_: Throwable) {
                }
            }
        }

        /** Stop capture and leave the controller. Stays offline until the user presses Start. */
        fun endSharing(context: Context) {
            val app = context.applicationContext
            ServerConfig.setStoppedByHost(app, true)
            val intent = Intent(app, NetworkService::class.java).setAction(ACTION_END_SHARING)
            try {
                app.startService(intent)
            } catch (_: Throwable) {
            }
        }

        fun disconnect(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, NetworkService::class.java).setAction(ACTION_DISCONNECT)
            try {
                app.startService(intent)
            } catch (_: Throwable) {
            }
        }

        fun connect(context: Context, host: String, port: Int) {
            val app = context.applicationContext
            // Start clears this flag before calling. Anything else is a leftover
            // start and must not bring the socket back.
            if (ServerConfig.isStoppedByHost(app)) {
                return
            }
            val intent = Intent(app, NetworkService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (t: Throwable) {
                try {
                    app.startService(intent)
                } catch (inner: Throwable) {
                    throw inner
                }
            }
        }
    }
}

/**
 * Shared bridge between NetworkService (TCP control) and RemoteScreenService
 * (capture/send threads). Mouse/key stay ordered JSON on the TCP socket.
 */
object FrameBridge {
    @Volatile
    private var connection: ServerConnection? = null

    fun attach(connection: ServerConnection?) {
        this.connection = connection
        if (StreamingFlag.isOn()) {
            connection?.setStreaming(true)
        }
    }

    fun setStreaming(enabled: Boolean) {
        StreamingFlag.setOn(enabled)
        connection?.setStreaming(enabled)
    }

    @JvmStatic
    fun isStreaming(): Boolean = StreamingFlag.isOn()

    /**
     * [capturedAt] is the uptimeMillis the compositor produced this frame. It
     * travels with the frame so the sender can match the desktop's frame_rx echo
     * back to a capture instant and report the real round trip.
     */
    @JvmStatic
    fun sendFrame(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        length: Int = jpeg.size,
        capturedAt: Long = 0L,
    ): Boolean {
        if (!StreamingFlag.isOn()) return false
        return connection?.sendFrame(jpeg, width, height, length, capturedAt) == true
    }

    /**
     * True while the previous frame is still going out. Encoding another one now
     * would only queue a picture the link has no room for, so the caller should
     * leave its regions unsent and let a later frame carry them.
     */
    @JvmStatic
    fun isSendBusy(): Boolean = connection?.isSendBusy() == true

    /** Time the last frame took to reach the socket, in ms. */
    @JvmStatic
    fun lastSendCostMs(): Long = connection?.lastSendCostMs() ?: 0L

    fun clear() {
        StreamingFlag.setOn(false)
        connection = null
    }
}
