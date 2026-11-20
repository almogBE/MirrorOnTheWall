package com.mirroronthewall.sender.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.sender.R
import com.mirroronthewall.sender.SenderViewModel
import com.mirroronthewall.sender.MirrorService
import com.mirroronthewall.sender.SenderSession.ConnectionUi
import com.mirroronthewall.sender.SenderSession.MirrorUi
import com.mirroronthewall.sender.SenderViewModel.DiscoveryUi
import com.mirroronthewall.sender.discovery.DiscoveredReceiver

@Composable
fun SenderApp(vm: SenderViewModel, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val discovery by vm.discoveryUi.collectAsStateWithLifecycle()
    var showManual by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        when (val c = connection) {
            is ConnectionUi.Connected -> ConnectedScreen(c, onDisconnect = vm::disconnect, onStopMirroring = vm::stopMirroring)
            is ConnectionUi.Connecting -> ConnectingRow(stringResource(R.string.connecting_to, c.target.name))
            else -> ReceiverList(
                discovery = discovery,
                onPick = vm::connect,
                onManual = { showManual = true },
                message = c as? ConnectionUi.Message,
                onDismissError = vm::dismissMessage,
            )
        }
    }

    val needsCode = connection as? ConnectionUi.NeedsCode
    if (needsCode != null) {
        PairingCodeDialog(
            receiverName = needsCode.target.name,
            wrong = needsCode.previousCodeWasWrong,
            onSubmit = vm::submitCode,
            onDismiss = vm::dismissMessage,
        )
    }
    if (showManual) {
        ManualAddressDialog(
            initialAddress = vm.lastManualAddress.orEmpty(),
            onConnect = { address, code -> vm.connectManual(address, code) },
            onDismiss = { showManual = false },
        )
    }
}

@Composable
private fun ReceiverList(
    discovery: DiscoveryUi,
    onPick: (DiscoveredReceiver) -> Unit,
    onManual: () -> Unit,
    message: ConnectionUi.Message?,
    onDismissError: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.choose_tv), style = MaterialTheme.typography.titleMedium)

        if (message != null) {
            Card(Modifier.fillMaxWidth().clickable(onClick = onDismissError)) {
                Text(
                    message.text,
                    Modifier.padding(16.dp),
                    color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        when (discovery) {
            DiscoveryUi.Searching -> SearchingRow()
            is DiscoveryUi.Failed -> Text(
                stringResource(R.string.discovery_failed, discovery.message),
                color = MaterialTheme.colorScheme.error,
            )
            is DiscoveryUi.Found -> {
                if (discovery.receivers.isEmpty()) {
                    SearchingRow()
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(discovery.receivers, key = { it.serviceName }) { r ->
                            ReceiverCard(r, onClick = { onPick(r) })
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.enter_address))
        }
    }
}

@Composable
private fun ReceiverCard(r: DiscoveredReceiver, onClick: () -> Unit) {
    val enabled = r.endpoint != null && r.isCompatible
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(r.displayName, style = MaterialTheme.typography.titleMedium)
            val sub = when {
                !r.isCompatible -> stringResource(R.string.receiver_incompatible, r.protocolVersion ?: 0)
                r.endpoint == null -> stringResource(R.string.receiver_resolving)
                else -> r.endpoint.toString()
            }
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConnectingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(text)
    }
}

@Composable
private fun ConnectedScreen(c: ConnectionUi.Connected, onDisconnect: () -> Unit, onStopMirroring: () -> Unit) {
    val context = LocalContext.current
    var consentDenied by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            consentDenied = false
            MirrorService.start(context, data)
        } else {
            consentDenied = true
        }
    }
    // Android 13+: ask for notification permission first, so the "mirroring" notification
    // with its Stop button is visible. Mirroring works either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        consent.launch(screenCaptureIntent(context))
    }
    val startMirroring = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            consent.launch(screenCaptureIntent(context))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.connected_to, c.receiverName), style = MaterialTheme.typography.titleLarge)
        if (c.newlyPaired) Text(stringResource(R.string.paired_note), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.tv_address, c.target.endpoint.toString()), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.tv_display, "${c.tvDisplay.widthPx}×${c.tvDisplay.heightPx} @ ${"%.0f".format(c.tvDisplay.refreshHz)} Hz"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            c.signalingRttMs?.let { stringResource(R.string.signaling_rtt, "%.1f".format(it)) }
                ?: stringResource(R.string.signaling_rtt_pending),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        when (val m = c.mirror) {
            MirrorUi.Off -> {
                Button(onClick = startMirroring, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.start_mirroring))
                }
                if (consentDenied) {
                    Text(stringResource(R.string.consent_denied), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.secure_content_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.disconnect))
                }
            }
            MirrorUi.Starting -> ConnectingRow(stringResource(R.string.mirroring_starting))
            is MirrorUi.Live -> {
                Text(
                    stringResource(R.string.mirroring_live, m.captureSize.toString(), m.fps),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.mirroring_connection, m.connection), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.secure_content_note), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onStopMirroring, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stop_mirroring))
                }
            }
        }
    }
}

/** Whole-screen capture. On API 34+ the picker is limited to the full display, since a mirror shows everything. */
private fun screenCaptureIntent(context: Context): Intent {
    val mpm = context.getSystemService(MediaProjectionManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        mpm.createScreenCaptureIntent()
    }
}

@Composable
private fun PairingCodeDialog(receiverName: String, wrong: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val valid = code.length == MirrorConstants.PAIRING_CODE_LENGTH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_title, receiverName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (wrong) R.string.pair_wrong else R.string.pair_explain))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(MirrorConstants.PAIRING_CODE_LENGTH) },
                    label = { Text(stringResource(R.string.pairing_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = valid) { Text(stringResource(R.string.pair)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ManualAddressDialog(initialAddress: String, onConnect: (String, String?) -> Boolean, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(initialAddress) }
    var code by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.manual_explain))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; invalid = false },
                    label = { Text(stringResource(R.string.address_hint)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = { if (invalid) Text(stringResource(R.string.address_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(MirrorConstants.PAIRING_CODE_LENGTH) },
                    label = { Text(stringResource(R.string.pairing_code_optional)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onConnect(address, code.ifBlank { null })) onDismiss() else invalid = true
            }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
