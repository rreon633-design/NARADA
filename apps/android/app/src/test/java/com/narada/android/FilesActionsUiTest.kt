package com.narada.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.ui.NaradaTheme
import com.narada.android.ui.screens.*
import com.narada.connection.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesActionsUiTest : FilesUiFixture() {
    @Test fun renameNeedsChangedNameAndExplicitDesktopConsent(){
        var calls=0;var accepted:FileAction?=null
        val selected=page().copy(selection=FileSelection(rootId,entry=entry))
        compose.setContent{Frame(selected,FileActions(mutate={action,id,version,confirmed->assertEquals(objectId,id);assertEquals(entry.version,version);assertTrue(confirmed);accepted=action;calls++}))}
        compose.onNodeWithText("Rename on desktop").performScrollTo().performClick()
        compose.onNodeWithText("Confirm rename").assertIsNotEnabled()
        compose.onNodeWithText("New name").performTextReplacement("Renamed fixture.jpg")
        compose.onNodeWithText("I confirm this change on the desktop.").performScrollTo().performClick()
        compose.onNodeWithText("Confirm rename").performClick();assertEquals(1,calls);assertEquals("Renamed fixture.jpg",accepted!!.name)
    }
    @Test fun absentModifyAndTrashCapabilitiesDisableSelectedActions(){
        val browse=root.copy(capabilities=listOf("browse","read"))
        val state=page().copy(roots=listOf(browse),page=page().page!!.copy(root=browse),selection=FileSelection(rootId,entry=entry))
        compose.setContent{Frame(state)}
        compose.onNodeWithText("Rename on desktop").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Move to managed trash").performScrollTo().assertIsNotEnabled()
    }
    @Test fun nativeHostDecoderEnforcesDimensionsAndPixelAllocationBeforeDisplay(){
        val encoded=javaClass.getResourceAsStream("/bounded-preview-fixture.jpg")!!.use{it.readBytes()}
        val preview=PixelPreview(objectId,entry.version,20,14,encoded)
        val bitmap=decodeBoundedPreview(preview);assertEquals(20,bitmap.width);assertEquals(14,bitmap.height);assertTrue(bitmap.allocationByteCount<=256*256*4);bitmap.recycle()
        try{decodeBoundedPreview(PixelPreview(objectId,entry.version,256,256,encoded));org.junit.Assert.fail("Mismatched dimensions must fail")}catch(f:ConnectionFailure){assertEquals("INVALID_PREVIEW",f.code)}
    }
    @Test fun unknownOperationDismissalDoesNotClaimSuccessOrRemoteCancellation(){
        val input=FileMutationInput(rootId,"44444444-4444-4444-8444-444444444444",objectId,entry.version,FileAction("rename","New fixture.jpg"))
        val op=FileOperation("a".repeat(64),"55555555-5555-4555-8555-555555555555",input,1)
        var dismissed=false;compose.setContent{Frame(roots().copy(operations=listOf(op)),FileActions(dismiss={id,confirmed->assertEquals(input.operationId,id);dismissed=confirmed}))}
        scroll("Review / dismiss local retry");compose.onNodeWithText("Review / dismiss local retry").performClick()
        compose.onNodeWithText("Dismiss local retry").assertIsNotEnabled()
        compose.onNodeWithText("I understand and want to continue.").performClick();compose.onNodeWithText("Dismiss local retry").performClick();assertTrue(dismissed)
    }
    @Test fun renameConfirmationRemainsScrollableAtTwoHundredPercentType(){
        compose.setContent{Frame(page().copy(selection=FileSelection(rootId,entry=entry)),scale=2f)}
        compose.onNodeWithText("Rename on desktop").performScrollTo().performClick()
        compose.onNodeWithText("New name").performScrollTo().performTextReplacement("Large type fixture.jpg")
        compose.onNodeWithText("I confirm this change on the desktop.").performScrollTo().performClick()
        compose.onNodeWithText("Confirm rename").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Keep unchanged").assertIsDisplayed()
    }
}
