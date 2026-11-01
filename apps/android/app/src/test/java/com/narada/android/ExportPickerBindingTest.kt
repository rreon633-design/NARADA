package com.narada.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.narada.android.transfers.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ExportPickerBindingTest {
    private class Registry:ActivityResultRegistry(){val codes=mutableListOf<Int>();val intents=mutableListOf<Intent>();var fail=false;override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){if(fail)throw android.content.ActivityNotFoundException();codes+=requestCode;intents+=input as Intent}}
    private fun ticket()=ExportPicker(UUID.randomUUID().toString(),UUID.randomUUID().toString())
    private fun reply()=Intent().setData(Uri.parse("content://fixture.documents/tree/root")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    @Test fun pickerRequestsTemporaryReadWriteTreeAndPreservesResultFlags(){val registry=Registry();val ticket=ticket();var claimed=false;val seen=mutableListOf<Triple<String,String?,Int>>();val binding=ExportPickerBinding(registry,ticket,{if(claimed)false else {claimed=true;true}},{id,uri,flags->seen+=Triple(id,uri,flags)});binding.launch();binding.launch();assertEquals(1,registry.codes.size);val sent=registry.intents.single();assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,sent.action);assertEquals(3,sent.flags);assertEquals(0,sent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);registry.dispatchResult(registry.codes.single(),Activity.RESULT_OK,reply());assertEquals(Triple(ticket.exportId,reply().data.toString(),3),seen.single());binding.close()}
    @Test fun rotationRebindsLiveTicketWithoutLaunchingAgain(){val first=Registry();val ticket=ticket();val binding=ExportPickerBinding(first,ticket,{true},{_,_,_->org.junit.Assert.fail("Old activity must not receive")});binding.launch();val code=first.codes.single();val saved=Bundle();first.onSaveInstanceState(saved);binding.close();val replacement=Registry();replacement.onRestoreInstanceState(saved);val seen=mutableListOf<String>();val reopened=ExportPickerBinding(replacement,ticket,{false},{id,_,_->seen+=id});reopened.launch();assertTrue(replacement.codes.isEmpty());replacement.dispatchResult(code,Activity.RESULT_OK,reply());assertEquals(listOf(ticket.exportId),seen);reopened.close()}
    @Test fun processRestoredOldReplyCannotBindToNewlyConsentedExport(){val first=Registry();val old=ticket();val binding=ExportPickerBinding(first,old,{true},{_,_,_->});binding.launch();val oldCode=first.codes.single();val saved=Bundle();first.onSaveInstanceState(saved);binding.close();val restored=Registry();restored.onRestoreInstanceState(saved);val fresh=ticket();val seen=mutableListOf<String>();val next=ExportPickerBinding(restored,fresh,{true},{id,_,_->seen+=id});next.launch();val newCode=restored.codes.single();assertNotEquals(oldCode,newCode);restored.dispatchResult(oldCode,Activity.RESULT_OK,reply());assertTrue(seen.isEmpty());restored.dispatchResult(newCode,Activity.RESULT_OK,reply());assertEquals(listOf(fresh.exportId),seen);next.close()}
    @Test fun missingPickerIsReportedAsCancelledForThatExactTicket(){val registry=Registry().apply{fail=true};val ticket=ticket();val seen=mutableListOf<Triple<String,String?,Int>>();ExportPickerBinding(registry,ticket,{true},{id,uri,flags->seen+=Triple(id,uri,flags)}).use{it.launch()};assertEquals(Triple(ticket.exportId,null,0),seen.single())}
}
