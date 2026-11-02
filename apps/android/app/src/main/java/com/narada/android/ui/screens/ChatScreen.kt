package com.narada.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.narada.android.chat.*
import com.narada.android.attachments.AttachmentsUiState
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.ui.*
import com.narada.connection.*
import java.util.UUID

data class ChatActions(
    val visible: (Boolean) -> Unit = {}, val reload: () -> Unit = {}, val select: (String) -> Unit = {},
    val enable: (Boolean) -> Unit = {}, val saveDraft: (String) -> Unit = {}, val send: (String, String) -> Unit = { _, _ -> },
    val sync: () -> Unit = {}, val stop: () -> Unit = {}, val retry: (String, Boolean) -> Unit = { _, _ -> },
    val cancel: (String, Boolean) -> Unit = { _, _ -> }, val markRead: () -> Unit = {},
    val liveUpdates: (Boolean) -> Unit = {}, val receive: () -> Unit = {},
)
private data class ChatReview(val kind: String, val id: String? = null)
@Composable
fun ChatScreen(state: ChatUiState, connections: ConnectionsUiState, actions: ChatActions, openConnections: () -> Unit) = ChatScreen(state, connections, actions, AttachmentsUiState(), AttachmentActions(), openConnections)
@Composable
fun ChatScreen(state: ChatUiState, connections: ConnectionsUiState, actions: ChatActions, attachments: AttachmentsUiState, attachmentActions: AttachmentActions, openConnections: () -> Unit) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        DisposableEffect(Unit) { actions.visible(true); attachmentActions.visible(true); onDispose { actions.visible(false); attachmentActions.visible(false) } }
        LaunchedEffect(state.peerId) { attachmentActions.select(state.peerId) }
        // A stale private frame is masked even before the coordinator's flow collector clears it.
        val safe = state.visible && connections.foreground && state.access != null && state.access == chatAccess(connections, state.peerId)
        key(safe, state.viewEpoch, state.peerId) { ChatContent(state, safe, actions, attachments, attachmentActions, connections, openConnections) }
    }
}
@Composable
private fun ChatContent(state: ChatUiState, safe: Boolean, actions: ChatActions, attachments: AttachmentsUiState, attachmentActions: AttachmentActions, connections: ConnectionsUiState, openConnections: () -> Unit) {
    var review by remember { mutableStateOf<ChatReview?>(null) }
    var peerMenu by remember { mutableStateOf(false) }
    var draft by remember(state.composerEpoch, state.draft) { mutableStateOf(if (safe) state.draft else "") }
    var intentId by remember(state.composerEpoch) { mutableStateOf(UUID.randomUUID().toString()) }
    val canSend = safe && state.enabled && !state.renewal && state.networkEligible && !state.busy && !attachments.busy && !attachments.providerBusy
    val textValid = draft.isNotBlank() && runCatching { ChatRules.text(draft) }.isSuccess
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("protected_chat"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("ENCRYPTED MESSAGES")
            Text("A private\nconversation.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Caption("Chat with an approved computer. No folder needed.")
        } }
        if (!safe) item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoPanel("Private view is closed", "Leaving Chat or losing access clears unsaved typing. Load saved history to continue.")
            OutlinedButton(actions.reload, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.foreground) { Text("Load protected history") }
            TextButton(openConnections, Modifier.heightIn(min = 48.dp)) { Text("Open Connections") }
        } }
        state.error?.let { code -> item { InfoPanel("Needs attention", chatMessage(code)) } }
        if (safe && state.peers.isEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoPanel("No approved conversation yet", "Pair your computer, compare fingerprints and allow chat in its permissions.")
            OutlinedButton(openConnections, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Connections") }
        } }
        if (safe && state.peerId != null) {
            item { Box {
                OutlinedButton({ peerMenu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) {
                    Text(state.peers.find { it.id == state.peerId }?.name ?: "Saved desktop")
                }
                DropdownMenu(peerMenu, { peerMenu = false }) { state.peers.forEach { peer ->
                    DropdownMenuItem(text = { Text(peer.name + if (!peer.active) " · revoked / saved history" else "") }, onClick = { peerMenu = false; actions.select(peer.id) }, modifier = Modifier.heightIn(min = 48.dp))
                } }
            } }
            if (!state.enabled || state.renewal) item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoPanel(if (state.renewal) "Review renewed pairing" else "Enable this private conversation", "Enable encrypted chat with this computer. Existing history is kept.")
                Button({ review = ChatReview("enable") }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.networkEligible && !state.busy && !attachments.busy && !attachments.providerBusy) { Text(if (state.renewal) "Review renewed authority" else "Review & enable private chat") }
            } }
            if (state.enabled) {
                item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.receiving) "Checking incoming messages" else if (state.busy) "Chat action in progress" else if (state.networkEligible) "Ready to send or check for messages" else "Saved history · offline or not authorized", style = MaterialTheme.typography.titleSmall)
                    Caption("${state.retained} / 32 saved messages · 4 conversations max. Nothing is auto-deleted.")
                    if (state.busy) OutlinedButton(actions.stop, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop this batch") }
                    else {
                        Button(actions.receive, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSend) { Text("Check messages") }
                        if (state.rows.any { it.outgoing && !it.oldPairing && it.status in setOf(ChatStatus.QUEUED, ChatStatus.READY, ChatStatus.UNCERTAIN) }) {
                            OutlinedButton(actions.sync, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSend) { Text("Send queued & retry pending") }
                        }
                    }
                    Caption("Check messages never sends. Delivered means saved on the other device, not read.")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Live updates while this chat is open", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(state.liveUpdates, actions.liveUpdates, enabled = state.liveUpdates || (canSend && !state.renewal), modifier = Modifier.testTag("chat_live_updates").semantics { contentDescription = "Live updates while this chat is open" })
                    }
                    Caption(if (state.liveUpdates) "Checks every 5 seconds when idle; never sends pending messages." else "Receive here without sending pending messages. Leaving Chat turns this off.")
                } }
                item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(draft, { value -> if (runCatching { ChatRules.text(value, true) }.isSuccess) { draft = value; intentId = UUID.randomUUID().toString() } },
                        Modifier.fillMaxWidth().testTag("chat_composer"), label = { Text("Private text") }, minLines = 3, maxLines = 7,
                        enabled = (!state.busy || state.receiving) && !state.renewal, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        supportingText = { Text("${draft.toByteArray(Charsets.UTF_8).size} / 4096 UTF-8 bytes · attachment caption ≤ 1024") })
                    Button({ actions.send(intentId, draft) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSend && textValid) { Text("Send") }
                    OutlinedButton({ actions.saveDraft(draft) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy && !state.renewal) { Text("Save encrypted draft") }
                    Caption("Save a draft to keep it. Unsaved typing is cleared when you leave.")
                } }
                item { AttachmentsPanel(attachments, connections, attachmentActions, state.peerId, draft, canSend) }
                if (state.rows.isEmpty()) item { InfoPanel("No saved messages", "Send a message or check for new messages.") }
                if (state.rows.any { it.unread }) item { TextButton(actions.markRead, Modifier.heightIn(min = 48.dp), enabled = !state.busy) { Text("Mark viewed on this phone") } }
                items(state.rows, key = { it.id }) { row ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (row.outgoing) Alignment.End else Alignment.Start) {
                        OutlinedCard(Modifier.fillMaxWidth(.96f).testTag("chat_message_${row.id}")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Eyebrow(if (row.outgoing) "YOU" else "DESKTOP")
                            Text(row.text, style = MaterialTheme.typography.bodyLarge)
                            Caption(chatStatus(row))
                            row.attachmentName?.let { name ->
                                Caption("Attachment: $name · ${row.attachmentSize ?: 0} bytes · message receipt is not file availability")
                                if (!row.outgoing) OutlinedButton({ attachmentActions.receive(row.id, true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSend && !row.oldPairing) { Text("Download / resume encrypted attachment") }
                            }
                            if (row.oldPairing) Caption("Retained from an earlier pairing · no replay under new authority")
                            row.error?.let { Caption(chatMessage(it)) }
                            if (row.outgoing && row.status != ChatStatus.DELIVERED) {
                                OutlinedButton({ review = ChatReview("retry", row.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSend && !row.oldPairing) { Text("Review exact retry") }
                                if (row.status !in setOf(ChatStatus.CANCELLED_NOT_SENT, ChatStatus.CANCELLED_UNCONFIRMED)) TextButton({ review = ChatReview("cancel", row.id) }, Modifier.heightIn(min = 48.dp), enabled = !state.busy) { Text("Stop retrying this text") }
                            }
                        } }
                    }
                }
            }
        }
        item { HorizontalDivider(); Spacer(Modifier.height(12.dp)); Caption("Development build · independent security review pending.") }
    }
    review?.let { choice ->
        var confirmed by remember(choice) { mutableStateOf(false) }
        val title = when (choice.kind) { "enable" -> "Enable protected private chat?"; "retry" -> "Allow an exact retry?"; else -> "Stop local retries?" }
        val explanation = when (choice.kind) {
            "enable" -> "Enable encrypted chat without resetting keys or history. Earlier-pairing messages are not resent. Development build: independent security and device validation are pending."
            "retry" -> "Retry the same message ID, text and saved ciphertext. It may already be on the computer. Allows up to three more attempts; sending waits for Send queued & retry pending."
            else -> "Stops future retries only. The computer may already have the message; this does not recall or delete it."
        }
        AlertDialog(onDismissRequest = { review = null }, title = { Text(title) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(explanation)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { confirmed = !confirmed }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it }); Text("I understand and want to continue.", Modifier.weight(1f))
                }
            } },
            confirmButton = { TextButton({ when (choice.kind) { "enable" -> actions.enable(true); "retry" -> actions.retry(choice.id!!, true); else -> actions.cancel(choice.id!!, true) }; review = null }, enabled = confirmed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirm") } },
            dismissButton = { TextButton({ review = null }, Modifier.heightIn(min = 48.dp)) { Text("Keep unchanged") } },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
}
fun chatStatus(row: ChatRow): String = if (!row.outgoing) {
    if (row.acknowledged) "Saved on this phone · ACK confirmed" else "Saved on this phone · desktop ACK pending"
} else when (row.status) {
    ChatStatus.QUEUED -> "Queued privately · not submitted"
    ChatStatus.READY -> "Exact ciphertext saved · ready for submission"
    ChatStatus.UNCERTAIN -> "Delivery unconfirmed · ${row.attempts} / 3 admitted attempts"
    ChatStatus.DELIVERED -> "Desktop delivery receipt saved · not a read receipt"
    ChatStatus.CANCELLED_NOT_SENT -> "Cancelled locally before encryption · not sent"
    ChatStatus.CANCELLED_UNCONFIRMED -> "Retries stopped · previous delivery remains unconfirmed"
}
