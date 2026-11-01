//! Bounded, versioned owner RPC. Host paths are accepted only by explicit root registration.
use serde::{Deserialize, Serialize};
mod connections;
mod content;
mod files;
mod transfers;
pub use connections::*;
pub use content::*;
pub use files::*;
pub use transfers::*;

pub const PROTOCOL_VERSION: u16 = 1;
pub const MAX_FRAME_BYTES: usize = 65_536;
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Settings {
    pub device_name: String,
    pub close_to_tray: bool,
    pub notifications_enabled: bool,
    pub hide_notification_previews: bool,
    pub reduce_motion: bool,
    pub setup_completed: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            device_name: "My workspace".into(),
            close_to_tray: true,
            notifications_enabled: true,
            hide_notification_previews: true,
            reduce_motion: false,
            setup_completed: false,
        }
    }
}
impl Settings {
    pub fn validated(mut self) -> Result<Self, RpcError> {
        self.device_name = self.device_name.trim().to_string();
        let len = self.device_name.chars().count();
        if len == 0 || len > 48 || self.device_name.chars().any(char::is_control) {
            return Err(RpcError::new(
                "INVALID_SETTINGS",
                "Use a workspace name of 1–48 characters without control characters.",
            ));
        }
        Ok(self)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "type",
    content = "payload",
    rename_all = "snake_case",
    deny_unknown_fields
)]
pub enum Command {
    GetSnapshot,
    SaveSettings(Settings),
    RunDiagnostics,
    ClearActivity,
    ConfigureConnections(ConnectionConfig),
    StartPairing,
    CancelPairing(InvitationRef),
    ApprovePairing(ApprovePairing),
    RejectPairing(AttemptRef),
    JoinPairing(JoinPairing),
    CancelJoin(AttemptRef),
    UpdatePeerPermissions(UpdatePeerPermissions),
    RevokePeer(PeerRevision),
    ReconnectPeer(PeerRef),
    SetPeerEndpoint(PeerEndpoint),
    DiscoverPeers,
    ListRoots,
    RegisterRoot(RegisterRoot),
    UpdateRootPolicy(RootPolicy),
    RemoveRoot(RootRevision),
    ReindexRoot(RootRef),
    FileQuery(FileQuery),
    FileMutation(FileMutation),
    RemoteFileQuery(RemoteFileQuery),
    RemoteFileMutation(RemoteFileMutation),
    ListTransfers,
    StartTransfer(StartTransfer),
    TransferControl(TransferControl),
    CreateTransferOffer(CreateTransferOffer),
    PollTransferOffers,
    AcceptTransferOffer(AcceptTransferOffer),
    CleanupTransfers,
    ContentStatus,
    ListConversations,
    ChatHistory(ChatHistory),
    SendChat(SendChat),
    SaveChatDraft(ChatDraft),
    MarkChatRead(PeerRef),
    ChatControl(ChatControl),
    FetchAttachment(AttachmentRef),
    PauseAttachment(AttachmentRef),
    ExportAttachment(ExportAttachment),
    CleanupContent,
    SyncQuery(SyncRequest),
    ListPins,
    CreatePin(CreatePin),
    PinControl(PinControl),
    RefreshSync,
    SupportBundle,
    // Only used by the native owner process on explicit application exit.
    Shutdown,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub version: u16,
    pub id: String,
    pub command: Command,
}
impl Request {
    pub fn new(command: Command) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            id: uuid::Uuid::new_v4().to_string(),
            command,
        }
    }
    pub fn validate(&self) -> Result<(), RpcError> {
        if self.version != PROTOCOL_VERSION {
            return Err(RpcError::new(
                "PROTOCOL_INCOMPATIBLE",
                "This local protocol version is not supported.",
            ));
        }
        if self.id.len() != 36 || uuid::Uuid::parse_str(&self.id).is_err() {
            return Err(RpcError::new(
                "INVALID_REQUEST",
                "A valid request identifier is required.",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, thiserror::Error)]
#[error("{message}")]
pub struct RpcError {
    pub code: String,
    pub message: String,
}
impl RpcError {
    pub fn new(code: &str, message: &str) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
        }
    }
    pub fn storage() -> Self {
        Self::new(
            "STORAGE_UNAVAILABLE",
            "Local storage is unavailable. Your existing data has not been reset.",
        )
    }
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Response {
    pub version: u16,
    pub id: String,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<RpcError>,
}
impl Response {
    pub fn result(id: String, result: Result<serde_json::Value, RpcError>) -> Self {
        match result {
            Ok(data) => Self {
                version: PROTOCOL_VERSION,
                id,
                ok: true,
                data: Some(data),
                error: None,
            },
            Err(error) => Self {
                version: PROTOCOL_VERSION,
                id,
                ok: false,
                data: None,
                error: Some(error),
            },
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Activity {
    pub id: i64,
    pub kind: String,
    pub title: String,
    pub detail: String,
    pub created_at: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Check {
    pub id: String,
    pub label: String,
    pub status: String,
    pub detail: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Diagnostics {
    pub checked_at: String,
    pub duration_ms: u128,
    pub checks: Vec<Check>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentStatus {
    pub version: String,
    pub platform: String,
    pub started_at: String,
    pub uptime_seconds: u64,
    pub ipc_transport: String,
    pub peer_network_enabled: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageStatus {
    pub schema_version: i64,
    pub database_bytes: u64,
    pub journal_mode: String,
    pub scope: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub settings: Settings,
    pub agent: AgentStatus,
    pub storage: StorageStatus,
    pub activity: Vec<Activity>,
    pub last_diagnostics: Option<Diagnostics>,
    pub paired_devices: u32,
    pub shared_roots: u32,
    pub active_transfers: u32,
    #[serde(default)]
    pub connections: Option<ConnectionsSnapshot>,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn settings_trim_and_validate() {
        let s = Settings {
            device_name: "  Studio laptop  ".into(),
            ..Default::default()
        }
        .validated()
        .unwrap();
        assert_eq!(s.device_name, "Studio laptop");
    }
    #[test]
    fn invalid_names_are_rejected() {
        for name in [
            "".to_owned(),
            " ".to_owned(),
            "a".repeat(49),
            "bad\nname".into(),
        ] {
            assert!(Settings {
                device_name: name,
                ..Default::default()
            }
            .validated()
            .is_err());
        }
    }
    #[test]
    fn unicode_names_count_characters() {
        assert!(Settings {
            device_name: "నారద".into(),
            ..Default::default()
        }
        .validated()
        .is_ok());
    }
    #[test]
    fn no_implicit_privacy_downgrade() {
        assert!(Settings::default().hide_notification_previews);
    }
    #[test]
    fn unknown_commands_are_rejected() {
        assert!(serde_json::from_str::<Command>(
            r#"{"type":"read_arbitrary_file","payload":"C:/"}"#
        )
        .is_err());
    }
    #[test]
    fn unknown_setting_fields_are_rejected() {
        let mut value = serde_json::to_value(Settings::default()).unwrap();
        value["enable_lan"] = true.into();
        assert!(serde_json::from_value::<Settings>(value).is_err());
    }
    #[test]
    fn version_and_id_are_checked() {
        let mut req = Request::new(Command::GetSnapshot);
        assert!(req.validate().is_ok());
        req.version = 99;
        assert!(req.validate().is_err());
        req.version = 1;
        req.id = "forged".into();
        assert!(req.validate().is_err());
    }
}
