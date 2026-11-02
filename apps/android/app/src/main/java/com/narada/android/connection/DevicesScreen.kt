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
    val discover: () -> Unit = {}, val stopDiscovery: () -> Unit = {}, val reload: () -> Unit = {}, val openChat: () -> Unit = {},
    val inputVisible: (Boolean) -> Unit = {},
)
@Composable
fun ConnectionDevicesScreen(name: String, state: ConnectionsUiState, discovery: DiscoveryState, actions: ConnectionActions) {
    DisposableEffect(Unit) { actions.inputVisible(true); onDispose { actions.inputVisible(false) } }
    var code by remember { mutableStateOf("") } // Memory only: an invitation is a short-lived secret.
    var compareHost by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var addComputer by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val doc = state.document
    val pending = doc?.peers?.firstOrNull { it.status !in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }
    val preferred = doc?.peers?.firstOrNull { it.id == doc.preferredPeer && it.status == TrustStatus.ACTIVE }
    val enabled = actions.available && !state.busy && !state.loading
    val canScan = enabled && state.foreground && state.networkAvailable && !state.pairingInProgress && (doc != null || state.error == null)
    val list = rememberLazyListState()
    LaunchedEffect(state.preview?.fingerprint) { compareHost = false }
    LaunchedEffect(doc?.preferredPeer) { addComputer = false }
    LaunchedEffect(state.preview?.fingerprint, pending?.attemptId, pending?.status) {
        if (state.preview != null || pending != null) list.animateScrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("screen_devices"), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SectionTitle("Connect your computer") }
        if (state.loading) item { Caption("Reading protected connection state…") }
        if (state.error != null) item {
            InfoPanel("Connection needs attention", connectionMessage(state.error), R.drawable.ic_shield)
            TextButton(actions.reload, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reopen without resetting") }
        }
        if (state.preview == null && pending == null) {
            when {
                state.pairingInProgress -> item { InfoPanel("Requesting pairing", "Keep NARADA open. Do not scan the code again while this request is in progress.") }
                preferred != null && !addComputer -> item { Panel {
                    Eyebrow(if (state.connectedPeer == preferred.id) "CONNECTED · VERIFIED HEALTH" else "TRUST SAVED · NOT CONNECTED")
                    Text(preferred.name, style = MaterialTheme.typography.titleLarge)
                    Caption(state.status)
                    if (preferred.remotePermissions.chat) PrimaryAction("Open private chat", actions.openChat, Modifier.fillMaxWidth(), enabled = enabled)
                    else Caption("Allow private chat in this phone’s permissions on the computer. No folder is needed.")
                    TextButton({ addComputer = true }, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Connect another computer") }
                } }
                else -> item { Panel {
                    Eyebrow("1 · SCAN")
                    Text("Start with a QR", style = MaterialTheme.typography.titleLarge)
                    Caption("On your computer, open Devices and show a pairing QR.")
                    if (doc?.enabled != true) Caption("Scan or import prepares this phone and enables private local connections. Verification is still required.")
                    PrimaryAction(if (doc == null) "Set up & scan QR" else if (!doc.enabled) "Enable & scan QR" else "Scan desktop QR", actions.scanQr, Modifier.fillMaxWidth(), enabled = canScan)
                    OutlinedButton(actions.importQr, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canScan) { Text("Import QR image") }
                    if (!state.networkAvailable) {
                        Caption("Use the same trusted Wi-Fi/Ethernet network as your computer.")
                        TextButton(actions.wifiSettings, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Open Wi-Fi settings") }
                    }
                    if (preferred != null) TextButton({ addComputer = false }, Modifier.heightIn(min = 48.dp)) { Text("Back to saved computer") }
                } }
            }
        }
        state.preview?.let { preview -> item { Panel {
            Eyebrow("2 · VERIFY THE COMPUTER")
            Text(preview.name, style = MaterialTheme.typography.titleLarge)
            Caption("Compare this full fingerprint on your computer. The invitation is not yet trusted.")
            SelectionContainer { Text(preview.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
            Caption("Private endpoint: ${preview.endpoint}")
            Row {
                Checkbox(compareHost, { compareHost = it }, Modifier.semantics { contentDescription = "Confirm the full desktop fingerprint comparison" })
                Text("I am scanning the intended computer and compared its full fingerprint.", Modifier.padding(top = 12.dp))
            }
            PrimaryAction("Request pairing", { actions.request(compareHost) }, Modifier.fillMaxWidth(), enabled = enabled && !state.pairingInProgress && doc?.enabled == true && state.foreground && state.networkAvailable && compareHost)
            TextButton(actions.discardCode, Modifier.heightIn(min = 48.dp)) { Text("Discard invitation") }
        } } }
        if (pending != null) item { Panel {
            Eyebrow(if (pending.status == TrustStatus.APPROVED) "3 · FINISH ON THIS PHONE" else "PAIRING · NOT CONNECTED")
            Text(pending.name, style = MaterialTheme.typography.titleLarge)
            Caption(when (pending.status) {
                TrustStatus.CLAIMING -> if (state.pairingInProgress) "Request in progress. Keep this app open." else "Pairing was interrupted. Cancel this attempt and request a new invitation."
                TrustStatus.WAITING -> if (state.pairingInProgress) "Checking approval automatically. Compare the phone fingerprint below and approve on your computer." else "Approval checks are paused. Resume them while this app is open."
                TrustStatus.APPROVED -> "Approved on the computer. Review permissions and confirm the phone fingerprint to finish."
                else -> if (state.pairingInProgress) "Completing confirmation with the same saved identity." else "Confirmation is incomplete. Recover this pairing; do not create another identity."
            })
            if (pending.status in setOf(TrustStatus.WAITING, TrustStatus.APPROVED)) {
                Eyebrow("THIS PHONE’S FULL FINGERPRINT")
                SelectionContainer { Text(doc!!.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
            }
            if (state.pairingInProgress) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("pairing_active_work"))
            PermissionSummary(pending.remotePermissions)
            var compared by remember(pending.attemptId) { mutableStateOf(false) }
            if (pending.status == TrustStatus.APPROVED) {
                Row {
                    Checkbox(compared, { compared = it }, Modifier.semantics { contentDescription = "Confirm this phone’s full fingerprint comparison" })
                    Text("I compared this phone’s full fingerprint on the desktop.", Modifier.padding(top = 12.dp))
                }
                PrimaryAction("Finish approved pairing", { actions.finish(pending.id, compared) }, Modifier.fillMaxWidth(), enabled = enabled && !state.pairingInProgress && compared && doc!!.enabled && state.foreground && state.networkAvailable)
            }
            if (!state.pairingInProgress && pending.status in setOf(TrustStatus.WAITING, TrustStatus.ACK_PENDING)) {
                OutlinedButton({ actions.recover(pending.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && doc!!.enabled && state.foreground && state.networkAvailable) {
                    Text(if (pending.status == TrustStatus.ACK_PENDING) "Recover acknowledgement" else "Resume approval checks")
                }
            }
            TextButton({ confirmRemoval = pending.id }, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Cancel this attempt") }
        } }
        item { TextButton({ advanced = !advanced }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (advanced) "Hide advanced setup" else "Advanced setup & saved devices") } }
        if (advanced) {
            item { DeviceCard(name) }
            if (doc == null) item { Panel {
                Text("Prepare without a camera", style = MaterialTheme.typography.titleMedium)
                Caption("Creates a protected identity offline. Missing existing keys are never replaced.")
                PrimaryAction("Prepare protected identity", actions.initialize, Modifier.fillMaxWidth(), enabled = enabled && state.error == null)
            } }
            if (doc != null) {
                item { Panel {
                    Eyebrow("PHONE FINGERPRINT")
                    SelectionContainer { Text(doc.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
                    Caption(state.protection ?: "AndroidKeyStore protection; hardware backing varies by device")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Local connections", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(doc.enabled, actions.enable, modifier = Modifier.semantics { contentDescription = "Local connections" }, enabled = enabled)
                    }
                    Caption("Private Wi-Fi/Ethernet only. Saved devices reconnect while this app is visible; no background presence.")
                    Caption(if (state.networkAvailable) "Eligible local network available" else "No eligible Wi-Fi/Ethernet route")
                    if (!state.networkAvailable) TextButton(actions.wifiSettings, Modifier.heightIn(min = 48.dp)) { Text("Open Wi-Fi settings") }
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                } }
                if (pending == null && state.preview == null && !state.pairingInProgress) item { Panel {
                    Caption("Manual code entry. Enable Local connections above before requesting pairing.")
                    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                        OutlinedTextField(code, { if (it.toByteArray().size <= Wire.MAX_CODE) code = it }, Modifier.fillMaxWidth(), label = { Text("Or paste the invitation code") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), maxLines = 3)
                    }
                    TextButton({ val input = code; code = ""; actions.acceptCode(input) }, Modifier.heightIn(min = 48.dp), enabled = enabled && code.isNotBlank()) { Text("Review code privately") }
                    Caption("Invitation codes stay in memory only.")
                } }
                item { SectionTitle("Saved devices") }
                if (doc.peers.none { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }) item { Caption("Pairing is not complete until both devices confirm.") }
                items(doc.peers.filter { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }, key = { it.id }) { peer ->
                    var expanded by rememberSaveable(peer.id) { mutableStateOf(false) }
                    var endpoint by rememberSaveable(peer.id, peer.endpoint) { mutableStateOf(peer.endpoint) }
                    Panel {
                        Text(peer.name, style = MaterialTheme.typography.titleLarge)
                        Text(if (peer.status == TrustStatus.REVOKED) "Locally revoked" else if (state.connectedPeer == peer.id) "Connected · authenticated health" else "Trust saved · not connected", style = MaterialTheme.typography.bodyMedium)
                        Caption("${peer.platform} · ${peer.endpoint}")
                        TextButton({ expanded = !expanded }, Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide identity and grants" else "Review identity and grants") }
                        if (expanded) {
                            SelectionContainer { Text(peer.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
                            PermissionSummary(peer.remotePermissions)
                        }
                        if (peer.status == TrustStatus.ACTIVE) {
                            if (peer.remotePermissions.chat) OutlinedButton(actions.openChat, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open private chat") }
                            else Caption("Allow chat for this phone in your computer’s Devices settings. No folder needed.")
                            if (expanded) OutlinedTextField(endpoint, { if (it.length <= 80) endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Private IPv4 address:port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                            if (expanded) Caption("Address changes keep the saved identity pin. Discovery results are unverified.")
                            PrimaryAction("Reconnect to saved identity", { actions.reconnect(peer.id, endpoint) }, Modifier.fillMaxWidth(), enabled = enabled && !state.pairingInProgress && doc.enabled && state.foreground && state.networkAvailable)
                            TextButton({ confirmRemoval = peer.id }, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Revoke on this phone") }
                        } else Caption("To pair again, revoke the old phone record on the desktop and use a new, explicitly approved invitation. No automatic re-pair occurs.")
                    }
                }
                item { Panel {
                    Text("Nearby hints", style = MaterialTheme.typography.titleLarge)
                    Caption("Scan for 20 seconds. Addresses are unverified; compare fingerprints before using them.")
                    if (discovery.scanning) OutlinedButton(actions.stopDiscovery, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop discovery") }
                    else OutlinedButton(actions.discover, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && !state.pairingInProgress && doc.enabled && state.foreground && state.networkAvailable) { Text("Look on this local network") }
                    if (discovery.error) Caption("Discovery unavailable. QR pairing and a saved manual endpoint remain available.")
                    discovery.hints.forEach { hint -> SelectionContainer { Text("Untrusted hint · ${hint.endpoint}", style = MaterialTheme.typography.bodyMedium) } }
                    if (!discovery.scanning && discovery.hints.isEmpty() && !discovery.error) Caption("No current discovery results.")
                } }
            }
        }
        item { InfoPanel("Grants stay separate", "Pairing grants no folders. Chat, files and attachments each need permission.") }
    }
    confirmRemoval?.let { id ->
        AlertDialog(onDismissRequest = { confirmRemoval = null }, title = { Text("Stop trusting this device here?") }, text = { Text("The local revocation fence is saved before connections close. This does not erase the desktop’s trust record or any files. Also revoke/remove the phone on the desktop when required.") }, confirmButton = { TextButton({ confirmRemoval = null; actions.revoke(id) }) { Text("Revoke locally") } }, dismissButton = { TextButton({ confirmRemoval = null }) { Text("Keep") } })
    }
}
@Composable private fun PermissionSummary(permissions: Permissions) {
    Caption("Computer permissions: chat ${if (permissions.chat) "allowed" else "off"}; attachments ${if (permissions.attachment) "allowed" else "off"}; ${permissions.roots.size} folders. Enable chat separately.")
    permissions.roots.forEach { root -> Caption("Scope ${root.root_id}\n${root.capabilities.joinToString(", ")}") }
}
