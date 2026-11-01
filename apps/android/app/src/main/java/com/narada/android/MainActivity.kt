package com.narada.android

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import com.narada.android.transfers.ExportPickerBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import android.view.WindowManager
import com.narada.android.connection.*
import com.narada.android.files.FilesViewModel
import com.narada.android.ui.screens.FileActions
import com.narada.android.ui.screens.TransferActions
import com.narada.android.ui.screens.ChatActions
import com.narada.android.chat.ChatCoordinator
import com.narada.android.attachments.AttachmentsCoordinator
import com.narada.android.attachments.AttachmentPickerBinding
import com.narada.android.offline.OfflineCoordinator
import com.narada.android.ui.screens.AttachmentActions
import com.narada.android.ui.screens.OfflineActions
import com.narada.android.transfers.TransfersCoordinator
import com.narada.domain.Destination
import kotlinx.coroutines.launch
import com.narada.android.platform.FolderPermissions
import com.narada.android.platform.PrivateNotifications
import com.narada.android.ui.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: WorkspaceViewModel by viewModels()
    @Inject lateinit var notifications: PrivateNotifications
    private val connections: ConnectionsViewModel by viewModels()
    private val files: FilesViewModel by viewModels()
    @Inject lateinit var transfers: TransfersCoordinator
    @Inject lateinit var chat: ChatCoordinator
    @Inject lateinit var attachments: AttachmentsCoordinator
    @Inject lateinit var offline: OfflineCoordinator
    @Inject lateinit var capture: PairingCapture
    @Inject lateinit var images: PairingImageReader
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = capture.take()
        if (result.resultCode == RESULT_OK && code != null) connections.acceptCode(code)
    }
    private val qrImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try { connections.acceptCode(images.read(uri)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { connections.inputUnavailable() }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.setNotifications(true) else model.permissionDenied()
    }
    // StartActivityForResult preserves RESULT Intent flags; OpenDocumentTree's URI-only contract does not.
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data -> data.data?.let { model.retainFolder(it, data.flags) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Fail closed before the first sensitive frame; a flow/route-driven flag can arrive a frame late.
        // External SAF/provider windows and already exported documents are outside this window's control.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        splash.setKeepOnScreenCondition { model.state.value.load is WorkspaceLoad.Loading }
        if (savedInstanceState == null && intent.action == ACTION_LOCAL_ACTIVITY) model.showNotifications()
        if (savedInstanceState == null && intent.action == ACTION_PRIVATE_CHAT) model.requestedRoute.value = Destination.CHAT
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            val report = model.report.collectAsStateWithLifecycle().value
            val route = model.requestedRoute.collectAsStateWithLifecycle().value
            val connectionState = connections.state.collectAsStateWithLifecycle().value
            val discoveryState = connections.discoveryState.collectAsStateWithLifecycle().value
            val fileState = files.state.collectAsStateWithLifecycle().value
            val transferState = transfers.state.collectAsStateWithLifecycle().value
            val chatState = chat.state.collectAsStateWithLifecycle().value
            val attachmentState = attachments.state.collectAsStateWithLifecycle().value
            val offlineState = offline.state.collectAsStateWithLifecycle().value
            DisposableEffect(attachmentState.picker?.ticketId) {
                val binding = attachmentState.picker?.let { ticket ->
                    AttachmentPickerBinding(activityResultRegistry, ticket, attachments::claimPicker) { id, uri, flags ->
                        lifecycleScope.launch {
                            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }; yield()
                            attachments.pickerResult(id, uri, flags)
                        }
                    }.also { it.launch() }
                }
                onDispose { binding?.close() }
            }
            DisposableEffect(transferState.picker?.exportId) {
                val binding = transferState.picker?.let { ticket ->
                    ExportPickerBinding(activityResultRegistry, ticket, transfers::claimPicker) { id, tree, flags ->
                        lifecycleScope.launch {
                            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                            yield() // onStart must publish the foreground fence before consuming a returned grant.
                            transfers.pickerResult(id, tree, flags)
                        }
                    }.also { it.launch() }
                }
                onDispose { binding?.close() }
            }
            NaradaTheme {
                WorkspaceApp(
                    state = state, report = report, notices = model.notices, requestedRoute = route,
                    connections = connectionState, discovery = discoveryState, files = fileState, transfers = transferState,
                    attachments = attachmentState, attachmentActions = AttachmentActions(visible = attachments::visible, select = attachments::select, reload = attachments::reload,
                        import = attachments::prepareImport, receive = attachments::receive, run = attachments::run, stop = attachments::stop,
                        cancel = attachments::cancel, remove = attachments::remove, export = attachments::prepareExport,
                        dismissExport = attachments::dismissExport, forgetExport = attachments::forgetExport),
                    offline = offlineState, offlineActions = OfflineActions(visible = offline::visible, reload = offline::reload, select = offline::select,
                        refresh = offline::refreshMetadata, stop = offline::stop, pin = offline::pin, refreshPin = offline::refreshPin,
                        adopt = offline::adopt, release = offline::release, unpin = offline::unpin, forget = offline::forgetMetadata,
                        openTransfers = { model.requestedRoute.value = Destination.TRANSFERS }),
                    chat = chatState, chatActions = ChatActions(visible = chat::visible, reload = chat::reload, select = chat::select, enable = chat::enable,
                        saveDraft = chat::saveDraft, send = chat::send, sync = chat::sync, stop = chat::stop, retry = chat::retry, cancel = chat::cancel, markRead = chat::markRead),
                    transferActions = TransferActions(
                        visible = transfers::visible, reload = transfers::reload, pause = transfers::pause, pauseAll = transfers::pauseAll,
                        resume = transfers::resume, cancel = transfers::cancel, remove = transfers::remove, finish = transfers::finish,
                        export = transfers::prepareExport, stopExport = transfers::stopExport, dismissExport = transfers::dismissExport, forgetExport = transfers::forgetExport,
                    ),
                    fileActions = FileActions(
                        visible = files.browser::visible, roots = files.browser::roots, refresh = files.browser::refresh, back = files.browser::back,
                        openRoot = files.browser::openRoot, directory = files.browser::openDirectory, breadcrumb = files.browser::breadcrumb,
                        search = files.browser::search, sort = files.browser::sort, trash = files.browser::trash,
                        next = files.browser::next, previous = files.browser::previous, select = files.browser::select,
                        preview = files.browser::preview, close = files.browser::closeSelection,
                        downloadAvailable = connectionState.document?.peers?.find { it.id == connectionState.document.preferredPeer }?.remoteOperations?.contains("transfers.v1") == true,
                        download = { id, version, confirmed ->
                            try { val choice = files.browser.downloadChoice(id, version); transfers.enqueue(choice, confirmed); files.browser.closeSelection(); model.requestedRoute.value = Destination.TRANSFERS }
                            catch (e: Exception) { transfers.inputFailure(e); model.requestedRoute.value = Destination.TRANSFERS }
                        },
                        mutate = files.browser::mutate, retry = files.browser::retry, dismiss = files.browser::dismiss,
                    ),
                    connectionActions = ConnectionActions(
                        available = true, initialize = connections::initialize, enable = connections::enable,
                        acceptCode = connections::acceptCode, discardCode = connections::discardCode, request = connections::requestPairing,
                        scanQr = { safely { scanner.launch(Intent(this, ScanPairingActivity::class.java)) } },
                        importQr = { safely { qrImage.launch(arrayOf("image/png", "image/jpeg", "image/webp")) } },
                        wifiSettings = { safely { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                        finish = connections::finish, recover = connections::recover, reconnect = connections::reconnect,
                        revoke = connections::revoke, discover = connections::scanNetwork, stopDiscovery = connections::stopDiscovery, reload = connections::reload,
                    ),
                    actions = WorkspaceActions(
                        completeSetup = model::completeSetup, saveName = model::saveName, editName = model::editName, discardName = model::discardName,
                        notifications = { enabled -> if (!enabled) model.setNotifications(false) else requestNotifications() },
                        reduceMotion = model::setReduceMotion,
                        testNotification = model::testNotification,
                        pickFolder = { safely { folderPicker.launch(FolderPermissions.pickerIntent()) } },
                        forgetFolder = model::forgetFolder, requestCheck = model::requestCheck, cancelCheck = model::cancelCheck,
                        clearActivity = model::clearActivity, inspectReport = model::inspectReport, dismissReport = model::dismissReport,
                        shareReport = { model.shareReport { uri -> safely {
                            val send = Intent(Intent.ACTION_SEND).setType("application/json")
                                .putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = ClipData.newRawUri("NARADA support report", uri) }
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(send, getString(R.string.share_report)))
                        } } },
                        systemNotifications = { safely { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } },
                        retry = model::retryRead, routeConsumed = model::routeConsumed,
                    ),
                )
            }
        }
    }
    override fun onStart() { super.onStart(); connections.foregroundChanged(true) }
    override fun onStop() { connections.foregroundChanged(false); super.onStop() }
    override fun onResume() { super.onResume(); model.onForeground() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.action == ACTION_LOCAL_ACTIVITY) model.showNotifications(); if (intent.action == ACTION_PRIVATE_CHAT) model.requestedRoute.value = Destination.CHAT }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifications.runtimePermissionGranted()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else model.setNotifications(true)
    }
    private inline fun safely(block: () -> Unit) { try { block() } catch (_: Exception) { model.externalActionUnavailable() } }
    companion object { const val ACTION_PRIVATE_CHAT = "com.narada.android.PRIVATE_CHAT"; const val ACTION_LOCAL_ACTIVITY = "com.narada.android.LOCAL_ACTIVITY" }
}
