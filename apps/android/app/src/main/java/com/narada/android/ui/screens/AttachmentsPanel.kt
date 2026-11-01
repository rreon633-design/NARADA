package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.narada.android.attachments.*
import com.narada.android.chat.chatAccess
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.transfers.transferMessage
import com.narada.android.ui.*
import com.narada.connection.*

data class AttachmentActions(
    val visible: (Boolean) -> Unit = {}, val select: (String?) -> Unit = {}, val reload: () -> Unit = {},
    val import: (String, Boolean) -> Unit = { _, _ -> }, val receive: (String, Boolean) -> Unit = { _, _ -> },
    val run: (String, Boolean) -> Unit = { _, _ -> }, val stop: () -> Unit = {}, val cancel: (String, Boolean) -> Unit = { _, _ -> },
    val remove: (String, Boolean) -> Unit = { _, _ -> }, val export: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val dismissExport: (String, String, Boolean) -> Unit = { _, _, _ -> }, val forgetExport: (String, String, Boolean) -> Unit = { _, _, _ -> },
)
private data class AttachmentReview(val kind: String, val id: String? = null, val exportId: String? = null)
@Composable fun AttachmentsPanel(state: AttachmentsUiState, connections: ConnectionsUiState, actions: AttachmentActions, peerId: String?, caption: String, canSend: Boolean) {
    val safe = state.visible && connections.foreground && state.peerId == peerId && state.access != null && state.access == chatAccess(connections, peerId)
    key(safe, state.viewEpoch, peerId) {
        var review by remember { mutableStateOf<AttachmentReview?>(null) }
        var exportName by remember { mutableStateOf("") }
        val busy = state.busy || state.providerBusy || state.picker != null
        OutlinedCard(Modifier.fillMaxWidth().testTag("attachment_panel")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Encrypted attachments", style = MaterialTheme.typography.titleMedium)
            Caption("Up to 8 MiB per file. A fresh age key stays inside the encrypted chat message. No automatic opening, executable preview or background transfer.")
            if (!safe) OutlinedButton(actions.reload, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = connections.foreground && !busy) { Text("Load private attachment queue") }
            state.error?.let { InfoPanel("Attachment needs attention", attachmentMessage(it)) }
            if (safe) {
                val peer = connections.document?.peers?.find { it.id == peerId }
                Button({ review = AttachmentReview("import") }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSend && !busy && peer?.remotePermissions?.attachment == true && caption.toByteArray().size <= 1024) { Text("Choose an encrypted attachment") }
                Caption("The composer becomes its caption (up to 1024 UTF-8 bytes). First sync this conversation to deliver the protected manifest, then send the encrypted bytes below. Sending requires the desktop upload extension.")
                if (busy) OutlinedButton(actions.stop, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop attachment work") }
                state.rows.forEach { row ->
                    HorizontalDivider()
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    Caption((if (row.outgoing) "To desktop" else "From desktop") + " · " + (row.size?.let { "$it bytes" } ?: "no input read"))
                    Caption(when {
                        row.state == AttachmentState.CORRUPT -> "Local verification failed · not available"
                        row.state == AttachmentState.CANCELLED -> "Stopped locally · remote copies cannot be recalled"
                        row.remoteStored -> "Desktop full verification confirmed · private copy retained"
                        row.available -> "Private copy fully verified" + if (row.outgoing && !row.messageDelivered) " · sync the chat manifest next" else ""
                        else -> "${row.state.name.lowercase().replace('_', ' ')} · ${row.receivedChunks}/${row.totalChunks} ciphertext fragments · not yet available"
                    })
                    if (row.outgoing && !row.remoteStored) Caption("Desktop checkpoint replies: ${row.uploadedChunks}/${row.totalChunks}. This is not a final verification receipt.")
                    if (row.oldPairing) Caption("Earlier pairing · retained locally, never replayed under new authority")
                    row.error?.let { Caption(attachmentMessage(it)) }
                    if (row.state in setOf(AttachmentState.PAUSED, AttachmentState.VERIFIED) && !row.remoteStored) OutlinedButton({ review = AttachmentReview("run", row.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && !row.oldPairing && (!row.outgoing || row.messageDelivered) && (canSend || row.available && !row.outgoing)) {
                        Text(if (row.outgoing) "Review & send encrypted bytes" else "Review & resume received copy")
                    }
                    if (row.available) OutlinedButton({ exportName = row.name.takeIf { runCatching { FileRules.newName(it) }.isSuccess } ?: "attachment.bin"; review = AttachmentReview("export", row.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && row.exports.size < 2 && row.exports.none { it.state in TransferExport.inFlight || it.state == ExportState.UNCERTAIN }) { Text("Export an inert new document") }
                    if (row.state != AttachmentState.CANCELLED) TextButton({ review = AttachmentReview("cancel", row.id) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Stop this attachment locally") }
                    else TextButton({ review = AttachmentReview("remove", row.id) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Remove this private copy") }
                    row.exports.forEach { e ->
                        Caption("Export ${e.name}: ${e.state.name.lowercase().replace('_', ' ')}")
                        if (e.state == ExportState.UNCERTAIN) TextButton({ review = AttachmentReview("dismiss", row.id, e.id) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Review uncertain external document") }
                        if (e.state in setOf(ExportState.VERIFIED, ExportState.NOT_CREATED, ExportState.DISMISSED_UNCONFIRMED)) TextButton({ review = AttachmentReview("forget", row.id, e.id) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Forget this export receipt only") }
                    }
                }
            }
        } }
        val request = review
        if (request != null && safe) TransferConsentDialog(
            title = when (request.kind) { "import" -> "Read one selected file?"; "export" -> "Create a new inert document?"; "remove" -> "Remove retained ciphertext?"; else -> "Review attachment action" },
            originalName = if (request.kind == "export") exportName else state.rows.find { it.id == request.id }?.name ?: "One selected file",
            body = when (request.kind) {
                "import" -> "Only a temporary read grant is used. At most 8 MiB is read into bounded memory and encrypted with a fresh key before queuing. Interrupted imports never reconstruct encryption state. No file is sent before the protected chat manifest is committed."
                "run" -> "Resume only this exact immutable ciphertext and original pairing. A lost upload reply is reconciled with the desktop's durable bitmap. Checkpoints do not mean full verification."
                "export" -> "All age records and both full hashes are checked before a provider document is created. Export is application/octet-stream, never opened automatically. A provider can leave a partial or uncertain document; NARADA cannot promise atomic publication or recall it."
                "remove" -> "Delete only this explicitly stopped private copy. The encrypted chat history remains. Remote or exported copies cannot be erased; uncertain exports must be reviewed first."
                "dismiss" -> "The external document may exist. Dismissal does not mean it was not created, and does not delete it. A future export needs a fresh review and new name."
                "forget" -> "Remove only the local export receipt. No external document is changed or deleted."
                else -> "Stop future local attachment work. The message or bytes may already be committed remotely. No remote deletion, key reset or re-encryption is attempted."
            },
            editName = request.kind == "export", consent = "I understand and want to continue.", button = "I reviewed · continue", enabled = !busy, cancel = { review = null },
        ) { name ->
            when (request.kind) {
                "import" -> actions.import(caption, true)
                "run" -> request.id?.let { actions.run(it, true) }
                "cancel" -> request.id?.let { actions.cancel(it, true) }
                "remove" -> request.id?.let { actions.remove(it, true) }
                "export" -> request.id?.let { actions.export(it, name, true) }
                "dismiss" -> request.id?.let { id -> request.exportId?.let { actions.dismissExport(id, it, true) } }
                "forget" -> request.id?.let { id -> request.exportId?.let { actions.forgetExport(id, it, true) } }
            }; review = null
        }
    }
}
fun attachmentMessage(code: String): String = when (code) {
    "ATTACHMENT_MESSAGE_PENDING" -> "The private ciphertext is retained. Sync this conversation until the desktop commits the encrypted manifest, then send the bytes."
    "ATTACHMENT_UPLOAD_UNSUPPORTED" -> "Sending from this outbound-only phone requires the versioned desktop upload extension. No phone listener or insecure fallback is offered."
    "ATTACHMENTS_UNSUPPORTED", "ACCESS_DENIED" -> "The same approved desktop must currently grant both chat and attachments. Existing verified private copies remain separate."
    "ATTACHMENT_LIMIT", "ATTACHMENT_CAPTION_LIMIT" -> "Use one file of at most 8 MiB and a caption of at most 1024 UTF-8 bytes. No partial plaintext was published."
    "ATTACHMENT_LOCAL_QUOTA", "CONTENT_QUOTA" -> "The bounded private queue or ciphertext quota is full. Nothing was silently evicted. Review and explicitly remove stopped copies."
    "IMPORT_INTERRUPTED", "IMPORT_PICKER_CANCELLED", "RESTART_PAUSED" -> "No automatic import, encryption or transfer resumes. Complete immutable ciphertext can be reverified; a partial import needs a fresh selected file and a new key."
    "AGE_UNAVAILABLE", "AGE_INCOMPATIBLE", "AGE_IDENTITY_INVALID", "AGE_DECRYPT_FAILED", "HASH_MISMATCH" -> "The age adapter, key or complete authenticated contents could not be verified. No plaintext fallback or partial export is allowed."
    "ATTACHMENT_REMOTE_STORAGE", "ATTACHMENT_REMOTE_FULL" -> "The desktop could not retain its protected ciphertext. This does not reset the phone's keys or delete the phone's saved copy."
    "ATTACHMENT_STOPPED", "ATTACHMENT_INTERRUPTED", "CHAT_SCOPE_CHANGED", "CHAT_ACCESS_CHANGED" -> "Visibility or authority changed. Load the private queue and explicitly review a retry; old pairing data is not resent under new authority."
    "ATTACHMENT_BUSY", "ATTACHMENT_REFERENCED" -> "Stop current work and resolve any uncertain export before changing this retained copy."
    "BLOB_EXPIRED", "BLOB_PAUSED", "BLOB_INCOMPLETE" -> "The desktop no longer has an available complete ciphertext for this request. No replacement attachment was inferred."
    else -> transferMessage(code)
}
