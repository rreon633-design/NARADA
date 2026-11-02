package com.narada.android.ui

import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narada.android.BuildConfig
import com.narada.android.data.MetadataStore
import com.narada.android.data.SettingsStore
import com.narada.android.platform.FolderPermissions
import com.narada.android.platform.PrivateFiles
import com.narada.android.platform.PrivateNotifications
import com.narada.android.work.WorkCoordinator
import com.narada.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface WorkspaceLoad {
    data object Loading : WorkspaceLoad
    data class Ready(val settings: WorkspaceSettings, val metadata: MetadataStore.Snapshot) : WorkspaceLoad
    data class Failed(val code: FailureCode) : WorkspaceLoad
}
data class WorkspaceUiState(val load: WorkspaceLoad = WorkspaceLoad.Loading, val busy: Boolean = false, val notificationsAllowed: Boolean = false, val draftName: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val settings: SettingsStore, private val metadata: MetadataStore,
    private val folders: FolderPermissions, private val work: WorkCoordinator,
    private val notifications: PrivateNotifications, private val files: PrivateFiles,
) : ViewModel() {
    private val draft = savedState.getStateFlow<String?>("draft_name", null)
    private val retry = MutableStateFlow(0)
    private val busy = MutableStateFlow(false)
    private val allowed = MutableStateFlow(notifications.canPost())
    private var action: Job? = null
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()
    private val load = retry.flatMapLatest {
        combine(settings.settings, metadata.snapshots) { preferences, snapshot -> WorkspaceLoad.Ready(preferences, snapshot) as WorkspaceLoad }
            .catch { emit(WorkspaceLoad.Failed(FailureCode.STORAGE_UNAVAILABLE)) }
    }
    val state = combine(load, busy, allowed, draft) { data, working, permission, name -> WorkspaceUiState(data, working, permission, name) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceUiState())
    val report = MutableStateFlow<SupportReport?>(null)
    val requestedRoute = MutableStateFlow<Destination?>(null)
    fun retryRead() { retry.value++ }
    fun routeConsumed() { requestedRoute.value = null }
    fun showNotifications() { requestedRoute.value = Destination.NOTIFICATIONS }
    fun onForeground() {
        allowed.value = notifications.canPost()
        // Read/reconcile only; never create a job merely because an Activity was recreated.
        viewModelScope.launch { runCatching { folders.reconcile(); work.reconcile() } }
    }
    private fun runAction(block: suspend () -> Unit) {
        if (action?.isActive == true) return
        busy.value = true
        action = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _notices.emit(message((failure as? FoundationFailure)?.code ?: FailureCode.STORAGE_UNAVAILABLE)) }
            finally { busy.value = false }
        }
    }
    private suspend fun recordBestEffort(kind: ActivityKind) { runCatching { metadata.record(kind) } }
    fun completeSetup(name: String) = runAction { settings.completeSetup(name); recordBestEffort(ActivityKind.SETUP_COMPLETED) }
    fun editName(name: String) { if (name.length <= 192) savedState["draft_name"] = name }
    fun discardName() { savedState["draft_name"] = null }
    fun saveName(name: String) = runAction { settings.changeName(name); savedState["draft_name"] = null; recordBestEffort(ActivityKind.SETTINGS_SAVED); _notices.emit("Device name saved on this phone.") }
    fun setReduceMotion(enabled: Boolean) = runAction { settings.reduceMotion(enabled); recordBestEffort(ActivityKind.SETTINGS_SAVED) }
    fun setNotifications(enabled: Boolean) = runAction {
        if (enabled && !notifications.runtimePermissionGranted()) throw FoundationFailure(FailureCode.NOTIFICATIONS_DENIED)
        settings.notifications(enabled); recordBestEffort(ActivityKind.SETTINGS_SAVED)
        allowed.value = notifications.canPost()
    }
    fun permissionDenied() { _notices.tryEmit(message(FailureCode.NOTIFICATIONS_DENIED)); allowed.value = notifications.canPost() }
    fun testNotification() = runAction {
        val posted = settings.current().notificationsEnabled && notifications.postGeneric()
        metadata.record(if (posted) ActivityKind.NOTIFICATION_SENT else ActivityKind.NOTIFICATION_UNAVAILABLE)
        _notices.emit(if (posted) "Test notification handed to Android. Display depends on your system settings." else "Notifications are off or blocked. The rest of NARADA still works.")
    }
    fun retainFolder(uri: Uri, flags: Int) = runAction { folders.retain(uri, flags); _notices.emit("Read permission retained. No files were scanned or copied.") }
    fun forgetFolder(id: String) = runAction { folders.forget(id); _notices.emit("Folder permission removed. Your files were not deleted.") }
    fun requestCheck() = runAction { work.request() }
    fun cancelCheck() = runAction { val cancelled = work.cancel(); _notices.emit(if (cancelled) "Local check cancelled." else "The local check has already finished.") }
    fun clearActivity() = runAction { metadata.clearActivity(); _notices.emit("Local activity cleared. Settings and folder grants are unchanged.") }
    fun inspectReport() = runAction {
        report.value = SupportReport(
            appVersion = BuildConfig.VERSION_NAME, androidApi = Build.VERSION.SDK_INT, generatedAtEpochMillis = System.currentTimeMillis(),
            metadataHealthy = runCatching { metadata.checkDatabase() }.getOrDefault(false),
            privateFilesHealthy = runCatching { files.probe() }.getOrDefault(false),
            retainedFolderCount = runCatching { metadata.grants().size }.getOrNull(),
            notificationsAllowed = notifications.canPost(), lastLocalJobState = runCatching { metadata.currentJob()?.state }.getOrNull(),
        )
    }
    fun dismissReport() { report.value = null }
    fun shareReport(onReady: (Uri) -> Unit) = runAction { report.value?.let { onReady(files.exportReport(it)) } }
    fun externalActionUnavailable() { _notices.tryEmit("Android could not open that action. No data was changed.") }
    companion object {
        fun message(code: FailureCode): String = when (code) {
            FailureCode.INVALID_NAME -> "Use 1–48 characters, without control or direction-override characters."
            FailureCode.GRANT_NOT_PERSISTABLE -> "This provider did not offer lasting read permission. Choose a supported folder."
            FailureCode.GRANT_LIMIT -> "You can retain up to eight folder grants. Remove one before adding another."
            FailureCode.PERMISSION_UNAVAILABLE -> "Folder permission is unavailable. Choose the folder again."
            FailureCode.SCHEDULER_UNAVAILABLE -> "Android could not schedule this check. Its durable status has been preserved."
            FailureCode.NOTIFICATIONS_DENIED -> "Notifications were not enabled. You can keep using NARADA without them."
            FailureCode.SENSITIVE_STORAGE_UNAVAILABLE -> "Protected storage is unavailable. Do not clear app data."
            FailureCode.REPORT_UNAVAILABLE -> "A diagnostic report could not be created. No private content was exported."
            else -> "Local storage could not be read or updated. No reset was attempted. Retry or review diagnostics."
        }
    }
}
