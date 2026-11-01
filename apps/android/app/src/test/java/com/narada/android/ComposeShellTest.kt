package com.narada.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.narada.android.data.MetadataStore
import com.narada.android.ui.*
import com.narada.domain.*
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeShellTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun ready()=WorkspaceUiState(WorkspaceLoad.Ready(WorkspaceSettings(setupCompleted=true,reduceMotion=true),MetadataStore.Snapshot(emptyList(),null,emptyList())))
    private fun actions(edit:(String)->Unit={},discard:()->Unit={},complete:(String)->Unit={},retry:()->Unit={}):WorkspaceActions = WorkspaceActions(
        completeSetup=complete,saveName={},editName=edit,discardName=discard,notifications={},reduceMotion={},testNotification={},pickFolder={},forgetFolder={},requestCheck={},cancelCheck={},clearActivity={},inspectReport={},dismissReport={},shareReport={},systemNotifications={},retry=retry,routeConsumed={},
    )
    private fun render(state:()->WorkspaceUiState={ready()},actions:WorkspaceActions=actions(),scale:Float=1f) {
        compose.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) { NaradaTheme { WorkspaceApp(state(),null,emptyFlow(),null,actions) } }
        }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val output=File(System.getProperty("narada.evidence.dir"),"host-renders/$name.png");output.parentFile!!.mkdirs()
        // PixelCopy waits for a real window compositor; use the actual native View/Canvas
        // under Robolectric instead. This is a HOST render, never a device screenshot.
        compose.runOnUiThread {
            val view=compose.activity.findViewById<View>(android.R.id.content)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val samples=(0 until bitmap.height step 20).flatMap { y -> (0 until bitmap.width step 20).map { x -> bitmap.getPixel(x,y) } }.toSet()
            assertTrue("Host render must contain drawn UI, not a blank canvas", samples.size>4)
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    @Test fun homeHasFiveTabsAndNoPretendPeer() {
        render();Destination.primary.forEach { compose.onNodeWithTag("tab_${it.route}").assertIsDisplayed() }
        compose.onNodeWithTag("tab_home").assertIsSelected();compose.onNodeWithText("NO PAIRED DEVICES").assertIsDisplayed()
        compose.onAllNodesWithText("Connected",substring=true).assertCountEquals(0);capture("02-home")
    }
    @Test fun onboardingHasRealNameInputAndNoPermissionPrompt() {
        var completed:String?=null
        val initial=ready().copy(load=WorkspaceLoad.Ready(WorkspaceSettings(),MetadataStore.Snapshot(emptyList(),null,emptyList())))
        render({initial},actions(complete={completed=it}));capture("01-onboarding")
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Device name").performTextReplacement("My test phone")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Open my workspace").performScrollTo().performClick()
        assertEquals("My test phone",completed)
        compose.onNodeWithTag("tab_home").assertDoesNotExist()
    }
    @Test fun filesAndChatRequireExplicitAuthorizedPrivateViews() {
        render();compose.onNodeWithTag("tab_files").performClick()
        compose.onNodeWithText("Not ready to browse").assertIsDisplayed();capture("03-files")
        compose.onNodeWithTag("tab_chat").performClick()
        compose.onNodeWithText("Private view is closed").assertIsDisplayed();compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
    @Test fun moreIsARealScreenAndNestedBackReturnsToIt() {
        render();compose.onNodeWithTag("tab_more").performClick();capture("04-more")
        compose.onNodeWithText("Sync",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Keep a copy.\nNot a promise.").assertIsDisplayed();compose.onNodeWithTag("tab_more").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("A few more things. All in one place.").assertIsDisplayed()
    }
    @Test fun switchingTabsRestoresTheNestedMoreStack() {
        render();compose.onNodeWithTag("tab_more").performClick();compose.onNodeWithText("Sync",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("tab_files").performClick();compose.onNodeWithTag("tab_more").performClick()
        compose.onNodeWithText("Keep a copy.\nNot a promise.").assertIsDisplayed()
    }
    @Test fun homeSettingsShortcutReturnsToItsActualCaller() {
        render();compose.onNodeWithContentDescription("Open settings").performClick();capture("05-settings")
        compose.onNodeWithContentDescription("Back").performClick();compose.onNodeWithTag("tab_home").assertIsSelected()
    }
    @Test fun unsavedNameGuardsTabNavigationAndCanBeDiscarded() {
        var state by mutableStateOf(ready())
        render({state},actions(edit={state=state.copy(draftName=it)},discard={state=state.copy(draftName=null)}))
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Device name").performTextReplacement("Unsaved phone")
        compose.onNodeWithTag("tab_files").performClick()
        compose.onNodeWithText("Discard the unsaved name?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick();compose.onNodeWithText("Unsaved phone").assertIsDisplayed()
        compose.onNodeWithTag("tab_files").performClick();compose.onNodeWithText("Discard changes").performClick()
        compose.onNodeWithTag("tab_files").assertIsSelected();assertNull(state.draftName)
    }
    @Test fun navigationSurvivesSavedStateRestoration() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { NaradaTheme { WorkspaceApp(ready(),null,emptyFlow(),null,actions()) } }
        compose.onNodeWithTag("tab_more").performClick();compose.onNodeWithText("Sync",useUnmergedTree=true).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Keep a copy.\nNot a promise.").assertIsDisplayed()
    }
    @Test fun twoHundredPercentTextKeepsAllTabTargetsUsable() {
        render(scale=2f)
        val bounds=Destination.primary.map { compose.onNodeWithTag("tab_${it.route}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.forEach { assertTrue(it.width>=96f);assertTrue(it.height>=96f) }
        bounds.zipWithNext().forEach { (left,right) -> assertTrue(left.right<=right.left+1f) }
        compose.onNodeWithTag("tab_transfers").performClick();compose.onNodeWithText("Your files.\nA checked copy.").assertIsDisplayed()
        compose.onNodeWithTag("tab_home").performClick();capture("07-home-font-200")
    }
    @Test fun diagnosticsStartsWithoutAFakeSuccessfulCheck() {
        render();compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag("screen_settings").performScrollToNode(hasText("Diagnostics"))
        compose.onNodeWithText("Diagnostics").performClick();compose.onNodeWithText("Not run yet").assertIsDisplayed();capture("06-diagnostics")
    }
    @Test fun storageFailureHasNoDestructiveResetOrDefaultWorkspace() {
        var retries=0
        render({WorkspaceUiState(WorkspaceLoad.Failed(FailureCode.STORAGE_CORRUPT))},actions(retry={retries++}))
        compose.onNodeWithText("Retry without resetting").performClick();assertEquals(1,retries)
        compose.onNodeWithTag("tab_home").assertDoesNotExist();compose.onAllNodesWithText("Reset app").assertCountEquals(0)
    }
}
