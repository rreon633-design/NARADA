package com.narada.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.narada.android.attachments.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AttachmentPickerBindingTest {
    private class Registry : ActivityResultRegistry() { val codes = mutableListOf<Int>(); val intents = mutableListOf<Intent>(); var fail = false; override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { if (fail) throw android.content.ActivityNotFoundException(); codes += requestCode; intents += input as Intent } }
    private fun ticket(export: Boolean = false) = AttachmentPicker(UUID.randomUUID().toString(), if (export) UUID.randomUUID().toString() else null)
    private fun reply() = Intent().setData(Uri.parse("content://fixture.documents/document/source")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    @Test fun importRequestsOpenableDocumentWithTemporaryReadOnlyAuthority() { val r = Registry(); val t = ticket(); var claimed = false; val seen = mutableListOf<Triple<String, String?, Int>>(); AttachmentPickerBinding(r, t, { if (claimed) false else { claimed = true; true } }, { id, uri, flags -> seen += Triple(id, uri, flags) }).use { it.launch(); it.launch(); assertEquals(1, r.codes.size); val sent = r.intents.single(); assertEquals(Intent.ACTION_OPEN_DOCUMENT, sent.action); assertEquals(setOf(Intent.CATEGORY_OPENABLE), sent.categories); assertEquals("*/*", sent.type); assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, sent.flags); r.dispatchResult(r.codes.single(), Activity.RESULT_OK, reply()); assertEquals(Triple(t.ticketId, reply().data.toString(), 1), seen.single()) } }
    @Test fun exportRequestsTemporaryTreeReadWriteNotPersistableAuthority() { val r = Registry(); AttachmentPickerBinding(r, ticket(true), { true }, { _, _, _ -> }).use { it.launch(); val sent = r.intents.single(); assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, sent.action); assertEquals(3, sent.flags); assertEquals(0, sent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) } }
    @Test fun rotationRebindsSameTicketWithoutAnotherLaunchOrGrant() { val r = Registry(); val t = ticket(); val first = AttachmentPickerBinding(r, t, { true }, { _, _, _ -> org.junit.Assert.fail("Old activity") }); first.launch(); val code = r.codes.single(); val saved = Bundle(); r.onSaveInstanceState(saved); first.close(); val next = Registry(); next.onRestoreInstanceState(saved); val seen = mutableListOf<String>(); AttachmentPickerBinding(next, t, { false }, { id, _, _ -> seen += id }).use { it.launch(); assertTrue(next.codes.isEmpty()); next.dispatchResult(code, Activity.RESULT_OK, reply()); assertEquals(listOf(t.ticketId), seen) } }
    @Test fun oldProcessResultCannotBecomeANewlyReviewedImport() { val r = Registry(); val old = ticket(); val first = AttachmentPickerBinding(r, old, { true }, { _, _, _ -> }); first.launch(); val code = r.codes.single(); val saved = Bundle(); r.onSaveInstanceState(saved); first.close(); val next = Registry(); next.onRestoreInstanceState(saved); val fresh = ticket(); val seen = mutableListOf<String>(); AttachmentPickerBinding(next, fresh, { true }, { id, _, _ -> seen += id }).use { it.launch(); next.dispatchResult(code, Activity.RESULT_OK, reply()); assertTrue(seen.isEmpty()); next.dispatchResult(next.codes.single(), Activity.RESULT_OK, reply()); assertEquals(listOf(fresh.ticketId), seen) } }
    @Test fun unavailablePickerCancelsOnlyItsExactDurableIntent() { val r = Registry().apply { fail = true }; val t = ticket(); val seen = mutableListOf<Triple<String, String?, Int>>(); AttachmentPickerBinding(r, t, { true }, { id, uri, flags -> seen += Triple(id, uri, flags) }).use { it.launch() }; assertEquals(Triple(t.ticketId, null, 0), seen.single()) }
}
