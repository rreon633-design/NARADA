package com.narada.android

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.narada.android.ui.screens.FileActions
import com.narada.connection.FileSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesDownloadUiTest:FilesUiFixture() {
    @Test fun selectedFileNeedsExplicitPrivateDownloadConsent(){var calls=0;compose.setContent{Frame(page().copy(selection=FileSelection(rootId,entry=entry)),FileActions(downloadAvailable=true,download={id,version,confirmed->assertEquals(objectId,id);assertEquals(entry.version,version);assertTrue(confirmed);calls++}))};compose.onNodeWithText("Save verified private copy").performScrollTo().performClick();compose.onNodeWithText("Queue download").assertIsNotEnabled();assertEquals(0,calls);compose.onNodeWithText("I want to download this exact file privately.").performScrollTo().performClick();compose.onNodeWithText("Queue download").performClick();assertEquals(1,calls)}
    @Test fun absentTransferCapabilityDisablesDownloadWithoutFakeFallback(){compose.setContent{Frame(page().copy(selection=FileSelection(rootId,entry=entry)),FileActions(downloadAvailable=false))};compose.onNodeWithText("Save verified private copy").performScrollTo().assertIsNotEnabled()}
    @Test fun oversizedFilesCannotQueueAndDirectoriesDoNotOfferRecursiveDownload(){val tooLarge=entry.copy(size="67108865");compose.setContent{Frame(page().copy(selection=FileSelection(rootId,entry=tooLarge)),FileActions(downloadAvailable=true))};compose.onNodeWithText("Save verified private copy").performScrollTo().assertIsNotEnabled()}
    @Test fun consentFooterRemainsReachableAtTwoHundredPercentFont(){compose.setContent{Frame(page().copy(selection=FileSelection(rootId,entry=entry)),FileActions(downloadAvailable=true),2f)};compose.onNodeWithText("Save verified private copy").performScrollTo().performClick();compose.onNodeWithText("I want to download this exact file privately.").performScrollTo().performClick();compose.onNodeWithText("Queue download").assertIsEnabled().assertHeightIsAtLeast(48.dp);compose.onNodeWithText("Keep unchanged").assertIsDisplayed()}
    @Test fun directoriesDoNotOfferOrdinaryFileDownload(){compose.setContent{Frame(page().copy(selection=FileSelection(rootId,entry=folder)),FileActions(downloadAvailable=true))};compose.onAllNodesWithText("Save verified private copy").assertCountEquals(0)}
}
