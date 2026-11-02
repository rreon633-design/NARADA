package com.narada.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.narada.android.connection.*
import com.narada.android.ui.NaradaTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun document():TrustDocument {
        val key=AndroidTestIdentities.create()
        return TrustDocument(storeId=UUID.randomUUID().toString(),deviceId=key.deviceId,fingerprint=key.fingerprint,certificate=Base64.getEncoder().encodeToString(key.certificate.encoded))
    }
    @Composable private fun FixtureFrame(state:ConnectionsUiState,actions:ConnectionActions=ConnectionActions(available=true),scale:Float=1f) {
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) { NaradaTheme {
            Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) { Column {
                Text("HOST UI FIXTURE · no device/network proof")
                Box(Modifier.weight(1f)){ConnectionDevicesScreen("Test phone",state.copy(protection="Software fixture key · not a device test"),DiscoveryState(),actions)}
            } }
        } }
    }
    private fun scroll(label:String) { compose.onNodeWithTag("screen_devices").performScrollToNode(hasText(label)) }
    private fun capture(name:String) {
        compose.waitForIdle();val file=File(System.getProperty("narada.evidence.dir"),"host-renders/$name.png");file.parentFile!!.mkdirs()
        compose.runOnUiThread { val view=compose.activity.findViewById<View>(android.R.id.content);val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
    }
    @Test fun identityPreparationIsExplicitAndDoesNotInventDevices() {
        var preparations=0
        compose.setContent { FixtureFrame(ConnectionsUiState(loading=false),ConnectionActions(available=true,initialize={preparations++})) }
        scroll("Advanced setup & saved devices");compose.onNodeWithText("Advanced setup & saved devices").performClick()
        scroll("Prepare protected identity");compose.onNodeWithText("Prepare protected identity").performClick()
        assertEquals(1,preparations);compose.onAllNodesWithText("Connected",substring=true).assertCountEquals(0);capture("08-identity")
    }
    @Test fun invitationTextIsDroppedOnSavedStateRestoration() {
        val doc=document();val state=ConnectionsUiState(loading=false,document=doc)
        val restoration=StateRestorationTester(compose);restoration.setContent{FixtureFrame(state)}
        scroll("Advanced setup & saved devices");compose.onNodeWithText("Advanced setup & saved devices").performClick()
        scroll("Or paste the invitation code");compose.onNodeWithText("Or paste the invitation code").performTextInput("TEST_ONLY_UNPERSISTED_INVITATION")
        restoration.emulateSavedInstanceStateRestore();scroll("Or paste the invitation code")
        compose.onNodeWithText("Review code privately").assertIsNotEnabled()
    }
    @Test fun connectionControlsRemainReachableAtTwoHundredPercentFont() {
        val doc=document();compose.setContent { FixtureFrame(ConnectionsUiState(loading=false,document=doc),scale=2f) }
        scroll("Enable & scan QR");compose.onNodeWithText("Enable & scan QR").assertIsDisplayed();capture("09-devices-font-200")
        scroll("Grants stay separate");compose.onNodeWithText("Grants stay separate").assertIsDisplayed()
    }
    @Test fun unverifiedInvitationNeedsExplicitFingerprintComparison() {
        val doc=document().copy(enabled=true)
        val state=ConnectionsUiState(loading=false,document=doc,networkAvailable=true,preview=InvitationPreview("Unverified desktop","a".repeat(64),"192.168.0.2:12345",System.currentTimeMillis()+120000))
        compose.setContent{FixtureFrame(state)};scroll("Request pairing");compose.onNodeWithText("Request pairing").assertIsNotEnabled();capture("10-pairing-review")
    }
    @Test fun quickScanIsOneExplicitActionWithoutAutomaticInitializationByComposition() {
        var scans=0;var imports=0;var preparations=0
        compose.setContent { FixtureFrame(ConnectionsUiState(loading=false,foreground=true,networkAvailable=true),ConnectionActions(available=true,scanQr={scans++},importQr={imports++},initialize={preparations++})) }
        assertEquals(0,scans);assertEquals(0,preparations)
        scroll("Set up & scan QR");compose.onNodeWithText("Set up & scan QR").performClick()
        scroll("Import QR image");compose.onNodeWithText("Import QR image").performClick()
        assertEquals(1,scans);assertEquals(1,imports);assertEquals(0,preparations)
    }
    @Test fun quickScanWaitsForEligibleNetworkAndForeground() {
        var state by mutableStateOf(ConnectionsUiState(loading=false,foreground=true,networkAvailable=false))
        compose.setContent{FixtureFrame(state)};scroll("Set up & scan QR")
        compose.onNodeWithText("Set up & scan QR").assertIsNotEnabled()
        compose.runOnIdle{state=state.copy(networkAvailable=true,foreground=false)}
        compose.onNodeWithText("Set up & scan QR").assertIsNotEnabled()
    }
    @Test fun approvalProgressIsAutomaticOnlyWhileTheOwnerJobIsRunning() {
        val doc=document().copy(enabled=true)
        val peer=TrustedPeer("a".repeat(64),"b".repeat(64),name="Test desktop",endpoint="192.168.0.2:12345",invitationId=UUID.randomUUID().toString(),attemptId=UUID.randomUUID().toString(),expiresAt=System.currentTimeMillis()+120000,status=TrustStatus.WAITING)
        var state by mutableStateOf(ConnectionsUiState(loading=false,document=doc.copy(peers=listOf(peer)),foreground=true,networkAvailable=true,pairingInProgress=true))
        compose.setContent{FixtureFrame(state)}
        scroll("THIS PHONE’S FULL FINGERPRINT");compose.onNodeWithText(doc.fingerprint.chunked(4).joinToString(" ")).assertExists()
        compose.onNodeWithTag("pairing_active_work").assertExists()
        compose.onNodeWithText("Resume approval checks").assertDoesNotExist()
        compose.runOnIdle{state=state.copy(pairingInProgress=false)}
        scroll("Resume approval checks");compose.onNodeWithText("Resume approval checks").assertIsEnabled()
        compose.onNodeWithTag("pairing_active_work").assertDoesNotExist()
    }
    @Test fun finalFingerprintCheckIsStillRequiredAndNotRestored() {
        val doc=document().copy(enabled=true)
        val peer=TrustedPeer("a".repeat(64),"b".repeat(64),name="Approved desktop",endpoint="192.168.0.2:12345",invitationId=UUID.randomUUID().toString(),attemptId=UUID.randomUUID().toString(),expiresAt=System.currentTimeMillis()+120000,status=TrustStatus.APPROVED)
        val state=ConnectionsUiState(loading=false,document=doc.copy(peers=listOf(peer)),foreground=true,networkAvailable=true)
        val restoration=StateRestorationTester(compose);restoration.setContent{FixtureFrame(state)}
        scroll("Finish approved pairing");compose.onNodeWithText("Finish approved pairing").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Confirm this phone’s full fingerprint comparison").performClick()
        compose.onNodeWithText("Finish approved pairing").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore();scroll("Finish approved pairing")
        compose.onNodeWithText("Finish approved pairing").assertIsNotEnabled()
    }

    @Test fun leavingDevicesDropsItsInputLaunchScope() {
        var visible by mutableStateOf(true);val events=mutableListOf<Boolean>()
        compose.setContent { if(visible) FixtureFrame(ConnectionsUiState(loading=false),ConnectionActions(available=true,inputVisible={events.add(it)})) }
        compose.runOnIdle{assertEquals(listOf(true),events);visible=false}
        compose.runOnIdle{assertEquals(listOf(true,false),events)}
    }

}
