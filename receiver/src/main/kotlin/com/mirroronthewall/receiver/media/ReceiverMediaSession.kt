package com.mirroronthewall.receiver.media

import android.util.Log
import com.mirroronthewall.common.protocol.Answer
import com.mirroronthewall.common.protocol.SignalingMessage
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import com.mirroronthewall.common.protocol.IceCandidate as SignalingIce

/**
 * One WebRTC receive session. The TV only answers. The phone is the offerer and
 * controlling ICE agent (§5.3).
 *
 * The remote video track feeds [sink], a [ProxyVideoSink] that the activity points
 * at its SurfaceViewRenderer.
 *
 * Called from one thread (the controller's), except the PeerConnection observer,
 * which WebRTC calls on its signaling thread.
 */
class ReceiverMediaSession(
    private val webRtc: ReceiverWebRtc,
    private val sink: ProxyVideoSink,
    private val send: (SignalingMessage) -> Boolean,
    /** Called on WebRTC's signaling thread. */
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
) {
    private var pc: PeerConnection? = null
    private var remoteDescriptionSet = false
    private val pendingIce = mutableListOf<IceCandidate>()
    @Volatile private var remoteTrack: VideoTrack? = null
    @Volatile private var disposed = false

    /** Applies the phone's offer and sends our answer. Later offers (ICE restart, Phase 3) reuse the PeerConnection. */
    suspend fun onOffer(sdp: String) {
        val peer = pc ?: createPeerConnection().also { pc = it }
        peer.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        remoteDescriptionSet = true
        pendingIce.forEach { peer.addIceCandidate(it) }
        pendingIce.clear()
        val answer = peer.awaitCreateAnswer()
        peer.awaitSetLocal(answer)
        send(Answer(answer.description))
    }

    fun onRemoteIce(c: SignalingIce) {
        val ice = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
        val peer = pc
        if (peer != null && remoteDescriptionSet) peer.addIceCandidate(ice) else pendingIce += ice
    }

    /** Idempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        remoteTrack?.let {
            try {
                it.removeSink(sink)
            } catch (e: IllegalStateException) {
                // Track already disposed with its PeerConnection.
            }
        }
        remoteTrack = null
        pc?.dispose()
        pc = null
    }

    private fun createPeerConnection(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        return webRtc.factory.createPeerConnection(config, Observer()) ?: error("createPeerConnection returned null")
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track() as? VideoTrack ?: return
            if (disposed) return
            Log.i(TAG, "Remote video track ${track.id()}")
            remoteTrack = track
            track.addSink(sink)
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            send(SignalingIce(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "PeerConnection: $newState")
            onState(newState)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE: $state")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private companion object {
        const val TAG = "ReceiverMedia"
    }
}
