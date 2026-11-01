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

abstract class FilesUiFixture {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    protected val rootId="11111111-1111-4111-8111-111111111111"
    protected val objectId="22222222-2222-4222-8222-222222222222"
    protected val root=RemoteRoot(rootId,"Fixture shared",1,"1","ready",null,"2026-10-31T09:00:00+00:00",2,0,listOf("browse","read","modify","trash","create"))
    protected val entry=RemoteEntry(objectId,"Fixture photo.jpg","file","a".repeat(64),"24576","2026-10-31T09:00:00+00:00","Fixture photo.jpg",null,true)
    protected val folder=entry.copy(object_id="33333333-3333-4333-8333-333333333333",name="Fixture documents",relative_path="Fixture documents",kind="directory",preview_candidate=false)
    protected fun roots()=FileBrowserState(visible=true,authorized=true,roots=listOf(root))
    protected fun page()=roots().copy(location=FileLocation(rootId),page=PageReply(root,listOf(folder,entry),listOf(Breadcrumb(root.label,null)),null,"1",2,"Fixture"))
    @Composable protected fun Frame(state:FileBrowserState,actions:FileActions=FileActions(),scale:Float=1f) {
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) { NaradaTheme { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column {
            Text("HOST UI FIXTURE · synthetic files · not a device test",style=MaterialTheme.typography.labelSmall)
            Box(Modifier.weight(1f)){FilesScreen(state,ConnectionsUiState(loading=false),actions){}}
        } } } }
    }
    protected fun scroll(label:String){compose.onNodeWithTag("remote_files").performScrollToNode(hasText(label))}
    protected fun capture(name:String){
        compose.waitForIdle();val file=File(System.getProperty("narada.evidence.dir"),"host-renders/$name.png");file.parentFile!!.mkdirs()
        compose.runOnUiThread{val view=compose.activity.findViewById<View>(android.R.id.content);val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image));file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesUiTest : FilesUiFixture() {
    @Test fun grantedRootCardsInvokeOnlyOpaqueIdentifiers(){
        var opened:String?=null;compose.setContent{Frame(roots(),FileActions(openRoot={opened=it}))}
        compose.onNodeWithText("Fixture shared").performClick();assertEquals(rootId,opened);capture("11-files-roots")
    }
    @Test fun browseRowsSearchAndDetailsAreNativeControls(){
        var selected:String?=null;var searched:String?=null
        compose.setContent{Frame(page(),FileActions(select={selected=it},search={searched=it}))}
        scroll("Search this root");compose.onNodeWithTag("file_search").performTextInput("Fixture")
        compose.onNodeWithText("Search",substring=false).performClick();assertEquals("Fixture",searched)
        scroll("Fixture photo.jpg");compose.onNodeWithText("Fixture photo.jpg").performClick();assertEquals(objectId,selected);capture("12-files-browse")
    }
    @Test fun deniedStateDoesNotPretendToBeAnEmptySuccessfulFolder(){
        compose.setContent{Frame(FileBrowserState(visible=true,error="ACCESS_DENIED"))}
        compose.onNodeWithTag("file_error").assertExists();compose.onAllNodesWithText("No live entries on this page").assertCountEquals(0);capture("14-files-denied")
    }
    @Test fun searchDraftIsNotSavedAcrossStateRestoration(){
        val restoration=StateRestorationTester(compose);restoration.setContent{Frame(page())}
        scroll("Search this root");compose.onNodeWithTag("file_search").performTextInput("PRIVATE_SEARCH_NOT_SAVED")
        restoration.emulateSavedInstanceStateRestore();scroll("Search this root");compose.onAllNodesWithText("PRIVATE_SEARCH_NOT_SAVED").assertCountEquals(0)
    }
    @Test fun rootActionsRemainReachableAtTwoHundredPercentFont(){
        compose.setContent{Frame(roots(),scale=2f)};scroll("Fixture shared");compose.onNodeWithText("Fixture shared").assertIsDisplayed();capture("13-files-font-200")
        scroll("Refresh roots");compose.onNodeWithText("Refresh roots").assertHeightIsAtLeast(48.dp)
    }
}
