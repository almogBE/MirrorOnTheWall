package com.mirroronthewall.sender.media

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Process-wide WebRTC initialization (must happen once, before any factory is built). */
object WebRtcInit {
    @Volatile private var done = false

    @Synchronized
    fun ensure(context: Context) {
        if (done) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                // Field trials (playout-delay experiments, Phase 3) go through
                // PeerConnectionFactory.Builder.setFieldTrials. The InitializationOptions variant is deprecated.
                .createInitializationOptions(),
        )
        done = true
    }
}

class SdpException(message: String) : Exception(message)

suspend fun PeerConnection.awaitCreateOffer(constraints: MediaConstraints = MediaConstraints()): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(SdpException("createOffer: $error"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet("setLocalDescription") { setLocalDescription(it, sdp) }

suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet("setRemoteDescription") { setRemoteDescription(it, sdp) }

private suspend fun awaitSet(what: String, call: (SdpObserver) -> Unit) =
    suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(SdpException("$what: $error"))
        })
    }
