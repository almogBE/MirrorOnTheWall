package com.mirroronthewall.receiver

import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mirroronthewall.common.signaling.SignalingServer
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * TV screen. While idle it shows the TV's name, address and pairing code. While a
 * phone mirrors, it shows the video full screen. The signaling server runs while this
 * activity is started (on screen).
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: ReceiverController

    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var infoPanel: View
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var codeLabelView: TextView
    private lateinit var codeView: TextView
    private lateinit var addressView: TextView
    private lateinit var discoveryView: TextView
    private lateinit var noticeView: TextView

    /** Session whose first frame has been rendered. The info panel is hidden while this matches the active session. */
    @Volatile private var firstFrameSessionId: Long? = null
    private var currentSessionId: Long? = null
    private var videoModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        renderer = findViewById(R.id.video)
        infoPanel = findViewById(R.id.info_panel)
        nameView = findViewById(R.id.receiver_name)
        statusView = findViewById(R.id.session_status)
        codeLabelView = findViewById(R.id.code_label)
        codeView = findViewById(R.id.pairing_code)
        addressView = findViewById(R.id.address)
        discoveryView = findViewById(R.id.discovery_status)
        noticeView = findViewById(R.id.notice)

        controller = ReceiverController(applicationContext, lifecycleScope)

        renderer.init(controller.eglContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                runOnUiThread {
                    firstFrameSessionId = currentSessionId
                    render(controller.uiState.value)
                }
            }

            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit
        })
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        // The SurfaceView buffer matches the video size and the display hardware does the
        // scaling. This saves a GPU pass per frame on the TV.
        renderer.setEnableHardwareScaler(true)
        renderer.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (videoModeActive) DisplayRefresh.request(this@MainActivity, holder.surface, STREAM_FPS)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.uiState.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.videoSink.target = renderer
        controller.start()
    }

    override fun onStop() {
        controller.videoSink.target = null
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        renderer.release()
        controller.release()
        super.onDestroy()
    }

    private fun render(s: ReceiverController.UiState) {
        val peer = s.peer
        if (peer?.sessionId != currentSessionId) {
            // New session or session ended: forget the old frame and show the panel again.
            currentSessionId = peer?.sessionId
            firstFrameSessionId = null
            renderer.clearImage()
        }
        val showingVideo = peer != null && firstFrameSessionId == peer.sessionId
        infoPanel.visibility = if (showingVideo) View.GONE else View.VISIBLE
        setVideoMode(showingVideo)

        nameView.text = s.receiverName
        statusView.text = when (val st = s.server) {
            is SignalingServer.State.Failed -> getString(R.string.status_failed, st.error.message ?: st.error::class.java.simpleName)
            SignalingServer.State.Starting -> getString(R.string.status_starting)
            SignalingServer.State.Stopped -> getString(R.string.status_stopped)
            is SignalingServer.State.Listening -> when {
                peer == null -> getString(R.string.status_waiting)
                s.video == PeerConnection.PeerConnectionState.FAILED -> getString(R.string.status_video_failed, peer.senderName)
                s.video != null -> getString(R.string.status_video_connecting, peer.senderName)
                else -> getString(R.string.status_connected, peer.senderName)
            }
        }

        // While a phone is connected, the code isn't needed. Hide it to keep the screen calm.
        val showCode = peer == null
        codeLabelView.visibility = if (showCode) View.VISIBLE else View.GONE
        codeView.visibility = if (showCode) View.VISIBLE else View.GONE
        codeView.text = s.pairingCode

        val port = (s.server as? SignalingServer.State.Listening)?.port
        addressView.text = when {
            s.ipv4 == null -> getString(R.string.address_no_network)
            port == null -> ""
            else -> getString(R.string.address_format, s.ipv4, port)
        }

        discoveryView.text = when (val n = s.nsd) {
            is NsdAdvertiser.Status.Registered -> getString(R.string.discovery_registered, n.serviceName)
            NsdAdvertiser.Status.Registering -> getString(R.string.discovery_registering)
            is NsdAdvertiser.Status.Failed -> getString(R.string.discovery_failed, n.errorCode)
            NsdAdvertiser.Status.Idle -> ""
        }

        noticeView.text = s.notice.orEmpty()

        // Keep the TV awake while a phone is connected.
        if (peer != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * While video is on screen: request the TV's low-latency game mode (ALLM, API 30+)
     * and a display refresh rate that matches the stream. Both requests are withdrawn
     * when video stops, so the idle screen uses the TV's normal picture mode.
     */
    private fun setVideoMode(on: Boolean) {
        if (on == videoModeActive) return
        videoModeActive = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setPreferMinimalPostProcessing(on)
        }
        if (on) {
            DisplayRefresh.request(this, renderer.holder.surface, STREAM_FPS)
        } else {
            DisplayRefresh.clear(this, renderer.holder.surface)
        }
    }

    private companion object {
        /** The sender's frame rate cap. Phase 3 profiles all use 60 fps. */
        const val STREAM_FPS = 60f
    }
}
