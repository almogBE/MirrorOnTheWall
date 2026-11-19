package com.mirroronthewall.receiver

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mirroronthewall.common.MirrorConstants

/**
 * Phase 0 shell. It only shows that the app starts on a TV and links :common.
 * The signaling server, NSD advertising and video rendering are NOT
 * implemented yet (Phases 1 and 2).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.protocol_info).text = getString(
            R.string.protocol_info,
            MirrorConstants.PROTOCOL_VERSION,
            MirrorConstants.NSD_SERVICE_TYPE,
        )
    }
}
