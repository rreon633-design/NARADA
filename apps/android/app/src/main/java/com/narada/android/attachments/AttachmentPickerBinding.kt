package com.narada.android.attachments

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

/** Non-secret per-intent registry key. Rotation may rebind a live ticket; process restoration never
 * invents one or binds a stale result to another file/message. No persisted provider grant. */
class AttachmentPickerBinding(
    registry: ActivityResultRegistry, private val ticket: AttachmentPicker,
    private val claim: (String) -> Boolean, private val result: (String, String?, Int) -> Unit,
) : AutoCloseable {
    private val launcher = registry.register("narada.attachment.${if (ticket.exportId == null) "import" else "export"}.${ticket.ticketId}", ActivityResultContracts.StartActivityForResult()) { reply ->
        result(ticket.ticketId, if (reply.resultCode == Activity.RESULT_OK) reply.data?.data?.toString() else null, reply.data?.flags ?: 0)
    }
    fun launch() {
        if (!claim(ticket.ticketId)) return
        val intent = if (ticket.exportId == null) Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { launcher.launch(intent) } catch (_: Exception) { result(ticket.ticketId, null, 0) }
    }
    override fun close() = launcher.unregister()
}
