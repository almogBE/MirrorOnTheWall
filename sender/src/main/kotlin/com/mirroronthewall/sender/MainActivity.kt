package com.mirroronthewall.sender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mirroronthewall.common.MirrorConstants

/**
 * Phase 0 shell. It only shows that the app starts and links :common.
 * Discovery, pairing and mirroring are NOT implemented yet (Phases 1 and 2).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    SkeletonScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun SkeletonScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.skeleton_notice), style = MaterialTheme.typography.bodyLarge)
        Text(
            "Protocol v${MirrorConstants.PROTOCOL_VERSION} · ${MirrorConstants.NSD_SERVICE_TYPE}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
