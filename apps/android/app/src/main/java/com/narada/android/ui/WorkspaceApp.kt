package com.narada.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.navigation
import com.narada.android.R
import com.narada.android.connection.*
import com.narada.android.ui.screens.*
import com.narada.domain.*
import com.narada.connection.FileBrowserState
import com.narada.android.transfers.TransfersUiState
import com.narada.android.chat.ChatUiState
import com.narada.android.attachments.AttachmentsUiState
import com.narada.android.offline.OfflineUiState
import kotlinx.coroutines.flow.Flow

data class WorkspaceActions(
    val completeSetup: (String) -> Unit, val saveName: (String) -> Unit,
    val editName: (String) -> Unit, val discardName: () -> Unit,
    val notifications: (Boolean) -> Unit, val reduceMotion: (Boolean) -> Unit,
    val testNotification: () -> Unit, val pickFolder: () -> Unit, val forgetFolder: (String) -> Unit,
    val requestCheck: () -> Unit, val cancelCheck: () -> Unit, val clearActivity: () -> Unit,
    val inspectReport: () -> Unit, val dismissReport: () -> Unit, val shareReport: () -> Unit,
    val systemNotifications: () -> Unit, val retry: () -> Unit, val routeConsumed: () -> Unit,
)

@Composable
fun WorkspaceApp(state: WorkspaceUiState, report: SupportReport?, notices: Flow<String>, requestedRoute: Destination?, actions: WorkspaceActions, connections: ConnectionsUiState = ConnectionsUiState(loading = false), discovery: DiscoveryState = DiscoveryState(), connectionActions: ConnectionActions = ConnectionActions(), files: FileBrowserState = FileBrowserState(), fileActions: FileActions = FileActions(), transfers: TransfersUiState = TransfersUiState(loading = false), transferActions: TransferActions = TransferActions(), chat: ChatUiState = ChatUiState(), chatActions: ChatActions = ChatActions(), attachments: AttachmentsUiState = AttachmentsUiState(), attachmentActions: AttachmentActions = AttachmentActions(), offline: OfflineUiState = OfflineUiState(), offlineActions: OfflineActions = OfflineActions()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notices) { notices.collect { snackbar.showSnackbar(it) } }
    when (val load = state.load) {
        WorkspaceLoad.Loading -> Box(Modifier.fillMaxSize().background(NaradaColors.Background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp); Text("Reading local settings…")
            }
        }
        is WorkspaceLoad.Failed -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                TileIcon(R.drawable.ic_lock); Text("Local data needs\nyour attention.", style = MaterialTheme.typography.headlineLarge)
                Caption("Settings or metadata could not be opened. NARADA did not reset preferences, delete a damaged database or create replacement sensitive keys.")
                InfoPanel("Keep your app data", "Do not uninstall or clear app data if you need to preserve it. Retry only reopens storage; it does not wipe anything.")
                PrimaryAction("Retry without resetting", actions.retry, Modifier.fillMaxWidth(), enabled = !state.busy)
                OutlinedButton(actions.inspectReport, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) { Text("Review a diagnostic report") }
            }
        }
        is WorkspaceLoad.Ready -> if (!load.settings.setupCompleted) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
                Box(Modifier.padding(padding)) { OnboardingScreen(load.settings.deviceName, state.busy, actions.completeSetup) }
            }
        } else NativeShell(load, state, snackbar, requestedRoute, actions, connections, discovery, connectionActions, files, fileActions, transfers, transferActions, chat, chatActions, attachments, attachmentActions, offline, offlineActions)
    }
    if (report != null) AlertDialog(
        onDismissRequest = actions.dismissReport,
        title = { Text("Review support report") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("This is the complete content that will be shared. No private labels or raw logs are included.")
            SelectionContainer { Text(report.json(), Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(actions.shareReport, enabled = !state.busy) { Text("Share JSON") } },
        dismissButton = { TextButton(actions.dismissReport) { Text("Keep private") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeShell(data: WorkspaceLoad.Ready, state: WorkspaceUiState, snackbar: SnackbarHostState, requestedRoute: Destination?, actions: WorkspaceActions, connections: ConnectionsUiState, discovery: DiscoveryState, connectionActions: ConnectionActions, files: FileBrowserState, fileActions: FileActions, transfers: TransfersUiState, transferActions: TransferActions, chat: ChatUiState, chatActions: ChatActions, attachments: AttachmentsUiState, attachmentActions: AttachmentActions, offline: OfflineUiState, offlineActions: OfflineActions) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = Destination.fromRoute(entry?.destination?.route)
    LaunchedEffect(current) { if (current != Destination.CHAT) { chatActions.visible(false); attachmentActions.visible(false) }; if (current !in setOf(Destination.FILES, Destination.SYNC, Destination.OFFLINE)) offlineActions.visible(false) }
    val selected = if (current in Destination.primary) current else Destination.MORE
    val dirty = current == Destination.SETTINGS && state.draftName != null && state.draftName != data.settings.deviceName
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    fun go(route: String) {
        when {
            route == BACK -> nav.popBackStack()
            Destination.primary.any { it.route == route } -> nav.navigate(if (route == Destination.MORE.route) MORE_GRAPH else route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
            else -> nav.navigate(route) { launchSingleTop = true }
        }
    }
    fun request(route: String) { if (dirty) pending = route else go(route) }
    val navigate: (Destination) -> Unit = { request(it.route) }
    LaunchedEffect(requestedRoute) {
        requestedRoute?.let { request(it.route); actions.routeConsumed() }
    }
    Scaffold(
        containerColor = NaradaColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (current == Destination.HOME) Text("NARADA", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    else Text(current.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { if (current !in Destination.primary) IconButton({ request(BACK) }, Modifier.size(48.dp)) { Glyph(R.drawable.ic_back, description = "Back") } },
                actions = {
                    if (current == Destination.HOME) {
                        IconButton({ navigate(Destination.SETTINGS) }, Modifier.size(48.dp)) { Glyph(R.drawable.ic_settings, description = "Open settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NaradaColors.Background),
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = NaradaColors.Border)
                NavigationBar(
                    modifier = Modifier.heightIn(min = if (LocalDensity.current.fontScale > 1.4f) 116.dp else 80.dp),
                    containerColor = Color.White, tonalElevation = 0.dp,
                ) {
                    Destination.primary.forEach { destination ->
                        NavigationBarItem(
                            selected = selected == destination,
                            onClick = { navigate(destination) },
                            icon = { Glyph(destination.icon()) },
                            label = { Text(destination.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.testTag("tab_${destination.route}"),
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = NaradaColors.Ink, selectedTextColor = NaradaColors.Ink, indicatorColor = NaradaColors.Soft, unselectedIconColor = NaradaColors.Muted, unselectedTextColor = NaradaColors.Muted),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav, startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { if (data.settings.reduceMotion) EnterTransition.None else fadeIn(tween(180)) },
            exitTransition = { if (data.settings.reduceMotion) ExitTransition.None else fadeOut(tween(100)) },
            popEnterTransition = { if (data.settings.reduceMotion) EnterTransition.None else fadeIn(tween(180)) },
            popExitTransition = { if (data.settings.reduceMotion) ExitTransition.None else fadeOut(tween(100)) },
        ) {
            composable(Destination.HOME.route) { HomeScreen(data, navigate, connections) }
            composable(Destination.FILES.route) { FilesScreen(files, connections, fileActions, offline, offlineActions) { navigate(Destination.DEVICES) } }
            composable(Destination.TRANSFERS.route) { TransfersScreen(transfers, transferActions) { navigate(Destination.FILES) } }
            composable(Destination.CHAT.route) { ChatScreen(chat, connections, chatActions, attachments, attachmentActions) { navigate(Destination.DEVICES) } }
            navigation(startDestination = Destination.MORE.route, route = MORE_GRAPH) {
                composable(Destination.MORE.route) { MoreScreen(data.settings.deviceName, navigate) }
                listOf(Destination.SYNC, Destination.OFFLINE).forEach { destination -> composable(destination.route) { OfflineScreen(offline, connections, offlineActions) } }
                composable(Destination.DEVICES.route) { ConnectionDevicesScreen(data.settings.deviceName, connections, discovery, connectionActions) }
                composable(Destination.NOTIFICATIONS.route) { NotificationsScreen(data, state.notificationsAllowed, state.busy, actions) }
                composable(Destination.SETTINGS.route) { SettingsScreen(data, state.busy, state.notificationsAllowed, actions, navigate, state.draftName) }
                composable(Destination.HELP.route) { HelpScreen(navigate) }
                composable(Destination.STORAGE.route) { StorageScreen(data, state.busy, actions) }
                composable(Destination.DIAGNOSTICS.route) { DiagnosticsScreen(data, state.busy, actions) }
                composable(Destination.APPEARANCE.route) { AppearanceScreen(data, state.busy, actions) }
            }
        }
    }
    BackHandler(enabled = dirty) { pending = BACK }
    if (pending != null) AlertDialog(
        onDismissRequest = { pending = null }, title = { Text("Discard the unsaved name?") },
        text = { Text("Your saved device name will stay unchanged. You can keep editing or discard this draft.") },
        confirmButton = { TextButton({ val route = pending; pending = null; actions.discardName(); route?.let(::go) }) { Text("Discard changes") } },
        dismissButton = { TextButton({ pending = null }) { Text("Keep editing") } },
    )
}
private const val MORE_GRAPH = "more_section"
private const val BACK = "__back__"
