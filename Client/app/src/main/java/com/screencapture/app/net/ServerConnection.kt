package com.screencapture.app.net

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.genymobile.scrcpy.agent.SilentScreenStreamer
import com.screencapture.app.ServerConfig
import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * TCP channel with explicit packet flags:
 *   V = video JPEG (length-prefixed), C = control JSON
 *   (mouse click / swipe / Back / Home / Recents, register, heartbeat).
 *
 * Writer thread: control JSON + heartbeat. Video frames are written by ScreenSend
 * (mutex on [writeMutex] so packets never interleave). The socket reader is a dedicated thread.
 */
class ServerConnection(
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(host: String, port: Int)
        fun onDisconnected(reason: String)
        fun onRegistered(clientId: String)
        fun onCommand(action: String, payload: JSONObject)
        fun onStatus(message: String)
        fun onError(message: String)

        /**
         * A controller took this phone. [remainMs] is the relay's time left;
         * [name]/[detail]/[device] identify that controller for the dashboard.
         */
        fun onSessionOwner(
            remainMs: Long,
            name: String,
            detail: String,
            device: String
        )

        /** The relay ended this sharing session: it is over for every controller. */
        fun onShareEnd()

        /**
         * The operator pressed Stop, or the handset's own Stop did. Sharing and
         * remote control end. The process stays up.
         */
        fun onShareStopped()

        /**
         * The controller that was holding this phone let it go or died. The phone
         * keeps sharing and goes straight back on offer, but everything belonging
         * to that controller has to be torn down first.
         */
        fun onControlRevoked()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connected = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)
    private val socketRef = AtomicReference<Socket?>(null)
    private val udpRef = AtomicReference<DatagramSocket?>(null)
    @Volatile private var relayUdpTarget: InetSocketAddress? = null
    @Volatile private var udpId: String = ""
    private val udpReady = AtomicBoolean(false)
    private val directReady = AtomicBoolean(false)
    @Volatile private var peerAddr: InetAddress? = null
    @Volatile private var peerPort: Int = 0
    private val outRef = AtomicReference<BufferedOutputStream?>(null)

    /**
     * Key of the controller currently allowed to drive this phone, handed over by
     * the relay when it grants the session. Every input event has to carry it.
     *
     * This is the only thing that can enforce one controller at a time, because a
     * controller reaches the phone over its own punched UDP and TCP paths as well
     * as through the relay, and those the relay cannot close. No key means nobody
     * has been granted this phone, and then nothing is obeyed — an unstamped event
     * is a controller that was never given the session.
     */
    @Volatile private var controlKey: String = ""

    /**
     * Second, simpler rule, on top of the key: input only runs while a monitoring
     * session is actually open. The key alone cannot carry this, because a relay
     * that never issues one leaves every controller permanently allowed — and
     * because the gap between a controller stopping and its last datagram landing
     * is exactly where stray mouse movement comes from.
     */
    @Volatile private var inputEnabled = false

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private var readerThread: Thread? = null
    private var heartbeatRunnable: Runnable? = null
    private val pendingJson = ConcurrentLinkedQueue<String>()
    private val videoHeader = ByteArray(Protocol.PACKET_HEADER_SIZE + Protocol.VIDEO_PREFIX_SIZE)
    private val writeMutex = Any()
    private val nextFrameId = AtomicInteger(1)

    /**
     * Recent frameId → capture time, so the desktop's frame_rx echo can be matched
     * back to the instant the compositor produced that frame. The desktop chooses
     * which frames to echo, so a single outstanding probe is not enough: this has
     * to remember a few seconds' worth to be sure the echoed id is still here.
     */
    private val probeLock = Any()
    private val probeIds = IntArray(PROBE_SLOTS)
    private val probeTimes = LongArray(PROBE_SLOTS)
    private var probeAt = 0
    private var lastEchoFrameId = 0
    @Volatile private var lastEchoAt = 0L
    @Volatile private var lastVideoSentAt = 0L
    private val appContext: Context? = (listener as? Context)?.applicationContext
    private var rtc: WebrtcPeer? = null

    /**
     * Video is written by a dedicated thread holding at most one frame, and callers
     * are told to drop rather than wait.
     *
     * Writing on the caller's thread made the whole stream unstable, because bytes
     * handed to the kernel are committed: it will spend however long the link needs
     * and nothing downstream can discard them. On a slow uplink a single frame was
     * measured taking 32 seconds, and for those 32 seconds the encoder was stuck
     * inside write() — producing nothing, measuring nothing, and unable to react.
     * A control loop whose only sensor reports after the fault has fully played out
     * cannot be stabilised by tuning, which is why the stream ran fast, then slow,
     * then froze, then repeated: it was a limit cycle, not a bad constant.
     *
     * Dropping at this slot instead makes the send rate equal the rate the link
     * actually drains, with no bandwidth estimate to get wrong, and it is the last
     * point where a stale frame still costs nothing to discard.
     */
    private val videoLock = Object()
    private val outQueue = ArrayDeque<OutFrame>()
    private var videoSending = false
    private var videoThread: Thread? = null
    private var videoRunning = false
    private var videoGeneration = 0

    /** How long the last frame took to reach the socket; the link's own report. */
    @Volatile
    private var lastVideoCostMs = 0L

    @Volatile
    private var host: String = ""

    @Volatile
    private var port: Int = 80

    /** Set once this connection is replaced; suppresses every listener callback. */
    @Volatile
    private var retired = false

    fun isConnected(): Boolean = connected.get()

    fun setStreaming(enabled: Boolean) {
        streaming.set(enabled)
    }

    fun connect(host: String, port: Int) {
        disconnect(reason = "Reconnecting", notify = false)
        this.host = host.trim()
        this.port = port

        val thread = HandlerThread("ServerConnection-Write", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        ioThread = thread
        ioHandler = Handler(thread.looper)
        val context = appContext
        if (context != null) {
            rtc = WebrtcPeer(
                context,
                sendSignal = { signal -> writeControlLocked(signal.toString()) },
                onControlJson = { json -> handleLine(json) },
                onStatus = { message -> postMain { listener.onStatus(message) } },
                onLastSessionClosed = {
                    // Captured first. A grant that arrives while this posts belongs
                    // to the next controller and must keep its stream.
                    val gen = ownerGen.get()
                    postMain {
                        if (!armTeardownMatching(gen)) {
                            return@postMain
                        }
                        if (streaming.getAndSet(false)) {
                            listener.onCommand(Protocol.ACTION_SCREEN_STOP, JSONObject())
                        }
                    }
                },
                onVideoReady = {
                    postMain { listener.onCommand(Protocol.ACTION_SCREEN_START, JSONObject()) }
                },
            )
        }

        val peer = rtc
        ioHandler?.post {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                // Whatever the kernel accepts is committed: it cannot be dropped when
                // a newer frame arrives, so every buffered byte is delay the viewer
                // will sit through. Keep this near one frame — still comfortably above
                // the bandwidth-delay product of a long-haul link, so throughput is
                // unaffected, but half the stale backlog of a 128 KB buffer.
                socket.sendBufferSize = 64 * 1024
                socket.receiveBufferSize = 64 * 1024
                configureKeepAliveProbes(socket)
                // Publish before connecting so cleanupSocket() can close a socket that
                // is still inside connect(). Otherwise a handshake bound to a route
                // that has gone away is unstoppable and holds the connect timeout even
                // though a usable network is already back.
                socketRef.set(socket)
                // The relay is IPv4-only. Connect to its IPv4 address explicitly so
                // the socket is AF_INET: on some boards a default dual-stack socket
                // binds to :: and, right after boot when only IPv4 has a route,
                // fails with ENETUNREACH ("from /::") until the network fully comes up.
                socket.connect(resolveTarget(this.host, this.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = 0

                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

                outRef.set(output)
                connected.set(true)

                postMain { listener.onConnected(this.host, this.port) }
                sendRegisterLocked()
                startHeartbeat()
                startVideoSender()
                openUdp()
                startInputTcp()

                // Reader must not share the writer looper — otherwise all posts stall forever.
                val reader = Thread({
                    try {
                        readLoop(input)
                    } catch (t: Throwable) {
                        if (connected.get()) {
                            postMain { listener.onError("Read error: ${t.message}") }
                            notifyDisconnected(t.message ?: "read error")
                        }
                    } finally {
                        stopHeartbeat()
                        stopVideoSender()
                        cleanupSocket()
                        releasePeer(peer)
                        connected.set(false)
                        streaming.set(false)
                    }
                }, "ServerConnection-Read")
                reader.isDaemon = true
                readerThread = reader
                reader.start()
            } catch (t: Throwable) {
                cleanupSocket()
                releasePeer(peer)
                connected.set(false)
                postMain { listener.onError("Connect failed: ${t.message}") }
                notifyDisconnected(t.message ?: "connect failed")
            }
        }
    }

    /**
     * Resolve to an IPv4 endpoint when possible so the socket is AF_INET. For a
     * literal IPv4 address this returns immediately without touching the network;
     * on failure we fall back to letting the socket resolve the host itself.
     */
    private fun resolveTarget(host: String, port: Int): InetSocketAddress {
        return try {
            val v4 = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
            if (v4 != null) InetSocketAddress(v4, port) else InetSocketAddress(host, port)
        } catch (t: Throwable) {
            InetSocketAddress(host, port)
        }
    }

    fun disconnect(reason: String = "Disconnected", notify: Boolean = true) {
        streaming.set(false)
        stopHeartbeat()
        stopVideoSender()
        releasePeer(rtc)
        cleanupSocket()
        connected.set(false)

        readerThread?.interrupt()
        readerThread = null

        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null

        if (notify) {
            notifyDisconnected(reason)
        }
    }

    /**
     * Permanently silence this connection and tear it down.
     *
     * quitSafely() cannot interrupt a socket already blocked in connect(), so a
     * replaced connection used to stay alive for the whole connect timeout and then
     * report its failure to the service — clearing the "connecting" latch while the
     * new attempt was still in flight. The 1 s poller then started yet another
     * attempt every second, piling up a dozen concurrent sockets (and duplicate
     * relay registrations). Closing the published socket now aborts that handshake
     * outright, and a retired connection must never call back again either way.
     */
    fun retire() {
        retired = true
        disconnect(reason = "Retired", notify = false)
    }

    private fun notifyDisconnected(reason: String) {
        postMain { listener.onDisconnected(reason) }
    }

    private fun sendJson(jsonLine: String) {
        pendingJson.add(jsonLine)
        val handler = ioHandler ?: return
        handler.postAtFrontOfQueue {
            flushJsonLocked()
        }
    }

    /** Tell the relay this phone is leaving so controllers drop it before the socket closes. */
    fun sendSessionEnd() {
        writeControlLocked("""{"type":"session_end"}""")
    }

    /**
     * Report whether this phone can actually serve a session right now. The relay
     * lists it to operators only while this says "ok", so a handset whose capture
     * permission or accessibility service has gone never appears as available.
     *
     * Re-sent unconditionally rather than only on change: it is two dozen bytes on
     * a link that already carries video, and a lost one would otherwise leave the
     * phone invisible until something else happened to change.
     */
    /**
     * The name was changed on this phone. Sent on the socket that is already up
     * so the controllers refresh the label without a new share.
     */
    fun sendDeviceName(name: String) {
        if (!connected.get() || name.isBlank()) {
            return
        }
        sendJson(JSONObject().put("type", "rename").put("device", name).toString())
    }

    fun sendReady(ready: Boolean, reason: String = "") {
        if (!connected.get()) {
            return
        }
        val state = if (ready) "ok" else "blocked"
        val why = reason.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
        sendJson("""{"type":"ready","state":"$state","reason":"$why"}""")
    }

    fun sendResponse(action: String, payload: JSONObject) {
        val json = Protocol.responseJson(action, payload)
        sendUdpPacket(Protocol.encodeControl(json))
    }

    /**
     * Hand a frame to the sender, or refuse it if the previous one has not left yet.
     * Refusing is normal and not an error: the caller keeps the regions unsent so a
     * later frame carries them, which is cheaper than queueing a picture that will
     * be stale by the time the link gets to it.
     */
    fun sendFrame(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        length: Int = jpeg.size,
        capturedAt: Long = 0L,
    ): Boolean {
        if (!connected.get() || !streaming.get() || length <= 0) return false
        val total = length.coerceAtMost(jpeg.size)
        val copy = ByteArray(total)
        System.arraycopy(jpeg, 0, copy, 0, total)
        synchronized(videoLock) {
            if (!videoRunning) return false
            outQueue.clear()
            outQueue.addLast(OutFrame(copy, width, height, total, capturedAt))
            videoLock.notifyAll()
        }
        return true
    }

    /** True while a frame is still on its way out, so a new one would only queue. */
    fun isSendBusy(): Boolean = synchronized(videoLock) { outQueue.isNotEmpty() || videoSending }

    fun lastSendCostMs(): Long = lastVideoCostMs

    private fun noteProbe(frameId: Int, capturedAt: Long) {
        if (capturedAt <= 0L) return
        synchronized(probeLock) {
            probeIds[probeAt] = frameId
            probeTimes[probeAt] = capturedAt
            probeAt = (probeAt + 1) % PROBE_SLOTS
        }
    }

    /** Capture time of [frameId], or 0 if it has already aged out of the ring. */
    private fun takeProbe(frameId: Int): Long {
        if (frameId == 0) return 0L
        synchronized(probeLock) {
            for (i in 0 until PROBE_SLOTS) {
                if (probeIds[i] == frameId) {
                    probeIds[i] = 0
                    return probeTimes[i]
                }
            }
        }
        return 0L
    }

    /**
     * Only once this desktop has proven it echoes (lastEchoAt set), so an older
     * build that never sends frame_rx is not mistaken for a dead path.
     */
    private fun checkEchoStall() {
        if (!streaming.get()) return
        val echoAt = lastEchoAt
        if (echoAt == 0L) return
        val now = SystemClock.uptimeMillis()
        if (now - lastVideoSentAt < ECHO_STALL_MS && now - echoAt > ECHO_STALL_MS) {
            SilentScreenStreamer.onPathStall()
        }
    }

    /**
     * The desktop sends each echo on several transports at once, so the same id
     * arrives more than once; only the first copy is a measurement.
     */
    private fun handleFrameEcho(obj: JSONObject) {
        val frameId = obj.optInt("frameId", 0)
        if (frameId == 0) return
        synchronized(probeLock) {
            if (frameId == lastEchoFrameId) return
            lastEchoFrameId = frameId
        }
        val now = SystemClock.uptimeMillis()
        lastEchoAt = now
        val capturedAt = takeProbe(frameId)
        // An id that has already left the ring is older than PROBE_SLOTS frames:
        // the queue is seconds deep, which is exactly the case to react to.
        val loopMs = if (capturedAt > 0L) now - capturedAt else STALE_ECHO_LOOP_MS
        if (capturedAt > 0L) {
            StreamMetrics.noteLoop(loopMs)
        }
        SilentScreenStreamer.onPathFeedback(loopMs, obj.optDouble("loss", -1.0))
    }

    /**
     * Log the last second of the pipeline and mirror it to the desktop, so both
     * ends' numbers end up in one log next to each other. Deliberately on the
     * relay TCP socket rather than UDP: a stats line that goes missing exactly
     * when the path is congested is missing exactly when it was needed.
     */
    private fun reportStreamMetrics() {
        val line = StreamMetrics.takeReport(SystemClock.uptimeMillis()) ?: return
        if (!streaming.get()) return
        Log.i(StreamMetrics.TAG, line)
        writeControlLocked(StreamMetrics.reportJson(line))
    }

    private fun startVideoSender() {
        stopVideoSender()
        synchronized(videoLock) {
            videoRunning = true
            videoSending = false
            outQueue.clear()
        }
        val generation = ++videoGeneration
        val t = Thread({
            while (true) {
                val frame = synchronized(videoLock) {
                    if (!videoRunning || generation != videoGeneration) return@Thread
                    if (outQueue.isEmpty()) {
                        null
                    } else {
                        val newest = outQueue.removeLast()
                        outQueue.clear()
                        newest
                    }
                }
                if (frame != null) {
                    synchronized(videoLock) { videoSending = true }
                    val startedAt = System.currentTimeMillis()
                    writeVideoLocked(
                        frame.bytes, frame.width, frame.height, frame.length, frame.capturedAt
                    )
                    lastVideoCostMs = System.currentTimeMillis() - startedAt
                    synchronized(videoLock) { videoSending = false }
                }
                try {
                    Thread.sleep(SEND_SLEEP_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "ServerConnection-Video")
        t.isDaemon = true
        videoThread = t
        t.start()
    }

    private fun stopVideoSender() {
        synchronized(videoLock) {
            videoRunning = false
            outQueue.clear()
            videoGeneration++
            videoLock.notifyAll()
        }
        videoThread?.interrupt()
        videoThread = null
    }

    private fun flushJsonLocked() {
        while (true) {
            val line = pendingJson.poll() ?: break
            if (!writeControlLocked(line, flush = true)) return
        }
    }

    private fun sendRegisterLocked() {
        val ctx = DeviceInfo
        writeControlLocked(
            Protocol.registerJson(
                id = ctx.phone,
                device = ctx.device,
                model = ctx.model,
                os = ctx.os,
                shareId = ctx.shareId
            )
        )
    }

    private fun configureKeepAliveProbes(socket: Socket) {
        try {
            val implField = socket.javaClass.getDeclaredField("impl").also { it.isAccessible = true }
            val impl = implField.get(socket) ?: return
            val fd = impl.javaClass.getDeclaredMethod("getFileDescriptor").also {
                it.isAccessible = true
            }.invoke(impl) ?: return

            val osConstants = Class.forName("android.system.OsConstants")
            val os = Class.forName("android.system.Os")
            val setsockoptInt = os.getMethod(
                "setsockoptInt",
                java.io.FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val solTcp = try {
                osConstants.getField("IPPROTO_TCP").getInt(null)
            } catch (_: Throwable) {
                6
            }
            fun setOpt(name: String, value: Int) {
                try {
                    val opt = osConstants.getField(name).getInt(null)
                    setsockoptInt.invoke(null, fd, solTcp, opt, value)
                } catch (_: Throwable) {
                }
            }
            setOpt("TCP_KEEPIDLE", 15)
            setOpt("TCP_KEEPINTVL", 5)
            setOpt("TCP_KEEPCNT", 4)
        } catch (_: Throwable) {
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                if (!connected.get()) return
                writeControlLocked(Protocol.heartbeatJson())
                sendUdpHello()
                reportStreamMetrics()
                checkEchoStall()
                ioHandler?.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeatRunnable = runnable
        ioHandler?.postDelayed(runnable, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { ioHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun readLoop(input: BufferedInputStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        val buffer = ByteArrayOutputBuffer()
        val tmp = ByteArray(8192)
        while (connected.get() && !Thread.currentThread().isInterrupted) {
            val read = input.read(tmp)
            if (read < 0) {
                notifyDisconnected("Socket closed")
                break
            }
            buffer.write(tmp, 0, read)
            processIncoming(buffer)
        }
    }

    private fun processIncoming(buffer: ByteArrayOutputBuffer) {
        while (true) {
            val data = buffer.toByteArray()
            if (data.size < Protocol.PACKET_HEADER_SIZE) {
                return
            }
            val flag = data[0]
            if (flag != Protocol.FLAG_VIDEO && flag != Protocol.FLAG_CONTROL) {
                buffer.discard(1)
                continue
            }
            val length = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.BIG_ENDIAN).int
            val max = if (flag == Protocol.FLAG_VIDEO) {
                Protocol.MAX_VIDEO_BYTES
            } else {
                Protocol.MAX_CONTROL_BYTES
            }
            if (length <= 0 || length > max) {
                buffer.discard(1)
                continue
            }
            val total = Protocol.PACKET_HEADER_SIZE + length
            if (data.size < total) {
                return
            }
            val payload = data.copyOfRange(Protocol.PACKET_HEADER_SIZE, total)
            buffer.discard(total)
            if (flag == Protocol.FLAG_CONTROL) {
                val line = payload.toString(Charsets.UTF_8).trim()
                if (line.isNotEmpty()) {
                    handleLine(line)
                }
            }
        }
    }

    private val commandSeqLock = Any()
    private var commandSid = 0L
    private var commandSeq = 0L

    /**
     * Bumped every time a controller is granted this phone. A stop that was already
     * queued for the previous holder must not run afterwards: it would clear the
     * new key and close the stream the new controller has just opened, which is
     * the frozen picture with working nav buttons.
     */
    private val ownerGen = AtomicInteger(0)
    private val teardownGen = AtomicInteger(-1)

    fun beginOwner() {
        ownerGen.incrementAndGet()
    }

    /**
     * The holder we have now is leaving. Returns false when a newer grant has
     * already replaced them, in which case their stop must be ignored.
     */
    fun armTeardown(): Boolean = armTeardownMatching(ownerGen.get())

    fun armTeardownMatching(gen: Int): Boolean {
        if (ownerGen.get() != gen) {
            return false
        }
        teardownGen.set(gen)
        inputEnabled = false
        return true
    }

    /** Finish [armTeardown] on the main thread. False when a newer owner arrived. */
    fun finishTeardown(): Boolean {
        val gen = teardownGen.get()
        if (gen < 0 || ownerGen.get() != gen) {
            return false
        }
        teardownGen.set(-1)
        revokeControl()
        return true
    }

    /**
     * The desktop sends each command over UDP direct (twice) and TCP. Run the first
     * copy to arrive and drop anything not newer than what already ran, so a late
     * TCP copy can never replay an old click after newer input.
     */
    private fun acceptCommandSeq(payload: JSONObject): Boolean {
        val seq = payload.optLong("iseq", 0L)
        if (seq <= 0L) return true
        val sid = payload.optLong("isid", 0L)
        synchronized(commandSeqLock) {
            if (sid != commandSid) {
                commandSid = sid
                commandSeq = seq
                return true
            }
            if (seq <= commandSeq) return false
            commandSeq = seq
            return true
        }
    }

    /**
     * A pointer move: the controller telling us where its mouse is so the yellow
     * circle can follow it. Nothing is pressed and nothing is injected into the
     * phone, which is why dropping an older one costs nothing.
     *
     * Taps, drags, scrolls and keys are never treated this way — each of those is
     * an event in its own right and losing one changes what the phone does.
     */
    private fun isPointerMove(action: String, payload: JSONObject): Boolean {
        if (action != Protocol.ACTION_INPUT_TOUCH) {
            return false
        }
        val a = payload.optString("a")
        return a == "hover" || a == "hover_end"
    }

    private fun controlAllowed(payload: JSONObject): Boolean {
        val current = controlKey
        // A session with no key yet must still accept the controller that just
        // opened the preview. Rejecting every event here dropped clicks (and used
        // to drop the pointer) until session_owner arrived, and a lost owner
        // message left the phone looking live with a dead mouse for the whole
        // session. Once a key is installed, only that controller matches.
        if (current.isEmpty()) {
            return true
        }
        return payload.optString("ck") == current
    }

    /**
     * The controller's session is over. Forget its key so any event still in flight
     * on a direct path is ignored, and tear down the direct sockets it punched so
     * it has no channel left at all. The next controller arrives with a new key and
     * re-punches its own paths.
     */
    fun revokeControl() {
        inputEnabled = false
        controlKey = ""
        // Forget where the old controller's numbering had got to, so the next one
        // is not mistaken for a replay of it.
        synchronized(commandSeqLock) {
            commandSid = 0L
            commandSeq = 0L
        }
        val tcp = inputTcp
        inputTcp = null
        try { tcp?.close() } catch (_: Throwable) {}
        val direct = directTcp
        directTcp = null
        try { direct?.close() } catch (_: Throwable) {}
        peerAddr = null
        peerPort = 0
        directReady.set(false)
    }

    private fun handleLine(line: String) {
        try {
            val obj = JSONObject(line)
            when (obj.optString("type")) {
                "ack" -> {
                    // The relay ack is role=agent plus sessionId. Older builds omitted
                    // clientId, so onRegistered never ran and the log never said
                    // "registered" even though the TCP socket was up.
                    var clientId = obj.optString("clientId")
                    if (clientId.isBlank() && obj.optString("role") == "agent") {
                        clientId = DeviceInfo.phone
                    }
                    if (clientId.isNotBlank()) {
                        udpId = clientId
                        sendUdpHello()
                        postMain { listener.onRegistered(clientId) }
                        postMain { listener.onStatus("Registered as $clientId") }
                    }
                }

                "tcp_peer" -> openDirectTcp(obj.optString("ip"), obj.optInt("port"))

                // A controller was granted this phone. The hour starts here.
                "session_owner" -> {
                    beginOwner()
                    controlKey = obj.optString("controlKey")
                    inputEnabled = true
                    // Re-punch now rather than waiting for the next heartbeat, so
                    // the incoming controller's direct path is ready by the time
                    // it starts sending.
                    sendUdpHello()
                    val remain = obj.optLong("remainMs", 0L)
                    val name = obj.optString("name")
                    val detail = obj.optString("detail")
                    val device = obj.optString("device")
                    val desktopId = obj.optString("desktopId")
                    postMain {
                        listener.onSessionOwner(
                            remain,
                            name.ifBlank { desktopId.ifBlank { "Support controller" } },
                            detail,
                            device.ifBlank { "Remote desktop" }
                        )
                    }
                }

                // The relay closed this sharing session for everyone: the hour ran
                // out, or the controller holding it vanished without handing it
                // back. Either way nobody may take over, so stop sharing.
                "share_end" -> {
                    inputEnabled = false
                    postMain { listener.onShareEnd() }
                }

                // Controller Stop, or the same request from this phone's own button
                // once it has reached the relay. End the offer and leave the app up.
                "share_stop" -> {
                    inputEnabled = false
                    postMain { listener.onShareStopped() }
                }

                // The controller let this phone go, or the relay cut it loose.
                // Close the gate on this thread, before the teardown is posted:
                // whatever that controller sent in its last moments is already
                // being parsed right behind this line.
                "control_revoked" -> {
                    if (armTeardown()) {
                        postMain { listener.onControlRevoked() }
                    }
                }

                // The desktop echoes the id of a frame it has just decoded and
                // published, roughly once per 500 ms. Matching it against our own
                // capture time measures the whole loop on one clock, which is the
                // only way to get an end-to-end number without the phone and the
                // PC agreeing on absolute time.
                "frame_rx" -> handleFrameEcho(obj)

                "pair_ok", "sdp", "ice" -> {
                    rtc?.handleSignal(obj)
                    if (obj.optString("type") == "pair_ok") {
                        inputEnabled = true
                        // Pairing means a desktop is watching. Start capture now,
                        // instead of waiting for a command that only fits on the
                        // data channel after ICE.
                        postMain { listener.onCommand(Protocol.ACTION_SCREEN_START, JSONObject()) }
                    }
                }

                "command" -> {
                    val action = obj.optString("action")
                    val payload = obj.optJSONObject("payload") ?: JSONObject()
                    val input = action == Protocol.ACTION_INPUT_MOUSE
                        || action == Protocol.ACTION_INPUT_KEY
                        || action == Protocol.ACTION_INPUT_TOUCH
                        || action == Protocol.ACTION_INPUT_SCROLL
                        || action == Protocol.ACTION_INPUT_TEXT
                    // Monitoring starting and stopping opens and closes the input
                    // gate here, on the reader thread, rather than in the handler
                    // posted below — the stop and the events that follow it arrive
                    // back to back, and the main thread gets to them too late.
                    if (action == Protocol.ACTION_SCREEN_START) {
                        inputEnabled = true
                    } else if (action == Protocol.ACTION_SCREEN_STOP) {
                        if (!armTeardown()) {
                            return
                        }
                    }
                    // Only the current holder may drive the device. Checked before
                    // the sequence window, so a stale controller cannot advance it
                    // and make the real one's events look like duplicates.
                    if (input && (!inputEnabled || !controlAllowed(payload))) return
                    if (!acceptCommandSeq(payload)) return
                    if (input) {
                        if (isPointerMove(action, payload)) {
                            // Where the pointer is has no history worth keeping:
                            // only the latest one is true. Letting them queue is
                            // what makes the yellow circle lag behind the mouse.
                            com.screencapture.app.input.InputPump.postLatest(POINTER_TOKEN) {
                                listener.onCommand(action, payload)
                            }
                        } else {
                            com.screencapture.app.input.InputPump.post {
                                listener.onCommand(action, payload)
                            }
                        }
                    } else {
                        postMain { listener.onCommand(action, payload) }
                    }
                }

                else -> {
                }
            }
        } catch (t: Throwable) {
            postMain { listener.onError("Bad message: ${t.message}") }
        }
    }

    private fun writeControlLocked(json: String, flush: Boolean = true): Boolean {
        val packet = Protocol.encodeControl(json)
        return writeBytesLocked(packet, 0, packet.size, flush)
    }

    private fun writeVideoLocked(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        length: Int,
        capturedAt: Long = 0L,
    ): Boolean {
        if (length <= 0) return true
        val total = length.coerceAtMost(jpeg.size)
        val sliceSize = 1100
        val chunks = (total + sliceSize - 1) / sliceSize
        if (chunks <= 0) return true
        val frameId = nextFrameId.getAndIncrement()
        noteProbe(frameId, capturedAt)
        return try {
            val packets = ArrayList<ByteArray>(chunks)
            var offset = 0
            var index = 0
            while (offset < total) {
                val slice = minOf(sliceSize, total - offset)
                val payloadLen = Protocol.VIDEO_PREFIX_SIZE + slice
                val packet = ByteArray(Protocol.PACKET_HEADER_SIZE + payloadLen)
                val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
                bb.put(Protocol.FLAG_VIDEO)
                bb.putInt(payloadLen)
                bb.putInt(width)
                bb.putInt(height)
                bb.putShort(index.toShort())
                bb.putShort(chunks.toShort())
                bb.putInt(total)
                bb.putInt(frameId)
                System.arraycopy(jpeg, offset, packet, Protocol.PACKET_HEADER_SIZE + Protocol.VIDEO_PREFIX_SIZE, slice)
                packets.add(packet)
                offset += slice
                index++
            }
            // Counted here rather than per datagram inside sendUdpPacket, because
            // what matters is how many datagrams one frame costs: the desktop needs
            // every one of them to assemble it, so a frame is only as reliable as
            // the whole burst.
            val sendStartedAt = System.currentTimeMillis()
            // The frame goes out on the relay TCP socket, which the relay hands to
            // whoever is attached right now. UDP used to be the only copy, and once
            // a direct punch succeeded it went to that one address and nowhere else.
            // After Stop the punch still answered from the controller that had just
            // left, so the next controller was shown one picture and then nothing.
            var bytes = 0
            for (packet in packets) {
                bytes += packet.size
            }
            val blob = ByteArray(bytes)
            var at = 0
            for (packet in packets) {
                System.arraycopy(packet, 0, blob, at, packet.size)
                at += packet.size
            }
            val wrote = writeBytesLocked(blob, 0, blob.size, true)
            lastVideoSentAt = SystemClock.uptimeMillis()
            StreamMetrics.noteTransport(false)
            StreamMetrics.noteWrite(
                if (wrote) packets.size else 0,
                if (wrote) bytes else 0,
                System.currentTimeMillis() - sendStartedAt
            )
            wrote
        } catch (t: Throwable) {
            postMain { listener.onError("Write failed: ${t.message}") }
            cleanupSocket()
            if (connected.getAndSet(false)) {
                notifyDisconnected("Write failed")
            }
            false
        }
    }

    private fun writeBytesLocked(bytes: ByteArray, offset: Int, length: Int, flush: Boolean): Boolean {
        val out = outRef.get() ?: return false
        return try {
            synchronized(writeMutex) {
                out.write(bytes, offset, length)
                if (flush) {
                    out.flush()
                }
            }
            true
        } catch (t: Throwable) {
            postMain { listener.onError("Write failed: ${t.message}") }
            // Close socket; reader thread will finish cleanup / poller will reconnect.
            cleanupSocket()
            if (connected.getAndSet(false)) {
                notifyDisconnected("Write failed")
            }
            false
        }
    }

    private fun releasePeer(peer: WebrtcPeer?) {
        if (peer != null && rtc === peer) {
            rtc = null
            peer.close()
        }
    }

    private fun cleanupSocket() {
        pendingJson.clear()
        try {
            outRef.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
        try {
            socketRef.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
        try {
            directTcp?.close()
        } catch (_: Throwable) {
        }
        directTcp = null
        try {
            inputTcp?.close()
        } catch (_: Throwable) {
        }
        inputTcp = null
        try {
            inputServer?.close()
        } catch (_: Throwable) {
        }
        inputServer = null
        try {
            udpRef.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
    }

    @Volatile private var directTcp: Socket? = null
    @Volatile private var inputServer: ServerSocket? = null
    @Volatile private var inputTcp: Socket? = null

    private fun startInputTcp() {
        Thread({
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.soTimeout = 1
                server.bind(InetSocketAddress(9001))
                inputServer = server
                var idle = 0
                while (connected.get()) {
                    try {
                        val accepted = server.accept()
                        accepted.tcpNoDelay = true
                        idle = 0
                        readInputSocket(accepted)
                    } catch (_: java.net.SocketTimeoutException) {
                        if (++idle >= 300) {
                            idle = 0
                            dialInputTcp()
                        }
                    }
                }
            } catch (t: Throwable) {
            } finally {
                try { server.close() } catch (_: Throwable) {}
                inputServer = null
            }
        }, "ServerConnection-InputTcp").apply { isDaemon = true }.start()
    }

    private fun dialInputTcp() {
        if (inputTcp != null) return
        val addr = peerAddr ?: return
        val punch = Socket()
        try {
            punch.reuseAddress = true
            punch.tcpNoDelay = true
            punch.bind(InetSocketAddress(9001))
            punch.connect(InetSocketAddress(addr, 9001), 400)
            readInputSocket(punch)
        } catch (_: Throwable) {
            try { punch.close() } catch (_: Throwable) {}
        }
    }

    private fun readInputSocket(sock: Socket) {
        val previous = inputTcp
        inputTcp = sock
        try { previous?.close() } catch (_: Throwable) {}
        try {
            sock.soTimeout = 1
            val buf = ByteArray(8192)
            var acc = ByteArray(0)
            val input = sock.getInputStream()
            while (connected.get()) {
                val n = try {
                    input.read(buf)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (n <= 0) break
                acc += buf.copyOf(n)
                while (acc.size >= Protocol.PACKET_HEADER_SIZE) {
                    if (acc[0] != Protocol.FLAG_CONTROL) {
                        acc = acc.copyOfRange(1, acc.size)
                        continue
                    }
                    val length = ByteBuffer.wrap(acc, 1, 4).order(ByteOrder.BIG_ENDIAN).int
                    if (length <= 0 || length > Protocol.MAX_CONTROL_BYTES) {
                        acc = acc.copyOfRange(1, acc.size)
                        continue
                    }
                    val total = Protocol.PACKET_HEADER_SIZE + length
                    if (acc.size < total) break
                    val json = acc.copyOfRange(Protocol.PACKET_HEADER_SIZE, total)
                        .toString(Charsets.UTF_8).trim()
                    acc = acc.copyOfRange(total, acc.size)
                    if (json.isNotEmpty()) handleLine(json)
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (inputTcp === sock) inputTcp = null
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun openDirectTcp(ip: String, port: Int) {
        if (ip.isBlank() || port <= 0) return
        Thread({
            val relay = socketRef.get() ?: return@Thread
            val punch = Socket()
            try {
                punch.reuseAddress = true
                punch.tcpNoDelay = true
                punch.bind(InetSocketAddress(relay.localAddress, relay.localPort))
                punch.connect(InetSocketAddress(ip, port), 4000)
                punch.soTimeout = 0
                directTcp?.close()
                directTcp = punch
                val buf = ByteArray(8192)
                var acc = ByteArray(0)
                while (connected.get()) {
                    val n = punch.getInputStream().read(buf)
                    if (n <= 0) break
                    acc += buf.copyOf(n)
                    while (acc.size >= Protocol.PACKET_HEADER_SIZE) {
                        if (acc[0] != Protocol.FLAG_CONTROL) {
                            acc = acc.copyOfRange(1, acc.size)
                            continue
                        }
                        val length = ByteBuffer.wrap(acc, 1, 4).order(ByteOrder.BIG_ENDIAN).int
                        if (length <= 0 || length > Protocol.MAX_CONTROL_BYTES) {
                            acc = acc.copyOfRange(1, acc.size)
                            continue
                        }
                        val total = Protocol.PACKET_HEADER_SIZE + length
                        if (acc.size < total) break
                        val json = acc.copyOfRange(Protocol.PACKET_HEADER_SIZE, total)
                            .toString(Charsets.UTF_8).trim()
                        acc = acc.copyOfRange(total, acc.size)
                        if (json.isNotEmpty()) handleLine(json)
                    }
                }
            } catch (t: Throwable) {
                try { punch.close() } catch (_: Throwable) {}
            }
        }, "ServerConnection-TcpDirect").apply { isDaemon = true }.start()
    }

    private fun openUdp() {
        try {
            val udp = DatagramSocket()
            udp.soTimeout = 500
            // Hold at most ~one frame in the kernel send buffer. The sender thread
            // already keeps only the newest frame, but that can only bound latency
            // if send() blocks as soon as the link is full: steady-state delay is
            // roughly (send buffer size / uplink bitrate), so a large buffer parks
            // seconds of video in the kernel that then trickle out behind real time
            // (the P2P delay). One frame's worth keeps that delay to a single
            // frame's transmission time, and — because writeVideoLocked then blocks
            // for the real link time — lets the adaptive controller actually sense a
            // slow uplink and shed bitrate instead of overrunning it blindly.
            try { udp.sendBufferSize = 32 * 1024 } catch (_: Throwable) {}
            relayUdpTarget = null
            udpRef.set(udp)
            val id = appContext?.let { ServerConfig.clientId(it) }.orEmpty()
            if (id.isNotEmpty()) {
                udpId = id
            }
            sendUdpHello()
            Thread({
                val buf = ByteArray(2048)
                while (connected.get()) {
                    val sock = udpRef.get() ?: break
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (_: Throwable) {
                        continue
                    }
                    val n = packet.length
                    val fromPeer = peerAddr != null && packet.address == peerAddr && packet.port == peerPort
                    if (fromPeer) {
                        directReady.set(true)
                    }
                    if (n < 8 || buf[0] != 'R'.code.toByte() || buf[3] != 'D'.code.toByte()) {
                        continue
                    }
                    if (buf[4] == 3.toByte()) {
                        udpReady.set(true)
                        continue
                    }
                    if (buf[4] == 4.toByte() && n >= 13) {
                        val ip = byteArrayOf(buf[7], buf[8], buf[9], buf[10])
                        val p = ((buf[11].toInt() and 0xFF) shl 8) or (buf[12].toInt() and 0xFF)
                        peerAddr = InetAddress.getByAddress(ip)
                        peerPort = p
                        sendUdpPunch()
                        continue
                    }
                    if (buf[4] == 5.toByte()) {
                        directReady.set(true)
                        continue
                    }
                    if (buf[4] != 2.toByte()) {
                        continue
                    }
                    val idLen = buf[6].toInt() and 0xFF
                    val body = 7 + idLen
                    if (body >= n) continue
                    val app = buf.copyOfRange(body, n)
                    if (app.isEmpty() || app[0] != Protocol.FLAG_CONTROL || app.size < Protocol.PACKET_HEADER_SIZE) {
                        continue
                    }
                    val length = ByteBuffer.wrap(app, 1, 4).order(ByteOrder.BIG_ENDIAN).int
                    if (length <= 0 || Protocol.PACKET_HEADER_SIZE + length > app.size) continue
                    val json = app.copyOfRange(
                        Protocol.PACKET_HEADER_SIZE,
                        Protocol.PACKET_HEADER_SIZE + length
                    ).toString(Charsets.UTF_8).trim()
                    if (json.isNotEmpty()) {
                        handleLine(json)
                    }
                }
            }, "ServerConnection-Udp").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
        }
    }

    private fun sendUdpHello() {
        val id = udpId
        if (id.isEmpty()) return
        val raw = id.toByteArray(Charsets.UTF_8)
        if (raw.size > 255) return
        val out = ByteArray(7 + raw.size)
        out[0] = 'R'.code.toByte(); out[1] = 'C'.code.toByte(); out[2] = 'U'.code.toByte(); out[3] = 'D'.code.toByte()
        out[4] = 1
        out[5] = 1
        out[6] = raw.size.toByte()
        System.arraycopy(raw, 0, out, 7, raw.size)
        sendUdpRaw(out, false)
        if (peerAddr != null) {
            sendUdpPunch()
        }
    }

    private fun sendUdpPunch() {
        val addr = peerAddr ?: return
        if (peerPort == 0) return
        val id = udpId
        if (id.isEmpty()) return
        val raw = id.toByteArray(Charsets.UTF_8)
        if (raw.size > 255) return
        val out = ByteArray(7 + raw.size)
        out[0] = 'R'.code.toByte(); out[1] = 'C'.code.toByte(); out[2] = 'U'.code.toByte(); out[3] = 'D'.code.toByte()
        out[4] = 5
        out[5] = 1
        out[6] = raw.size.toByte()
        System.arraycopy(raw, 0, out, 7, raw.size)
        sendUdpRaw(out, true)
    }

    private fun sendUdpPacket(packet: ByteArray): Boolean {
        val id = udpId
        if (id.isEmpty() || packet.size > 1600) return false
        val raw = id.toByteArray(Charsets.UTF_8)
        if (raw.size > 255) return false
        val out = ByteArray(7 + raw.size + packet.size)
        out[0] = 'R'.code.toByte(); out[1] = 'C'.code.toByte(); out[2] = 'U'.code.toByte(); out[3] = 'D'.code.toByte()
        out[4] = 2
        out[5] = 1
        out[6] = raw.size.toByte()
        System.arraycopy(raw, 0, out, 7, raw.size)
        System.arraycopy(packet, 0, out, 7 + raw.size, packet.size)
        return sendUdpRaw(out, directReady.get())
    }

    private fun sendUdpRaw(bytes: ByteArray, toPeer: Boolean): Boolean {
        val sock = udpRef.get() ?: return false
        return try {
            val addr = peerAddr
            if (toPeer && addr != null && peerPort != 0) {
                sock.send(DatagramPacket(bytes, bytes.size, addr, peerPort))
            } else {
                val target = relayUdpTarget ?: resolveTarget(host, 9000).also { relayUdpTarget = it }
                sock.send(DatagramPacket(bytes, bytes.size, target.address, target.port))
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun postMain(block: () -> Unit) {
        if (retired) {
            return
        }
        mainHandler.post {
            if (!retired) {
                block()
            }
        }
    }

    private class OutFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val length: Int,
        val capturedAt: Long,
    )

    private class ByteArrayOutputBuffer {
        private var data = ByteArray(0)

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            val next = ByteArray(data.size + length)
            System.arraycopy(data, 0, next, 0, data.size)
            System.arraycopy(bytes, offset, next, data.size, length)
            data = next
        }

        fun toByteArray(): ByteArray = data

        fun discard(count: Int) {
            if (count <= 0) return
            if (count >= data.size) {
                data = ByteArray(0)
                return
            }
            data = data.copyOfRange(count, data.size)
        }
    }

    object DeviceInfo {
        @Volatile
        var phone: String = "Android"

        @Volatile
        var device: String = Build.DEVICE

        @Volatile
        var model: String = Build.MODEL

        @Volatile
        var os: String = "Android ${Build.VERSION.RELEASE}"

        /**
         * Names the current run of sharing. Resent unchanged on every reconnect so
         * the relay resumes the same session; [newShare] is called only when the
         * user starts sharing again, which is what earns a fresh hour and puts this
         * phone back in front of every controller.
         */
        @Volatile
        var shareId: String = ""
            private set

        fun newShare() {
            shareId = java.util.UUID.randomUUID().toString()
        }

        /**
         * [deviceName] is the name the user set on this phone. Only when it has
         * never been entered do we fall back to the build's own name, so a device
         * that registers before anyone opened the app still shows up somewhere.
         */
        fun refresh(androidId: String?, deviceName: String? = null) {
            phone = androidId?.takeIf { it.isNotBlank() } ?: "Android"
            device = deviceName?.takeIf { it.isNotBlank() }
                ?: Build.DEVICE.ifBlank { Build.PRODUCT }
            model = Build.MODEL
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 1_000L
        private const val SEND_SLEEP_MS = 1L
        private const val OUT_QUEUE_CAP = 3

        /** ~3 s of frames at 20 fps, so an echo always finds its capture time. */
        private const val PROBE_SLOTS = 64

        /** Loop assumed for an echo whose frame already aged out of the ring. */
        private const val STALE_ECHO_LOOP_MS = 3_000L
        /** Frames are flowing but no echo for this long: treat the path as stalled. */
        private const val ECHO_STALL_MS = 1_500L

        /**
         * The relay handshake completes well under a second even across continents,
         * so a long timeout only delays the retry. When the uplink re-establishes
         * with a new public IP the in-flight SYNs are blackholed, and every second
         * spent waiting here is a second the phone stays off the relay.
         */
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** Groups the pointer-position updates that supersede one another. */
        private val POINTER_TOKEN = Any()

        fun loadDeviceInfo(context: android.content.Context) {
            val name = ServerConfig.deviceName(context)
            val customId = ServerConfig.clientId(context)
            if (customId.isNotBlank()) {
                DeviceInfo.refresh(customId, name)
                return
            }
            val id = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            DeviceInfo.refresh(id, name)
        }
    }
}
