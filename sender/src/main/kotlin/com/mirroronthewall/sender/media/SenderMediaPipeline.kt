package com.mirroronthewall.sender.media

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import com.mirroronthewall.common.media.CaptureSizing
import com.mirroronthewall.common.media.VideoProfile
import com.mirroronthewall.common.protocol.DisplayInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import com.mirroronthewall.common.protocol.IceCandidate as SignalingIce

/**
 * Phone-side WebRTC pipeline: screen capture → H.264 (or VP8) → one send-only video
 * transceiver.
 *
 * Must be created and started by [com.mirroronthewall.sender.MirrorService] **after**
 * it has called startForeground(type = mediaProjection). [ScreenCapturerAndroid]
 * obtains the MediaProjection from the consent [Intent] inside [start]. It then
 * registers the callback and creates the VirtualDisplay, in that order (verified in
 * its bytecode, ARCHITECTURE.md §6.1).
 *
 * Not thread-safe: the owning session calls it from one dispatcher.
 */
class SenderMediaPipeline(
    private val context: Context,
    private val projectionData: Intent,
    private val tvDisplay: DisplayInfo,
    private val profile: VideoProfile,
    /** Local ICE candidates to send to the TV. Called on WebRTC's signaling thread. */
    private val onLocalIce: (SignalingIce) -> Unit,
    /** The system or the user ended the projection (revoked, screen locked, another capture). */
    private val onProjectionStopped: () -> Unit,
    /** Called on WebRTC's signaling thread. */
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit,
) {
    data class Status(val captureSize: CaptureSizing.Size, val fps: Int)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private var eglBase: EglBase? = null
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var textureHelper: SurfaceTextureHelper? = null
    // Read from the main thread by the rotation listener.
    @Volatile private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var pc: PeerConnection? = null
    private var transceiver: RtpTransceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    @Volatile private var captureSize: CaptureSizing.Size? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Builds everything and returns the SDP offer to send. */
    suspend fun start(): String {
        WebRtcInit.ensure(context)
        val egl = EglBase.create().also { eglBase = it }

        // Constrained Baseline H.264, not High: this build's HardwareVideoEncoder forces
        // AVC Level 3 when High is enabled, which caps strict encoders at about 720x576@25
        // (ARCHITECTURE.md §6.2). VP8 (HW or libvpx) remains as the fallback codec.
        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, /* enableIntelVp8Encoder = */ true, /* enableH264HighProfile = */ false)

        // No audio in Phase 2. An explicit ADM that never records keeps the default
        // module from touching the microphone. System audio arrives in Phase 4.
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule().also { adm = it }

        val options = PeerConnectionFactory.Options().apply {
            // LAN only: never gather candidates on mobile data or loopback.
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
        }
        val f = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()
            .also { factory = it }

        // Capture.
        val helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext).also { textureHelper = it }
        // isScreencast = false: WebRTC's screencast mode favors resolution over frame
        // rate and tolerates frame drops, which conflicts with priority #1 (§6.2).
        val src = f.createVideoSource(/* isScreencast = */ false).also { source = it }
        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                onProjectionStopped()
            }
        }).also { capturer = it }
        cap.initialize(helper, context, src.capturerObserver)
        val size = currentCaptureSize()
        captureSize = size
        cap.startCapture(size.width, size.height, profile.maxFps)
        _status.value = Status(size, profile.maxFps)
        Log.i(TAG, "Capturing at $size @ ${profile.maxFps} fps (TV ${tvDisplay.widthPx}x${tvDisplay.heightPx})")
        watchRotation()

        val videoTrack = f.createVideoTrack(VIDEO_TRACK_ID, src).also { track = it }

        // PeerConnection: LAN host candidates only (§5.4).
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            networkPreference = PeerConnection.AdapterType.WIFI
        }
        val peer = f.createPeerConnection(config, Observer()) ?: error("createPeerConnection returned null")
        pc = peer

        val tx = peer.addTransceiver(
            videoTrack,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID)),
        )
        transceiver = tx
        preferCodecs(f, tx)
        // Start bandwidth estimation high: the LAN can take it, and the default
        // ~300 kbps start would show a blurry first few seconds.
        peer.setBitrate(profile.minBitrateBps, profile.startBitrateBps, profile.maxBitrateBps)

        val offer = peer.awaitCreateOffer()
        peer.awaitSetLocal(offer)
        return offer.description
    }

    suspend fun onAnswer(sdp: String) {
        val peer = pc ?: return
        peer.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        remoteDescriptionSet = true
        pendingRemoteIce.forEach { peer.addIceCandidate(it) }
        pendingRemoteIce.clear()
        applySenderParameters()
    }

    fun onRemoteIce(c: SignalingIce) {
        val ice = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
        val peer = pc ?: return
        if (remoteDescriptionSet) peer.addIceCandidate(ice) else pendingRemoteIce += ice
    }

    /** Idempotent. Releases everything in dependency order (§7.3). Blocks briefly. */
    fun dispose() {
        displayListener?.let { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        capturer?.let {
            try {
                it.stopCapture()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            it.dispose()
        }
        capturer = null
        pc?.dispose() // Also disposes the transceiver and sender.
        pc = null
        transceiver = null
        track?.dispose()
        track = null
        source?.dispose()
        source = null
        textureHelper?.dispose()
        textureHelper = null
        factory?.dispose()
        factory = null
        adm?.release()
        adm = null
        eglBase?.release()
        eglBase = null
        _status.value = null
    }

    // ---------------------------------------------------------------- internals

    /** H.264 first (widest TV decoder support), then VP8. RTX is kept for NACK retransmissions. */
    private fun preferCodecs(f: PeerConnectionFactory, tx: RtpTransceiver) {
        val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        fun rank(c: org.webrtc.RtpCapabilities.CodecCapability): Int = when (c.name.uppercase()) {
            "H264" -> if (c.parameters["packetization-mode"] == "1") 0 else 1
            "VP8" -> 2
            "RTX" -> 3
            "RED", "ULPFEC" -> 4
            else -> -1 // VP9, AV1, H265: not used (§6.2).
        }
        val ordered = caps.filter { rank(it) >= 0 }.sortedBy(::rank)
        val err = tx.setCodecPreferences(ordered)
        Log.i(TAG, "Codec preferences ${ordered.map { it.name + (it.parameters["profile-level-id"]?.let { p -> "/$p" } ?: "") }} → ${err?.toString() ?: "ok"}")
    }

    /** Bitrate bounds, frame-rate cap and degradation preference (§6.3). */
    private fun applySenderParameters() {
        val sender = transceiver?.sender ?: return
        val params = sender.parameters
        params.degradationPreference = when (profile.degradation) {
            VideoProfile.Degradation.MAINTAIN_FRAMERATE -> RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            VideoProfile.Degradation.BALANCED -> RtpParameters.DegradationPreference.BALANCED
            VideoProfile.Degradation.MAINTAIN_RESOLUTION -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        }
        params.encodings.forEach { e ->
            e.minBitrateBps = profile.minBitrateBps
            e.maxBitrateBps = profile.maxBitrateBps
            e.maxFramerate = profile.maxFps
        }
        val ok = sender.setParameters(params)
        Log.i(TAG, "RtpSender.setParameters(${profile.id}) → $ok")
    }

    /** Current screen size in pixels, rotated as the user sees it. Works from a Service context. */
    private fun screenSize(): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        // Mode dimensions are in the display's natural orientation.
        val w = mode.physicalWidth
        val h = mode.physicalHeight
        val quarterTurn = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        return if (quarterTurn) h to w else w to h
    }

    private fun currentCaptureSize(): CaptureSizing.Size {
        val (w, h) = screenSize()
        return CaptureSizing.compute(w, h, tvDisplay.widthPx, tvDisplay.heightPx, profile)
    }

    /**
     * Rotating the phone swaps the screen's width and height. Resize the capture to
     * match. [ScreenCapturerAndroid.changeCaptureFormat] uses VirtualDisplay.resize()
     * (verified), which is allowed on Android 14+. Creating a second VirtualDisplay
     * would not be.
     */
    private fun watchRotation() {
        val dm = context.getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val cap = capturer ?: return
                val newSize = currentCaptureSize()
                if (newSize != captureSize) {
                    Log.i(TAG, "Display changed: capture $captureSize → $newSize")
                    try {
                        cap.changeCaptureFormat(newSize.width, newSize.height, profile.maxFps)
                        captureSize = newSize
                        _status.value = Status(newSize, profile.maxFps)
                    } catch (e: RuntimeException) {
                        // Raced with dispose() on the session thread: the capturer is gone.
                        Log.w(TAG, "Resize skipped: ${e.message}")
                    }
                }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayListener = l
        dm.registerDisplayListener(l, mainHandler)
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            onLocalIce(SignalingIce(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "PeerConnection: $newState")
            onConnectionState(newState)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        // Negotiation is driven explicitly by start() and, in Phase 3, by ICE restarts.
        override fun onRenegotiationNeeded() = Unit
    }

    private companion object {
        const val TAG = "SenderMedia"
        const val STREAM_ID = "mirror"
        const val VIDEO_TRACK_ID = "screen"
    }
}
