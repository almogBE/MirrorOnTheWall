package com.mirroronthewall.sender.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.sender.R
import com.mirroronthewall.sender.SenderViewModel
import com.mirroronthewall.sender.SenderViewModel.ConnectionUi
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
            is ConnectionUi.Connected -> ConnectedScreen(c, onDisconnect = vm::disconnect)
            is ConnectionUi.Connecting -> ConnectingRow(c.target.name)
            else -> ReceiverList(
                discovery = discovery,
                onPick = vm::connect,
                onManual = { showManual = true },
                error = (c as? ConnectionUi.Error)?.message,
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
    error: String?,
    onDismissError: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.choose_tv), style = MaterialTheme.typography.titleMedium)

        if (error != null) {
            Card(Modifier.fillMaxWidth().clickable(onClick = onDismissError)) {
                Text(
                    error,
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
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
private fun ConnectingRow(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.connecting_to, name))
    }
}

@Composable
private fun ConnectedScreen(c: ConnectionUi.Connected, onDisconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.connected_to, c.receiverName), style = MaterialTheme.typography.titleLarge)
        if (c.newlyPaired) Text(stringResource(R.string.paired_note), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.tv_address, c.target.endpoint.toString()), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.tv_display, c.tvDisplay), style = MaterialTheme.typography.bodyMedium)
        Text(
            c.signalingRttMs?.let { stringResource(R.string.signaling_rtt, "%.1f".format(it)) }
                ?: stringResource(R.string.signaling_rtt_pending),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.phase1_no_media), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disconnect))
        }
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
