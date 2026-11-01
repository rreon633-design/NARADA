package com.narada.android.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.narada.android.R
import com.narada.android.ui.*
import com.narada.connection.*

data class ConnectionActions(
    val available: Boolean = false, val initialize: () -> Unit = {}, val enable: (Boolean) -> Unit = {},
    val acceptCode: (String) -> Unit = {}, val discardCode: () -> Unit = {}, val request: (Boolean) -> Unit = {},
    val scanQr: () -> Unit = {}, val importQr: () -> Unit = {}, val wifiSettings: () -> Unit = {},
    val finish: (String, Boolean) -> Unit = { _, _ -> }, val recover: (String) -> Unit = {},
    val reconnect: (String, String) -> Unit = { _, _ -> }, val revoke: (String) -> Unit = {},
    val discover: () -> Unit = {}, val stopDiscovery: () -> Unit = {}, val reload: () -> Unit = {},
)
@Composable
fun ConnectionDevicesScreen(name: String, state: ConnectionsUiState, discovery: DiscoveryState, actions: ConnectionActions) {
    var code by remember { mutableStateOf("") } // Never rememberSaveable: invitation is a short-lived secret.
    var compareHost by remember { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val doc = state.document
    val pending = doc?.peers?.firstOrNull { it.status !in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }
    val enabled = actions.available && !state.busy && !state.loading
    LaunchedEffect(state.preview?.fingerprint) { compareHost = false }
    LazyColumn(Modifier.fillMaxSize().testTag("screen_devices"), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Eyebrow("THIS DEVICE"); DeviceCard(name) }
        if (state.loading) item { Caption("Reading protected connection state…") }
        if (state.error != null) item { InfoPanel("Connection needs attention", connectionMessage(state.error), R.drawable.ic_shield); TextButton(actions.reload, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reopen without resetting") } }
        if (doc == null) item {
            Panel {
                TileIcon(R.drawable.ic_lock); Text("A private identity.\nOnly for this phone.", style = MaterialTheme.typography.titleLarge)
                Caption("Create a non-exportable P-256 key in AndroidKeyStore. Trust records are sealed with a separate AndroidKeyStore AES-GCM key. Hardware backing depends on the device and is not assumed.")
                PrimaryAction("Prepare protected identity", actions.initialize, Modifier.fillMaxWidth(), enabled = enabled && state.error == null)
                Caption("No network connection or permission prompt is triggered by this step. No replacement key is created if prior identity state is missing.")
            }
        }
        if (doc != null) {
            item { Panel {
                Eyebrow("PHONE FINGERPRINT"); SelectionContainer { Text(doc.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
                Caption(state.protection ?: "AndroidKeyStore protection")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Local connections", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(doc.enabled, actions.enable, modifier = Modifier.semantics { contentDescription = "Local connections" }, enabled = enabled)
                }
                Caption("Wi-Fi/Ethernet, private IPv4, TLS 1.3. One live desktop health session while NARADA is visible; no always-on background service.")
                Caption(if (state.networkAvailable) "Eligible local network available" else "No eligible Wi-Fi/Ethernet route")
                if (!state.networkAvailable) TextButton(actions.wifiSettings, Modifier.heightIn(min = 48.dp)) { Text("Open Wi-Fi settings") }
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
            } }
            if (pending == null && state.preview == null) {
                item { SectionTitle("Pair your computer") }
                item { Panel {
                    Caption("On the desktop, enable its selected private interface and create a fresh invitation. Scan its real QR or import an image. Names are labels, not authentication.")
                    PrimaryAction("Scan desktop QR", actions.scanQr, Modifier.fillMaxWidth(), enabled = enabled)
                    OutlinedButton(actions.importQr, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) { Text("Import QR image") }
                    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                    OutlinedTextField(code, { if (it.toByteArray().size <= Wire.MAX_CODE) code = it }, Modifier.fillMaxWidth(), label = { Text("Or paste the invitation code") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), maxLines = 3)
                    }
                    TextButton({ val input = code; code = ""; actions.acceptCode(input) }, Modifier.heightIn(min = 48.dp), enabled = enabled && code.isNotBlank()) { Text("Review code privately") }
                    Caption("The invitation is held only in memory. It is not saved in navigation state, settings, logs or backups.")
                } }
            }
            state.preview?.let { preview -> item {
                Panel {
                    Eyebrow("REVIEW THE INVITATION"); Text(preview.name, style = MaterialTheme.typography.titleLarge)
                    Caption("Unverified label until the pinned TLS handshake completes. The token will not be sent to a different identity.")
                    SelectionContainer { Text(preview.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
                    Caption("Private endpoint: ${preview.endpoint}")
                    Row { Checkbox(compareHost, { compareHost = it }, Modifier.semantics { contentDescription = "Confirm the full desktop fingerprint comparison" }); Text("I am scanning the intended computer and compared its full fingerprint.", Modifier.padding(top = 12.dp)) }
                    PrimaryAction("Request pairing", { actions.request(compareHost) }, Modifier.fillMaxWidth(), enabled = enabled && doc.enabled && state.networkAvailable && compareHost)
                    TextButton(actions.discardCode, Modifier.heightIn(min = 48.dp)) { Text("Discard invitation") }
                }
            } }
            if (pending != null) item {
                Panel {
                    Eyebrow("PAIRING · NOT CONNECTED")
                    Text(pending.name, style = MaterialTheme.typography.titleLarge)
                    Caption(when (pending.status) {
                        TrustStatus.CLAIMING -> "The one-use claim outcome is unfinished. It is never replayed after interruption. Cancel here and request a fresh invitation."
                        TrustStatus.WAITING -> "Claim recorded, not yet paired. Check approval to complete or retry private-key proof. Compare this phone’s fingerprint and explicitly approve its grants on the desktop."
                        TrustStatus.APPROVED -> "The desktop approved this attempt. Review the granted capabilities and compare fingerprints before final acknowledgement."
                        else -> "The final acknowledgement may already be committed on the desktop. Recover it using this same pinned attempt; do not create another identity."
                    })
                    PermissionSummary(pending.remotePermissions)
                    var compared by remember(pending.attemptId) { mutableStateOf(false) }
                    if (pending.status == TrustStatus.APPROVED) {
                        Row { Checkbox(compared, { compared = it }, Modifier.semantics { contentDescription = "Confirm this phone’s full fingerprint comparison" }); Text("I compared this phone’s full fingerprint on the desktop.", Modifier.padding(top = 12.dp)) }
                        PrimaryAction("Finish approved pairing", { actions.finish(pending.id, compared) }, Modifier.fillMaxWidth(), enabled = enabled && compared && doc.enabled && state.networkAvailable)
                    }
                    if (pending.status in setOf(TrustStatus.WAITING, TrustStatus.APPROVED, TrustStatus.ACK_PENDING)) OutlinedButton({ actions.recover(pending.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && doc.enabled && state.networkAvailable) { Text(if (pending.status == TrustStatus.ACK_PENDING) "Recover acknowledgement" else "Check approval again") }
                    TextButton({ confirmRemoval = pending.id }, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Cancel this attempt") }
                }
            }
            item { SectionTitle("Saved devices") }
            if (doc.peers.none { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }) item { Caption("No completed pairing yet. A scanned code or discovery hint is not a trusted device.") }
            items(doc.peers.filter { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }, key = { it.id }) { peer ->
                var expanded by rememberSaveable(peer.id) { mutableStateOf(false) }
                var endpoint by rememberSaveable(peer.id, peer.endpoint) { mutableStateOf(peer.endpoint) }
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Glyph(R.drawable.ic_devices)
                    }
                    Text(if (peer.status == TrustStatus.REVOKED) "Locally revoked" else if (state.connectedPeer == peer.id) "Connected · authenticated health" else "Trust saved · not connected", style = MaterialTheme.typography.bodyMedium)
                    Caption("${peer.platform} · ${peer.endpoint}")
                    TextButton({ expanded = !expanded }, Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide identity and grants" else "Review identity and grants") }
                    if (expanded) {
                        SelectionContainer { Text(peer.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
                        PermissionSummary(peer.remotePermissions)
                        Caption("Desktop authorization revision ${peer.remoteRevision}, session epoch ${peer.remoteEpoch}. Android offers no inbound content service in this phase.")
                    }
                    if (peer.status == TrustStatus.ACTIVE) {
                        OutlinedTextField(endpoint, { if (it.length <= 80) endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Private IPv4 address:port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                        Caption("Editing an endpoint never changes the saved certificate or public-key pin. Discovery suggestions are untrusted.")
                        PrimaryAction("Reconnect to saved identity", { actions.reconnect(peer.id, endpoint) }, Modifier.fillMaxWidth(), enabled = enabled && doc.enabled && state.networkAvailable)
                        TextButton({ confirmRemoval = peer.id }, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Revoke on this phone") }
                    } else Caption("To pair again, revoke the old phone record on the desktop and use a new, explicitly approved invitation. No automatic re-pair occurs.")
                }
            }
            item { Panel {
                Text("Nearby hints", style = MaterialTheme.typography.titleLarge)
                Caption("NSD can suggest private endpoints, not identities. Compare a saved device’s fingerprint and explicitly edit its endpoint. Scanning lasts 20 seconds; hints expire.")
                if (discovery.scanning) OutlinedButton(actions.stopDiscovery, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop discovery") }
                else OutlinedButton(actions.discover, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && doc.enabled && state.networkAvailable) { Text("Look on this local network") }
                if (discovery.error) Caption("Discovery unavailable. QR pairing and a saved manual endpoint remain available.")
                discovery.hints.forEach { hint -> SelectionContainer { Text("Untrusted hint · ${hint.endpoint}", style = MaterialTheme.typography.bodyMedium) } }
                if (!discovery.scanning && discovery.hints.isEmpty() && !discovery.error) Caption("No current discovery results.")
            } }
        }
        item { InfoPanel("Grants stay separate", "Files can browse currently granted desktop roots and perform explicitly confirmed managed actions. Pairing alone grants no root access. Transfers, chat, attachments and synchronization remain later phases.") }
    }
    confirmRemoval?.let { id ->
        AlertDialog(onDismissRequest = { confirmRemoval = null }, title = { Text("Stop trusting this device here?") }, text = { Text("The local revocation fence is saved before connections close. This does not erase the desktop’s trust record or any files. Also revoke/remove the phone on the desktop when required.") }, confirmButton = { TextButton({ confirmRemoval = null; actions.revoke(id) }) { Text("Revoke locally") } }, dismissButton = { TextButton({ confirmRemoval = null }) { Text("Keep") } })
    }
}
@Composable private fun PermissionSummary(permissions: Permissions) {
    Caption("Desktop grants: chat ${if (permissions.chat) "allowed" else "off"}; attachments ${if (permissions.attachment) "allowed" else "off"}; ${permissions.roots.size} scoped roots. These features are not implemented on Android yet.")
    permissions.roots.forEach { root -> Caption("Scope ${root.root_id}\n${root.capabilities.joinToString(", ")}") }
}
