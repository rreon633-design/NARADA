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
        scroll("Prepare protected identity");compose.onNodeWithText("Prepare protected identity").performClick()
        assertEquals(1,preparations);compose.onAllNodesWithText("Connected",substring=true).assertCountEquals(0);capture("08-identity")
    }
    @Test fun invitationTextIsDroppedOnSavedStateRestoration() {
        val doc=document();val state=ConnectionsUiState(loading=false,document=doc)
        val restoration=StateRestorationTester(compose);restoration.setContent{FixtureFrame(state)}
        scroll("Or paste the invitation code");compose.onNodeWithText("Or paste the invitation code").performTextInput("TEST_ONLY_UNPERSISTED_INVITATION")
        restoration.emulateSavedInstanceStateRestore();scroll("Or paste the invitation code")
        compose.onNodeWithText("Review code privately").assertIsNotEnabled()
    }
    @Test fun connectionControlsRemainReachableAtTwoHundredPercentFont() {
        val doc=document();compose.setContent { FixtureFrame(ConnectionsUiState(loading=false,document=doc),scale=2f) }
        scroll("Scan desktop QR");compose.onNodeWithText("Scan desktop QR").assertIsDisplayed();capture("09-devices-font-200")
        scroll("Grants stay separate");compose.onNodeWithText("Grants stay separate").assertIsDisplayed()
    }
    @Test fun unverifiedInvitationNeedsExplicitFingerprintComparison() {
        val doc=document().copy(enabled=true)
        val state=ConnectionsUiState(loading=false,document=doc,networkAvailable=true,preview=InvitationPreview("Unverified desktop","a".repeat(64),"192.168.0.2:12345",System.currentTimeMillis()+120000))
        compose.setContent{FixtureFrame(state)};scroll("Request pairing");compose.onNodeWithText("Request pairing").assertIsNotEnabled();capture("10-pairing-review")
    }
}
