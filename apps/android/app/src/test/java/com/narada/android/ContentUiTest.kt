package com.narada.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.narada.android.attachments.*
import com.narada.android.chat.chatAccess
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.offline.*
import com.narada.android.ui.NaradaTheme
import com.narada.android.ui.screens.*
import com.narada.connection.*
import java.io.File
import java.util.Base64
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class ContentUiFixture {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected val peer = TransferTestData.peer().copy(remotePermissions = Permissions(chat = true, attachment = true, roots = listOf(RootGrant(TransferTestData.root, listOf("browse", "read", "sync_read")))))
    private val identity = AndroidTestIdentities.create()
    protected val connection = ConnectionsUiState(loading = false, foreground = true, networkAvailable = true, transportAllowed = true, document = TrustDocument(storeId = UUID.randomUUID().toString(), deviceId = identity.deviceId, fingerprint = identity.fingerprint, certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded), enabled = true, peers = listOf(peer), preferredPeer = peer.id))
    protected fun attachment() = AttachmentRow(UUID.randomUUID().toString(), "Synthetic inert attachment.bin", 128, true, AttachmentState.VERIFIED, true, false, 1, 0, 1, false, false, null, emptyList())
    protected fun astate(vararg rows: AttachmentRow) = AttachmentsUiState(visible = true, peerId = peer.id, access = chatAccess(connection, peer.id), rows = rows.toList())
    protected fun entry() = SyncEntry(TransferTestData.objectId, null, "Synthetic source.bin", "Synthetic source.bin", "file", "128", "a".repeat(64))
    protected fun ostate() = OfflineUiState(visible = true, peerId = peer.id, rootId = TransferTestData.root, access = chatAccess(connection, peer.id), roots = listOf(OfflineRoot(peer.id, TransferTestData.root, "Approved fixture root")), entries = listOf(entry()), freshAt = 1_793_404_800_000L, scopeCurrent = true, networkEligible = true)
    @Composable protected fun AFrame(state: AttachmentsUiState, actions: AttachmentActions = AttachmentActions(), c: ConnectionsUiState = connection, scale: Float = 1f) { val d = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) { NaradaTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("HOST UI FIXTURE · synthetic · no device proof", style = MaterialTheme.typography.labelSmall); AttachmentsPanel(state, c, actions, peer.id, "", true) } } } } }
    @Composable protected fun OFrame(state: OfflineUiState, actions: OfflineActions = OfflineActions(), c: ConnectionsUiState = connection, scale: Float = 1f) { val d = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) { NaradaTheme { Surface(Modifier.fillMaxSize()) { Column { Text("HOST UI FIXTURE · synthetic · no device proof", style = MaterialTheme.typography.labelSmall); Box(Modifier.weight(1f)) { OfflineScreen(state, c, actions) } } } } } }
    protected fun ascroll(text: String) { compose.onNodeWithText(text).performScrollTo() }
    protected fun oscroll(text: String) { compose.onNodeWithTag("offline_pins").performScrollToNode(hasText(text)) }
    protected fun capture(name: String) { compose.waitForIdle(); val file = File(System.getProperty("narada.evidence.dir"), "host-renders/$name.png"); file.parentFile!!.mkdirs(); compose.runOnUiThread { val v = compose.activity.findViewById<View>(android.R.id.content); val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b)); file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() } }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentUiTest : ContentUiFixture() {
    @Test fun importingRequiresASeparateReviewAndNeverSelectsAutomatically() { var accepted = false; compose.setContent { AFrame(astate(), AttachmentActions(import = { _, yes -> accepted = yes })) }; ascroll("Choose an encrypted attachment"); compose.onNodeWithText("Choose an encrypted attachment").assertHeightIsAtLeast(48.dp).performClick(); assertFalse(accepted); compose.onNodeWithText("Read one selected file?").assertIsDisplayed(); compose.onNodeWithText("I reviewed · continue").assertIsNotEnabled(); compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick(); compose.onNodeWithText("I reviewed · continue").performClick(); assertTrue(accepted) }
    @Test fun verifiedPrivateCiphertextDoesNotImplyMessageOrRemoteByteDelivery() { val r = attachment(); compose.setContent { AFrame(astate(r)) }; ascroll("Review & send encrypted bytes"); compose.onNodeWithText("Review & send encrypted bytes").assertIsNotEnabled(); compose.onNodeWithText("Private copy fully verified · sync the chat manifest next").assertExists(); compose.onAllNodesWithText("Desktop full verification confirmed · private copy retained").assertCountEquals(0); capture("23-attachment-manifest-first") }
    @Test fun aMessageReceiptOnlyEnablesExplicitByteSendItDoesNotClaimRemoteStored() { val r = attachment().copy(messageDelivered = true); var sent = false; compose.setContent { AFrame(astate(r), AttachmentActions(run = { id, yes -> assertEquals(r.id, id); sent = yes })) }; ascroll("Review & send encrypted bytes"); compose.onNodeWithText("Review & send encrypted bytes").assertIsEnabled().performClick(); assertFalse(sent); compose.onNodeWithText("I reviewed · continue").assertIsNotEnabled(); compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick(); compose.onNodeWithText("I reviewed · continue").performClick(); assertTrue(sent); compose.onAllNodesWithText("Desktop full verification confirmed · private copy retained").assertCountEquals(0) }
    @Test fun backgroundAndAuthorityChangeMaskNamesBeforeFlowRefresh() { val c = mutableStateOf(connection); val r = attachment().copy(name = "PRIVATE_ATTACHMENT_SENTINEL"); compose.setContent { AFrame(astate(r), c = c.value) }; ascroll(r.name); compose.onNodeWithText(r.name).assertIsDisplayed(); compose.runOnIdle { c.value = c.value.copy(accessEpoch = c.value.accessEpoch + 1) }; compose.onAllNodesWithText(r.name).assertCountEquals(0); compose.onNodeWithText("Load private attachment queue").assertExists() }
    @Test fun uncertainExportBlocksAnotherCreateAndNeverOffersExternalDeletion() { val r = attachment().copy(exports = listOf(AttachmentExportRow(UUID.randomUUID().toString(), "uncertain.bin", ExportState.UNCERTAIN, "PROVIDER_TIMEOUT"))); compose.setContent { AFrame(astate(r)) }; ascroll("Export an inert new document"); compose.onNodeWithText("Export an inert new document").assertIsNotEnabled(); ascroll("Review uncertain external document"); capture("24-attachment-uncertain-export"); compose.onNodeWithText("Review uncertain external document").performClick(); compose.onNodeWithText("Keep unchanged").assertIsDisplayed(); compose.onAllNodes(hasText("Delete external document")).assertCountEquals(0) }
    @Test fun exportNameAndOpenReviewAreNotRestoredFromSavedInstanceState() { val restore = StateRestorationTester(compose); restore.setContent { AFrame(astate(attachment())) }; ascroll("Export an inert new document"); compose.onNodeWithText("Export an inert new document").performClick(); compose.onNodeWithText("New export name").performTextClearance(); compose.onNodeWithText("New export name").performTextInput("PRIVATE_UNSAVED_EXPORT"); restore.emulateSavedInstanceStateRestore(); compose.onAllNodesWithText("PRIVATE_UNSAVED_EXPORT").assertCountEquals(0); compose.onAllNodesWithText("Create a new inert document?").assertCountEquals(0) }

}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineUiTest : ContentUiFixture() {
    @Test fun completedMetadataDoesNotFabricateAnAvailablePin() { compose.setContent { OFrame(ostate()) }; oscroll("No pinned copies"); compose.onNodeWithText("No pinned copies").assertIsDisplayed(); compose.onAllNodesWithText("Verified private version available").assertCountEquals(0); capture("26-offline-metadata-not-bytes") }
    @Test fun creatingAPinRequiresReviewedConsentForTheExactObject() { var id: String? = null; compose.setContent { OFrame(ostate(), OfflineActions(pin = { selected, yes -> assertTrue(yes); id = selected })) }; oscroll("Review & pin this version"); compose.onNodeWithText("Review & pin this version").assertHeightIsAtLeast(48.dp).performClick(); assertNull(id); compose.onNodeWithText("I reviewed · continue").assertIsNotEnabled(); compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick(); compose.onNodeWithText("I reviewed · continue").performClick(); assertEquals(TransferTestData.objectId, id) }
    @Test fun partialOrFormerAuthorityMetadataCannotStartANewPin() { compose.setContent { OFrame(ostate().copy(partialCount = 16, scopeCurrent = false)) }; oscroll("Review & pin this version"); compose.onNodeWithText("Review & pin this version").assertIsNotEnabled(); compose.onAllNodesWithText("Verified private version available").assertCountEquals(0) }
    @Test fun sourceTombstoneStillShowsVerifiedRetainedCopyAndBlocksRefresh() { val p = OfflinePinRow(UUID.randomUUID().toString(), "Retained source.bin", true, true, false, false, null, null, UUID.randomUUID().toString(), emptyList()); compose.setContent { OFrame(ostate().copy(pins = listOf(p))) }; oscroll("Source removed"); compose.onNodeWithText("Verified private version available").assertExists(); capture("27-offline-retained-tombstone"); oscroll("Download a separate refresh candidate"); compose.onNodeWithText("Download a separate refresh candidate").assertIsNotEnabled() }
    @Test fun backgroundMasksScopedPathsEvenIfUiStateStillContainsThem() { val c = mutableStateOf(connection); compose.setContent { OFrame(ostate(), c = c.value) }; oscroll(entry().name); compose.onNodeWithText(entry().name).assertIsDisplayed(); compose.runOnIdle { c.value = c.value.copy(foreground = false) }; compose.onAllNodesWithText(entry().name).assertCountEquals(0); compose.onNodeWithText("Private offline view is closed").assertExists() }
    @Test fun largeFontKeepsMetadataPauseAndReviewControlsReachable() { compose.setContent { OFrame(ostate().copy(busy = true), scale = 2f) }; oscroll("Pause metadata refresh"); compose.onNodeWithText("Pause metadata refresh").assertHeightIsAtLeast(48.dp).assertIsEnabled(); capture("28-offline-font-200") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentLargeTypeUiTest : ContentUiFixture() {
    @Test fun twoHundredPercentFontKeepsExportAndCancellationReachable() { compose.setContent { AFrame(astate(attachment()), scale = 2f) }; ascroll("Export an inert new document"); compose.onNodeWithText("Export an inert new document").assertHeightIsAtLeast(48.dp).performClick(); compose.onNodeWithText("Keep unchanged").assertIsDisplayed(); compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick(); compose.onNodeWithText("I reviewed · continue").assertIsEnabled().assertHeightIsAtLeast(48.dp); capture("25-attachment-font-200") }
}
