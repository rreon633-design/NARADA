package com.narada.android.transfers

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

/** Separate registry key per live ticket. A result restored by Android after process death cannot
 * be rebound to a newly consented export. Rotation registers the SAME still-live in-memory ticket
 * but claim() prevents relaunch; the app never reconstructs authority from registry saved state. */
class ExportPickerBinding(
    registry: ActivityResultRegistry, private val ticket: ExportPicker,
    private val claim: (String) -> Boolean, private val result: (String, String?, Int) -> Unit,
) : AutoCloseable {
    private val launcher = registry.register("narada.export.${ticket.exportId}", ActivityResultContracts.StartActivityForResult()) { reply ->
        val data = reply.data
        result(ticket.exportId, if (reply.resultCode == Activity.RESULT_OK) data?.data?.toString() else null, data?.flags ?: 0)
    }
    fun launch() {
        if (!claim(ticket.exportId)) return
        try { launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) }
        catch (_: Exception) { result(ticket.exportId, null, 0) }
    }
    override fun close() = launcher.unregister()
}
