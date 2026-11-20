package com.mirroronthewall.receiver.media

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Long-lived WebRTC objects of the TV app: one EGL context, shared by the decoder
 * (texture output) and the SurfaceViewRenderer, and one PeerConnectionFactory.
 * PeerConnections come and go per session. This environment lives as long as the
 * activity.
 */
class ReceiverWebRtc(context: Context) {

    val eglBase: EglBase = EglBase.create()
    private val adm: JavaAudioDeviceModule
    val factory: PeerConnectionFactory

    init {
        ensureInitialized(context)
        // DefaultVideoDecoderFactory: MediaCodec hardware first, libvpx software as the
        // VP8 fallback. This build has no software H.264 decoder, so H.264 is offered
        // in the answer only if the TV has a hardware decoder for it (§6.4).
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        // The receiver never sends video. The encoder factory only completes the setup.
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, false, false)
        // No audio until Phase 4.
        adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
            })
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    fun release() {
        factory.dispose()
        adm.release()
        eglBase.release()
    }

    private companion object {
        @Volatile var initialized = false

        @Synchronized
        fun ensureInitialized(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    // Field trials (playout-delay experiments, Phase 3) go through
                    // PeerConnectionFactory.Builder.setFieldTrials. The InitializationOptions variant is deprecated.
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }
}

/**
 * Forwards frames to whatever view is currently attached. The track's sink is this
 * proxy, never the view itself. The view can then come and go (activity stop and
 * start) without touching a track that may already be disposed.
 */
class ProxyVideoSink : VideoSink {
    @Volatile var target: VideoSink? = null

    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

class SdpException(message: String) : Exception(message)

suspend fun PeerConnection.awaitCreateAnswer(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(SdpException("createAnswer: $error"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
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
