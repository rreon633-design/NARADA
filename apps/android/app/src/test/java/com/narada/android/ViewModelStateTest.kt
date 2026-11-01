package com.narada.android

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.narada.android.platform.*
import com.narada.android.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ViewModelStateTest {
    private lateinit var f:TestFixture
    private val owner=ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(Dispatchers.Unconfined);f=TestFixture() }
    @After fun cleanup() { owner.clear();f.close();Dispatchers.resetMain() }
    private fun vm(handle:SavedStateHandle=SavedStateHandle()):WorkspaceViewModel = WorkspaceViewModel(handle,f.settings,f.store,FolderPermissions(f.permissions,f.store),f.coordinator,PrivateNotifications(f.context),PrivateFiles(f.context)).also { owner.put("workspace",it) }
    @Test fun readableStoresBecomeReadyWithoutInventingPeersOrWork() = runBlocking {
        val model=vm();val state=withTimeout(5000) { model.state.first { it.load is WorkspaceLoad.Ready } }
        val ready=state.load as WorkspaceLoad.Ready
        assertFalse(ready.settings.setupCompleted);assertNull(ready.metadata.job);assertTrue(ready.metadata.events.isEmpty());assertTrue(f.scheduler.enqueued.isEmpty())
    }
    @Test fun restoredUnsavedNameIsNotACommittedPreference() = runBlocking {
        val handle=SavedStateHandle(mapOf("draft_name" to "Uncommitted draft"));val model=vm(handle)
        val state=withTimeout(5000) { model.state.first { it.load is WorkspaceLoad.Ready } }
        assertEquals("Uncommitted draft",state.draftName);assertEquals("My phone",f.settings.current().deviceName)
        model.discardName();assertNull(handle.get<String>("draft_name"));assertEquals("My phone",f.settings.current().deviceName)
    }
    @Test fun corruptPreferencesProduceVisibleFailureNotWelcomeDefaults() = runBlocking {
        val bytes=byteArrayOf(-1,-1,-1,0);f.settingsFile.writeBytes(bytes)
        val state=withTimeout(5000) { vm().state.first { it.load is WorkspaceLoad.Failed } }
        assertTrue(state.load is WorkspaceLoad.Failed);assertArrayEquals(bytes,f.settingsFile.readBytes())
    }
}
