#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use narada_protocol::*;
use std::{
    path::PathBuf,
    sync::atomic::{AtomicBool, Ordering},
};
use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    Manager,
};
struct AppState {
    data_dir: PathBuf,
    close_to_tray: AtomicBool,
}
#[tauri::command]
async fn get_snapshot(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    let value = narada_ipc::call(&state.data_dir, Command::GetSnapshot).await?;
    state.close_to_tray.store(
        value["settings"]["close_to_tray"].as_bool().unwrap_or(true),
        Ordering::Relaxed,
    );
    Ok(value)
}
#[tauri::command]
async fn save_settings(
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<serde_json::Value, RpcError> {
    let value = narada_ipc::call(&state.data_dir, Command::SaveSettings(settings)).await?;
    state.close_to_tray.store(
        value["close_to_tray"].as_bool().unwrap_or(true),
        Ordering::Relaxed,
    );
    Ok(value)
}
#[tauri::command]
async fn run_diagnostics(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RunDiagnostics).await
}
#[tauri::command]
async fn clear_activity(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ClearActivity).await
}
#[tauri::command]
async fn configure_connections(
    state: tauri::State<'_, AppState>,
    payload: ConnectionConfig,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ConfigureConnections(payload)).await
}
#[tauri::command]
async fn cancel_pairing(
    state: tauri::State<'_, AppState>,
    payload: InvitationRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::CancelPairing(payload)).await
}
#[tauri::command]
async fn approve_pairing(
    state: tauri::State<'_, AppState>,
    payload: ApprovePairing,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ApprovePairing(payload)).await
}
#[tauri::command]
async fn reject_pairing(
    state: tauri::State<'_, AppState>,
    payload: AttemptRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RejectPairing(payload)).await
}
#[tauri::command]
async fn join_pairing(
    state: tauri::State<'_, AppState>,
    payload: JoinPairing,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::JoinPairing(payload)).await
}
#[tauri::command]
async fn cancel_join(
    state: tauri::State<'_, AppState>,
    payload: AttemptRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::CancelJoin(payload)).await
}
#[tauri::command]
async fn update_peer_permissions(
    state: tauri::State<'_, AppState>,
    payload: UpdatePeerPermissions,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::UpdatePeerPermissions(payload)).await
}
#[tauri::command]
async fn revoke_peer(
    state: tauri::State<'_, AppState>,
    payload: PeerRevision,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RevokePeer(payload)).await
}
#[tauri::command]
async fn reconnect_peer(
    state: tauri::State<'_, AppState>,
    payload: PeerRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ReconnectPeer(payload)).await
}
#[tauri::command]
async fn set_peer_endpoint(
    state: tauri::State<'_, AppState>,
    payload: PeerEndpoint,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::SetPeerEndpoint(payload)).await
}
#[tauri::command]
async fn start_pairing(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::StartPairing).await
}
#[tauri::command]
async fn discover_peers(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::DiscoverPeers).await
}
#[tauri::command]
async fn list_roots(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ListRoots).await
}
#[tauri::command]
async fn register_root(
    state: tauri::State<'_, AppState>,
    payload: RegisterRoot,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RegisterRoot(payload)).await
}
#[tauri::command]
async fn update_root_policy(
    state: tauri::State<'_, AppState>,
    payload: RootPolicy,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::UpdateRootPolicy(payload)).await
}
#[tauri::command]
async fn remove_root(
    state: tauri::State<'_, AppState>,
    payload: RootRevision,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RemoveRoot(payload)).await
}
#[tauri::command]
async fn reindex_root(
    state: tauri::State<'_, AppState>,
    payload: RootRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ReindexRoot(payload)).await
}
#[tauri::command]
async fn file_query(
    state: tauri::State<'_, AppState>,
    payload: FileQuery,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::FileQuery(payload)).await
}
#[tauri::command]
async fn file_mutation(
    state: tauri::State<'_, AppState>,
    payload: FileMutation,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::FileMutation(payload)).await
}
#[tauri::command]
async fn remote_file_query(
    state: tauri::State<'_, AppState>,
    payload: RemoteFileQuery,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RemoteFileQuery(payload)).await
}
#[tauri::command]
async fn remote_file_mutation(
    state: tauri::State<'_, AppState>,
    payload: RemoteFileMutation,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RemoteFileMutation(payload)).await
}
#[tauri::command]
async fn list_transfers(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ListTransfers).await
}
#[tauri::command]
async fn start_transfer(
    state: tauri::State<'_, AppState>,
    payload: StartTransfer,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::StartTransfer(payload)).await
}
#[tauri::command]
async fn transfer_control(
    state: tauri::State<'_, AppState>,
    payload: TransferControl,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::TransferControl(payload)).await
}
#[tauri::command]
async fn create_transfer_offer(
    state: tauri::State<'_, AppState>,
    payload: CreateTransferOffer,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::CreateTransferOffer(payload)).await
}
#[tauri::command]
async fn poll_transfer_offers(
    state: tauri::State<'_, AppState>,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::PollTransferOffers).await
}
#[tauri::command]
async fn accept_transfer_offer(
    state: tauri::State<'_, AppState>,
    payload: AcceptTransferOffer,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::AcceptTransferOffer(payload)).await
}
#[tauri::command]
async fn cleanup_transfers(
    state: tauri::State<'_, AppState>,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::CleanupTransfers).await
}
#[tauri::command]
async fn content_status(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ContentStatus).await
}
#[tauri::command]
async fn list_conversations(
    state: tauri::State<'_, AppState>,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ListConversations).await
}
#[tauri::command]
async fn chat_history(
    state: tauri::State<'_, AppState>,
    payload: ChatHistory,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ChatHistory(payload)).await
}
#[tauri::command]
async fn send_chat(
    state: tauri::State<'_, AppState>,
    payload: SendChat,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::SendChat(payload)).await
}
#[tauri::command]
async fn save_chat_draft(
    state: tauri::State<'_, AppState>,
    payload: ChatDraft,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::SaveChatDraft(payload)).await
}
#[tauri::command]
async fn mark_chat_read(
    state: tauri::State<'_, AppState>,
    payload: PeerRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::MarkChatRead(payload)).await
}
#[tauri::command]
async fn chat_control(
    state: tauri::State<'_, AppState>,
    payload: ChatControl,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ChatControl(payload)).await
}
#[tauri::command]
async fn fetch_attachment(
    state: tauri::State<'_, AppState>,
    payload: AttachmentRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::FetchAttachment(payload)).await
}
#[tauri::command]
async fn pause_attachment(
    state: tauri::State<'_, AppState>,
    payload: AttachmentRef,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::PauseAttachment(payload)).await
}
#[tauri::command]
async fn export_attachment(
    state: tauri::State<'_, AppState>,
    payload: ExportAttachment,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ExportAttachment(payload)).await
}
#[tauri::command]
async fn cleanup_content(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::CleanupContent).await
}
#[tauri::command]
async fn sync_query(
    state: tauri::State<'_, AppState>,
    payload: SyncRequest,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::SyncQuery(payload)).await
}
#[tauri::command]
async fn list_pins(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::ListPins).await
}
#[tauri::command]
async fn create_pin(
    state: tauri::State<'_, AppState>,
    payload: CreatePin,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::CreatePin(payload)).await
}
#[tauri::command]
async fn pin_control(
    state: tauri::State<'_, AppState>,
    payload: PinControl,
) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::PinControl(payload)).await
}
#[tauri::command]
async fn refresh_sync(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::RefreshSync).await
}
#[tauri::command]
async fn support_bundle(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, RpcError> {
    narada_ipc::call(&state.data_dir, Command::SupportBundle).await
}
#[tauri::command]
async fn choose_root() -> Result<Option<String>, RpcError> {
    #[cfg(windows)]
    {
        let selected = tauri::async_runtime::spawn_blocking(|| {
            rfd::FileDialog::new()
                .set_title("Choose an approved NARADA folder")
                .pick_folder()
        })
        .await
        .map_err(|_| RpcError::new("PICKER_FAILED", "The native folder picker did not finish."))?;
        selected
            .map(|p| {
                p.into_os_string().into_string().map_err(|_| {
                    RpcError::new("UNSUPPORTED_PATH", "Choose a folder with a Unicode path.")
                })
            })
            .transpose()
    }
    #[cfg(not(windows))]
    {
        Err(RpcError::new("NATIVE_WINDOWS_REQUIRED","The native picker is available on Windows. The development preview requires an explicit agent-host folder path."))
    }
}
fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            get_snapshot,
            save_settings,
            run_diagnostics,
            clear_activity,
            configure_connections,
            cancel_pairing,
            approve_pairing,
            reject_pairing,
            join_pairing,
            cancel_join,
            update_peer_permissions,
            revoke_peer,
            reconnect_peer,
            set_peer_endpoint,
            start_pairing,
            discover_peers,
            list_roots,
            register_root,
            update_root_policy,
            remove_root,
            reindex_root,
            file_query,
            file_mutation,
            remote_file_query,
            remote_file_mutation,
            list_transfers,
            start_transfer,
            transfer_control,
            create_transfer_offer,
            poll_transfer_offers,
            accept_transfer_offer,
            cleanup_transfers,
            content_status,
            list_conversations,
            chat_history,
            send_chat,
            save_chat_draft,
            mark_chat_read,
            chat_control,
            fetch_attachment,
            pause_attachment,
            export_attachment,
            cleanup_content,
            sync_query,
            list_pins,
            create_pin,
            pin_control,
            refresh_sync,
            support_bundle,
            choose_root,
        ])
        .setup(|app| {
            let data_dir = directories::ProjectDirs::from("com", "narada", "NARADA")
                .ok_or("User data directory unavailable")?
                .data_local_dir()
                .to_owned();
            let running =
                tauri::async_runtime::block_on(narada_ipc::call(&data_dir, Command::GetSnapshot));
            let close_to_tray = running
                .as_ref()
                .ok()
                .and_then(|v| v["settings"]["close_to_tray"].as_bool())
                .unwrap_or(true);
            if running.is_err() {
                let agent = if cfg!(debug_assertions) {
                    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                        .join("../../../target/debug")
                        .join(if cfg!(windows) {
                            "narada-agent.exe"
                        } else {
                            "narada-agent"
                        })
                } else {
                    std::env::current_exe()?
                        .parent()
                        .ok_or("Executable directory unavailable")?
                        .join(if cfg!(windows) {
                            "narada-agent.exe"
                        } else {
                            "narada-agent"
                        })
                };
                if agent.is_file() {
                    let mut command = std::process::Command::new(agent);
                    command.arg("--data-dir").arg(&data_dir);
                    #[cfg(windows)]
                    {
                        use std::os::windows::process::CommandExt;
                        command.creation_flags(0x08000000);
                    }
                    // Independent user process. Closing the UI does not discard agent state.
                    command.spawn()?;
                }
            }
            app.manage(AppState {
                data_dir,
                close_to_tray: AtomicBool::new(close_to_tray),
            });
            let open = MenuItem::with_id(app, "open", "Open NARADA", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit NARADA", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            let mut tray = TrayIconBuilder::new()
                .tooltip("NARADA — local workspace")
                .menu(&menu)
                .show_menu_on_left_click(true)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.unminimize();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => {
                        let app = app.clone();
                        tauri::async_runtime::spawn(async move {
                            let dir = app.state::<AppState>().data_dir.clone();
                            let _ = narada_ipc::call(&dir, Command::Shutdown).await;
                            app.exit(0);
                        });
                    }
                    _ => {}
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let state = window.state::<AppState>();
                if state.close_to_tray.load(Ordering::Relaxed) {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("NARADA native application failed");
}
