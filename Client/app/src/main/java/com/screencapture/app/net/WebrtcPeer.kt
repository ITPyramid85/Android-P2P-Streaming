package com.screencapture.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone side of the screen session. The desktop creates the offer and the two
 * data channels; this side answers. Video and control packets are the same
 * length-prefixed frames the desktop already understands, carried on SCTP
 * instead of the signaling TCP socket.
 *
 * The signaling socket only delivers [handleSignal] (pair_ok / sdp / ice).
 */
class WebrtcPeer(
    context: Context,
    private val sendSignal: (JSONObject) -> Unit,
    private val onControlJson: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLastSessionClosed: () -> Unit,
    private val onVideoReady: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val iceByDesktop = ConcurrentHashMap<String, List<PeerConnection.IceServer>>()
    @Volatile private var shuttingDown = false

    fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "pair_ok" -> openSession(message)
            "sdp" -> {
                val id = message.optString("desktopId")
                if (message.optString("sdpType") == "offer") {
                    val current = sessions[id]
                    if (current != null && current.needsNewPeer()) {
                        current.close()
                        val servers = iceByDesktop[id]
                        if (servers != null) {
                            val fresh = Session(id, servers)
                            sessions[id] = fresh
                            fresh.acceptOffer(message.optString("sdp"))
                        }
                    } else {
                        current?.acceptOffer(message.optString("sdp"))
                    }
                }
            }
            "ice" -> {
                val id = message.optString("desktopId")
                sessions[id]?.addIce(message.optString("candidate"), message.optString("mid"))
            }
        }
    }

    fun sendVideo(packets: List<ByteArray>): Boolean {
        val open = sessions.values.mapNotNull { it.videoChannel() }
        if (open.isEmpty()) {
            return false
        }
        if (open.any { it.bufferedAmount() > MAX_VIDEO_BUFFER }) {
            return true
        }
        for (packet in packets) {
            for (channel in open) {
                if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet), true))) {
                    return false
                }
            }
        }
        return true
    }

    fun sendControl(packet: ByteArray): Boolean {
        val open = sessions.values.mapNotNull { it.controlChannel() }
        if (open.isEmpty()) {
            return false
        }
        var sent = false
        for (channel in open) {
            if (channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet), true))) {
                sent = true
            }
        }
        return sent
    }

    fun hasVideoChannel(): Boolean = sessions.values.any { it.videoChannel() != null }

    fun close() {
        shuttingDown = true
        val live = sessions.values.toList()
        sessions.clear()
        for (session in live) {
            session.close()
        }
    }

    private fun openSession(message: JSONObject) {
        val desktopId = message.optString("desktopId")
        if (desktopId.isBlank()) {
            return
        }
        sessions.remove(desktopId)?.close()
        ensureFactory()
        val iceServers = ArrayList<PeerConnection.IceServer>()
        val listed = message.optJSONArray("iceServers")
        if (listed != null) {
            for (i in 0 until listed.length()) {
                val item = listed.optJSONObject(i) ?: continue
                val urls = item.optString("urls").ifBlank {
                    item.optJSONArray("urls")?.optString(0).orEmpty()
                }
                if (urls.isBlank()) {
                    continue
                }
                val builder = PeerConnection.IceServer.builder(urls)
                val user = item.optString("username")
                val cred = item.optString("credential")
                if (user.isNotBlank()) {
                    builder.setUsername(user)
                    builder.setPassword(cred)
                }
                iceServers.add(builder.createIceServer())
            }
        }
        if (iceServers.isEmpty()) {
            iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        }
        iceByDesktop[desktopId] = iceServers
        val session = Session(desktopId, iceServers)
        sessions[desktopId] = session
        onStatus("WebRTC: paired with $desktopId, gathering candidates")
    }

    private fun dropSession(desktopId: String) {
        val removed = sessions.remove(desktopId) ?: return
        removed.close()
        if (!shuttingDown && sessions.isEmpty()) {
            onStatus("WebRTC: P2P closed")
            onLastSessionClosed()
        }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    private inner class Session(
        private val desktopId: String,
        iceServers: List<PeerConnection.IceServer>,
    ) {
        private val pendingCandidates = ArrayList<IceCandidate>()
        private var remoteSet = false
        private var iceFailed = false
        private var closed = false
        private var video: DataChannel? = null
        private var control: DataChannel? = null
        private val pc: PeerConnection

        init {
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            pc = factory().createPeerConnection(config, observer())
                ?: throw IllegalStateException("PeerConnection was not created")
        }

        fun needsNewPeer(): Boolean = iceFailed

        fun videoChannel(): DataChannel? = video?.takeIf { it.state() == DataChannel.State.OPEN }

        fun controlChannel(): DataChannel? = control?.takeIf { it.state() == DataChannel.State.OPEN }

        fun acceptOffer(sdp: String) {
            if (closed || sdp.isBlank()) {
                return
            }
            // A retry after ICE FAILED sends a fresh offer on the same desktop id.
            if (remoteSet && !iceFailed) {
                return
            }
            iceFailed = false
            remoteSet = false
            pc.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() {
                    remoteSet = true
                    val queued = pendingCandidates.toList()
                    pendingCandidates.clear()
                    for (candidate in queued) {
                        pc.addIceCandidate(candidate)
                    }
                    pc.createAnswer(object : SdpAdapter() {
                        override fun onCreateSuccess(description: SessionDescription) {
                            pc.setLocalDescription(object : SdpAdapter() {
                                override fun onSetSuccess() {
                                    sendSignal(
                                        JSONObject()
                                            .put("type", "sdp")
                                            .put("desktopId", desktopId)
                                            .put("sdpType", "answer")
                                            .put("sdp", description.description)
                                    )
                                }
                            }, description)
                        }
                    }, MediaConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        }

        fun addIce(candidate: String, mid: String) {
            if (closed || candidate.isBlank()) {
                return
            }
            val ice = IceCandidate(mid.ifBlank { "0" }, 0, candidate)
            if (!remoteSet) {
                pendingCandidates.add(ice)
            } else {
                pc.addIceCandidate(ice)
            }
        }

        fun close() {
            if (closed) {
                return
            }
            closed = true
            try {
                video?.close()
                control?.close()
                pc.close()
                pc.dispose()
            } catch (t: Throwable) {
            }
        }

        private fun observer() = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onStatus("WebRTC: ICE $state")
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    iceFailed = true
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                val line = candidate.sdp ?: return
                if (line.isBlank()) {
                    return
                }
                sendSignal(
                    JSONObject()
                        .put("type", "ice")
                        .put("desktopId", desktopId)
                        .put("candidate", line)
                        .put("mid", candidate.sdpMid ?: "0")
                )
                if (line.contains("typ relay")) {
                    onStatus("WebRTC: using TURN")
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                when (channel.label()) {
                    "video" -> video = channel
                    "ctrl" -> control = channel
                }
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        if (channel.state() == DataChannel.State.OPEN) {
                            if (channel.label() == "ctrl") {
                                onStatus("WebRTC: P2P connected")
                            }
                            if (channel.label() == "video") {
                                onVideoReady()
                            }
                        }
                        if (channel.state() == DataChannel.State.CLOSED && !closed && !shuttingDown) {
                            mainHandler.post { dropSession(desktopId) }
                        }
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (channel.label() != "ctrl") {
                            return
                        }
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        controlJson(bytes)?.let(onControlJson)
                    }
                })
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                mediaStreams: Array<out org.webrtc.MediaStream>,
            ) {}
        }
    }

    private fun controlJson(packet: ByteArray): String? {
        if (packet.size < Protocol.PACKET_HEADER_SIZE || packet[0] != Protocol.FLAG_CONTROL) {
            return null
        }
        val length = ByteBuffer.wrap(packet, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        val total = Protocol.PACKET_HEADER_SIZE + length
        if (length <= 0 || total > packet.size) {
            return null
        }
        return packet.copyOfRange(Protocol.PACKET_HEADER_SIZE, total).toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_VIDEO_BUFFER = 48 * 1024L
        private val factoryLock = Any()
        private var factoryRef: PeerConnectionFactory? = null

        private fun ensureFactory(context: Context) {
            if (factoryRef != null) {
                return
            }
            synchronized(factoryLock) {
                if (factoryRef != null) {
                    return
                }
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        // LDPlayer otherwise advertises a .local host candidate the
                        // desktop cannot resolve, so ICE never opens the video channel.
                        .setFieldTrials("WebRTC-HideLocalIpsWithMdns/Disabled/")
                        .createInitializationOptions()
                )
                factoryRef = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                    .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
                    .createPeerConnectionFactory()
            }
        }
    }

    private fun ensureFactory() {
        Companion.ensureFactory(appContext)
    }

    private fun factory(): PeerConnectionFactory = factoryRef
        ?: throw IllegalStateException("WebRTC factory is not initialized")
}
