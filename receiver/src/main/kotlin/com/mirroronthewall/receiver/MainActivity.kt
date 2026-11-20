package com.mirroronthewall.receiver

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mirroronthewall.common.signaling.SignalingServer
import kotlinx.coroutines.launch

/**
 * TV home screen: shows the TV's name, address and pairing code, and the session
 * status. The signaling server runs while this activity is started (on screen).
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: ReceiverController

    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var codeLabelView: TextView
    private lateinit var codeView: TextView
    private lateinit var addressView: TextView
    private lateinit var discoveryView: TextView
    private lateinit var noticeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nameView = findViewById(R.id.receiver_name)
        statusView = findViewById(R.id.session_status)
        codeLabelView = findViewById(R.id.code_label)
        codeView = findViewById(R.id.pairing_code)
        addressView = findViewById(R.id.address)
        discoveryView = findViewById(R.id.discovery_status)
        noticeView = findViewById(R.id.notice)

        controller = ReceiverController(applicationContext, lifecycleScope)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.uiState.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    private fun render(s: ReceiverController.UiState) {
        nameView.text = s.receiverName

        statusView.text = when (val st = s.server) {
            is SignalingServer.State.Failed -> getString(R.string.status_failed, st.error.message ?: st.error::class.java.simpleName)
            SignalingServer.State.Starting -> getString(R.string.status_starting)
            SignalingServer.State.Stopped -> getString(R.string.status_stopped)
            is SignalingServer.State.Listening ->
                s.peer?.let { getString(R.string.status_connected, it.senderName) } ?: getString(R.string.status_waiting)
        }

        // While a phone is connected, the code isn't needed. Hide it to keep the screen calm.
        val showCode = s.peer == null
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

        // Keep the TV awake while a phone is connected (the video itself comes in Phase 2).
        if (s.peer != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
