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
import com.narada.android.chat.*
import com.narada.android.connection.ConnectionsUiState
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

abstract class ChatUiFixture {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    protected val peer=TransferTestData.peer().copy(remotePermissions=Permissions(chat=true))
    private val identity=AndroidTestIdentities.create()
    protected val connection=ConnectionsUiState(loading=false,foreground=true,networkAvailable=true,transportAllowed=true,document=TrustDocument(storeId=UUID.randomUUID().toString(),deviceId=identity.deviceId,fingerprint=identity.fingerprint,certificate=Base64.getEncoder().encodeToString(identity.certificate.encoded),enabled=true,peers=listOf(peer),preferredPeer=peer.id))
    protected fun ui(rows:List<ChatRow> = emptyList())=ChatUiState(visible=true,foreground=true,peers=listOf(ChatPeer(peer.id,"Fixture approved desktop",true)),peerId=peer.id,access=chatAccess(connection,peer.id),enabled=true,networkEligible=true,rows=rows,retained=rows.size)
    protected fun row(status:ChatStatus=ChatStatus.DELIVERED)=ChatRow(UUID.randomUUID().toString(),"Synthetic host message — not production activity.",true,status,status==ChatStatus.DELIVERED,false,false,1,null)
    @Composable protected fun Frame(state:ChatUiState,actions:ChatActions=ChatActions(),c:ConnectionsUiState=connection,scale:Float=1f){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,scale)){NaradaTheme{Surface(Modifier.fillMaxSize()){Column{Text("HOST UI FIXTURE · synthetic chat · no device proof",style=MaterialTheme.typography.labelSmall);Box(Modifier.weight(1f)){ChatScreen(state,c,actions){}}}}}}}
    protected fun scroll(text:String){compose.onNodeWithTag("protected_chat").performScrollToNode(hasText(text))}
    protected fun capture(name:String){compose.waitForIdle();val file=File(System.getProperty("narada.evidence.dir"),"host-renders/$name.png");file.parentFile!!.mkdirs();compose.runOnUiThread{val v=compose.activity.findViewById<View>(android.R.id.content);val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatUiTest:ChatUiFixture(){
    @Test fun noApprovedPeerMeansNoFabricatedConversations(){val s=ui().copy(peers=emptyList(),peerId=null,access=chatAccess(connection,null),enabled=false);compose.setContent{Frame(s)};scroll("No approved conversation yet");compose.onNodeWithText("No approved conversation yet").assertIsDisplayed();compose.onAllNodesWithTag("chat_composer").assertCountEquals(0);capture("19-chat-no-peers")}
    @Test fun enrollmentRequiresExplicitReviewedConsent(){var accepted=false;compose.setContent{Frame(ui().copy(enabled=false),ChatActions(enable={accepted=it}))};scroll("Review & enable private chat");compose.onNodeWithText("Review & enable private chat").assertHeightIsAtLeast(48.dp).performClick();compose.onNodeWithText("Confirm").assertIsNotEnabled();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Confirm").performClick();assertTrue(accepted)}
    @Test fun emptyAndOfflineComposerCannotSubmit(){var sent=0;compose.setContent{Frame(ui().copy(networkEligible=false),ChatActions(send={_,_->sent++}))};scroll("Queue & sync text");compose.onNodeWithText("Queue & sync text").assertIsNotEnabled();compose.onNodeWithTag("chat_composer").performTextInput("private draft");compose.onNodeWithText("Queue & sync text").assertIsNotEnabled();compose.onNodeWithText("Save encrypted draft").assertIsEnabled();assertEquals(0,sent)}
    @Test fun typingDoesNotAutoSaveOrSendAndStableIntentPreventsDoubleSubmission(){val calls=mutableListOf<Pair<String,String>>();var saves=0;compose.setContent{Frame(ui(),ChatActions(send={id,text->calls+=id to text},saveDraft={saves++}))};scroll("Queue & sync text");compose.onNodeWithTag("chat_composer").performTextInput("typed text");assertEquals(0,saves);assertTrue(calls.isEmpty());compose.onNodeWithText("Queue & sync text").performClick().performClick();assertEquals(2,calls.size);assertEquals(calls[0],calls[1])}
    @Test fun unsavedTextIsNotSavedInstanceState(){val restore=StateRestorationTester(compose);restore.setContent{Frame(ui())};scroll("Queue & sync text");compose.onNodeWithTag("chat_composer").performTextInput("PRIVATE_UNSAVED_DRAFT");restore.emulateSavedInstanceStateRestore();compose.onAllNodesWithText("PRIVATE_UNSAVED_DRAFT").assertCountEquals(0)}
    @Test fun deliveryIsNotClaimedToBeHumanRead(){val r=row();compose.setContent{Frame(ui(listOf(r)))};scroll(chatStatus(r));compose.onNodeWithText("Desktop delivery receipt saved · not a read receipt").assertIsDisplayed();capture("20-chat-receipt-history")}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatPrivacyUiTest:ChatUiFixture(){
    @Test fun foregroundChangeMasksAStalePrivateProjectionImmediately(){val c=mutableStateOf(connection);val r=row().copy(text="PRIVATE_HISTORY_SENTINEL");compose.setContent{Frame(ui(listOf(r)),c=c.value)};scroll(r.text);compose.onNodeWithText(r.text).assertIsDisplayed();compose.runOnIdle{c.value=c.value.copy(foreground=false)};compose.onAllNodesWithText(r.text).assertCountEquals(0);compose.onNodeWithText("Private view is closed").assertExists()}
    @Test fun changedEpochMasksHistoryBeforeCoordinatorClearsItsFlow(){val c=mutableStateOf(connection);val r=row().copy(text="PRIVATE_EPOCH_SENTINEL");compose.setContent{Frame(ui(listOf(r)),c=c.value)};scroll(r.text);compose.runOnIdle{c.value=c.value.copy(accessEpoch=c.value.accessEpoch+1)};compose.onAllNodesWithText(r.text).assertCountEquals(0)}
    @Test fun unknownOutcomeRequiresConsentAndCannotClaimRecall(){val r=row(ChatStatus.UNCERTAIN);var cancelled=false;compose.setContent{Frame(ui(listOf(r)),ChatActions(cancel={id,accepted->assertEquals(r.id,id);cancelled=accepted}))};scroll("Stop retrying this text");capture("21-chat-unknown-outcome");compose.onNodeWithText("Stop retrying this text").performClick();compose.onNodeWithText("Confirm").assertIsNotEnabled();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Confirm").performClick();assertTrue(cancelled)}
    @Test fun priorPairingNeverOffersAReplayUnderNewAuthority(){val r=row(ChatStatus.UNCERTAIN).copy(oldPairing=true);compose.setContent{Frame(ui(listOf(r)))};scroll("Review exact retry");compose.onNodeWithText("Review exact retry").assertIsNotEnabled();compose.onNodeWithText("Retained from an earlier pairing · no replay under new authority").assertExists()}
    @Test fun largeTypeKeepsStopAndConsentReachable(){val r=row(ChatStatus.UNCERTAIN);compose.setContent{Frame(ui(listOf(r)),scale=2f)};scroll("Review exact retry");capture("22-chat-font-200");compose.onNodeWithText("Review exact retry").assertHeightIsAtLeast(48.dp).performClick();compose.onNodeWithText("I understand and want to continue.").performScrollTo().performClick();compose.onNodeWithText("Confirm").assertIsEnabled();compose.onNodeWithText("Keep unchanged").assertIsDisplayed()}
    @Test fun stopRemainsAvailableWhileNetworkBatchOwnsWork(){var stopped=false;compose.setContent{Frame(ui().copy(busy=true),ChatActions(stop={stopped=true}))};scroll("Stop this batch");compose.onNodeWithText("Stop this batch").assertHeightIsAtLeast(48.dp).performClick();assertTrue(stopped)}
}
