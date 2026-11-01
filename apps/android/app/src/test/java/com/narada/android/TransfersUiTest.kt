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
import com.narada.android.transfers.TransfersUiState
import com.narada.android.ui.NaradaTheme
import com.narada.android.ui.screens.*
import com.narada.connection.*
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class TransfersUiFixture {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    protected fun saved()=TransferTestData.verified(TransferTestData.peer(),"Fixture verified.bin")
    protected fun ui(vararg rows:DownloadRecord)=TransfersUiState(loading=false,records=rows.toList(),foreground=true,networkEligible=true)
    @Composable protected fun Frame(state:TransfersUiState,actions:TransferActions=TransferActions(),scale:Float=1f){val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){NaradaTheme{Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column{Text("HOST UI FIXTURE · synthetic transfers · no device proof",style=MaterialTheme.typography.labelSmall);Box(Modifier.weight(1f)){TransfersScreen(state,actions){}}}}}}}
    protected fun scroll(label:String){compose.onNodeWithTag("verified_transfers").performScrollToNode(hasText(label))}
    protected fun capture(name:String){compose.waitForIdle();val file=File(System.getProperty("narada.evidence.dir"),"host-renders/$name.png");file.parentFile!!.mkdirs();compose.runOnUiThread{val view=compose.activity.findViewById<View>(android.R.id.content);val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransfersUiTest:TransfersUiFixture(){
    @Test fun emptyQueueDoesNotInventFilesOrProgress(){compose.setContent{Frame(ui())};scroll("No downloads yet");compose.onNodeWithText("No downloads yet").assertIsDisplayed();compose.onAllNodesWithText("Verified private receipt").assertCountEquals(0);capture("15-transfers-empty")}
    @Test fun completeStagingIsNotDisplayedAsAPrivateReceipt(){val r=saved().copy(state=DownloadState.PAUSED,receipt=null);compose.setContent{Frame(ui(r).copy(networkEligible=false))};scroll("Verify saved fragments");compose.onNodeWithText("Verify saved fragments").assertIsEnabled().assertHeightIsAtLeast(48.dp);compose.onAllNodesWithText("Export a new copy…").assertCountEquals(0);capture("16-transfers-restart-paused")}
    @Test fun savedCopyRequiresNewConsentAndValidNewNameBeforeExport(){val r=saved();var calls=0;compose.setContent{Frame(ui(r),TransferActions(export={id,name,confirmed->assertEquals(r.id,id);assertEquals("my-new-copy.bin",name);assertTrue(confirmed);calls++}))};scroll("Export a new copy…");compose.onNodeWithText("Export a new copy…").performClick();compose.onNodeWithText("Choose export folder").assertIsNotEnabled();compose.onNodeWithText("New export name").performTextReplacement("../bad");compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Choose export folder").assertIsNotEnabled();compose.onNodeWithText("New export name").performScrollTo().performTextReplacement("my-new-copy.bin");compose.onNodeWithText("Choose export folder").performClick();assertEquals(1,calls)}
    @Test fun providerBusyStateDisablesNewPublicationButKeepsStopReachable(){val r=saved();var stop=0;compose.setContent{Frame(ui(r).copy(providerBusy=true),TransferActions(stopExport={stop++}))};scroll("Stop export");compose.onNodeWithText("Stop export").assertHeightIsAtLeast(48.dp).performClick();assertEquals(1,stop);scroll("Export a new copy…");compose.onNodeWithText("Export a new copy…").assertIsNotEnabled()}
    @Test fun exportDraftAndConsentAreNotRestoredFromSavedInstanceState(){val r=saved();val restoration=StateRestorationTester(compose);restoration.setContent{Frame(ui(r))};scroll("Export a new copy…");compose.onNodeWithText("Export a new copy…").performClick();compose.onNodeWithText("New export name").performTextReplacement("PRIVATE_EXPORT_DRAFT.bin");restoration.emulateSavedInstanceStateRestore();compose.onAllNodesWithText("PRIVATE_EXPORT_DRAFT.bin").assertCountEquals(0);compose.onAllNodesWithText("Choose export folder").assertCountEquals(0)}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransferReviewUiTest:TransfersUiFixture(){
    @Test fun uncertaintyRequiresReviewAndCannotOfferRewrite(){val r=saved();val e=ExportRecord(UUID.randomUUID().toString(),"unknown-copy.bin",r.createdAt,ExportState.UNCERTAIN,"content://fixture.documents/tree/root");var reviewed=false;compose.setContent{Frame(ui(r.copy(exports=listOf(e))),TransferActions(dismissExport={id,exportId,confirmed->assertEquals(r.id,id);assertEquals(e.id,exportId);reviewed=confirmed}))};scroll("Export a new copy…");compose.onNodeWithText("Export a new copy…").assertIsNotEnabled();scroll("Review export uncertainty");capture("17-transfers-uncertain");compose.onNodeWithText("Review export uncertainty").performClick();compose.onNodeWithText("Dismiss local review").assertIsNotEnabled();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Dismiss local review").performClick();assertTrue(reviewed)}
    @Test fun explicitLocalRecordRemovalIsNotRemoteDeletionOrAReceipt(){val r=saved();val e=ExportRecord(UUID.randomUUID().toString(),"unknown-copy.bin",r.createdAt,ExportState.DISMISSED_UNCONFIRMED);var removed=false;compose.setContent{Frame(ui(r.copy(exports=listOf(e))),TransferActions(forgetExport={_,id,confirmed->assertEquals(e.id,id);removed=confirmed}))};scroll("Remove this local export record");compose.onNodeWithText("Remove this local export record").performClick();compose.onNodeWithText("Remove local export record").assertIsNotEnabled();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Remove local export record").performClick();assertTrue(removed)}
    @Test fun largeTypeKeepsExportConsentAndCancellationReachable(){val r=saved();compose.setContent{Frame(ui(r),scale=2f)};scroll("Export a new copy…");capture("18-transfers-font-200");compose.onNodeWithText("Export a new copy…").performClick();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Choose export folder").assertIsEnabled().assertHeightIsAtLeast(48.dp);compose.onNodeWithText("Keep unchanged").assertIsDisplayed()}
    @Test fun cancellingADownloadStillRequiresLocalConfirmation(){val r=saved().copy(state=DownloadState.PAUSED,receipt=null,chunks=listOf(0));var cancelled=false;compose.setContent{Frame(ui(r),TransferActions(cancel={id,confirmed->assertEquals(r.id,id);cancelled=confirmed}))};scroll("Cancel on this phone");compose.onNodeWithText("Cancel on this phone").performClick();compose.onNodeWithText("Confirm local cancel").assertIsNotEnabled();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Confirm local cancel").performClick();assertTrue(cancelled)}
}
