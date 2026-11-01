//! Owner-only messaging/offline controls. Secret fields are never Debug printed.
use crate::{file_id, transfer_hash, RpcError, TransferDestination, TransferSource};
use serde::{Deserialize, Serialize};
pub const CONTENT_PROTOCOL_VERSION: u16 = 1;
pub const MAX_CHAT_TEXT: usize = 4096;
pub const MAX_ATTACHMENT_BYTES: u64 = 8 * 1024 * 1024;
pub const MAX_CIPHER_BYTES: u64 = MAX_ATTACHMENT_BYTES + 65536;
pub const MAX_CONTENT_MESSAGES: usize = 128;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChatHistory {
    pub peer_id: String,
    pub before: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SendChat {
    pub message_id: String,
    pub peer_id: String,
    pub text: String,
    pub attachment: Option<TransferSource>,
}
impl std::fmt::Debug for SendChat {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SendChat([private content redacted])")
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChatDraft {
    pub peer_id: String,
    pub text: String,
}
impl std::fmt::Debug for ChatDraft {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ChatDraft([private content redacted])")
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChatControl {
    pub message_id: String,
    pub action: ChatControlAction,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChatControlAction {
    Retry,
    Cancel,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttachmentRef {
    pub message_id: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExportAttachment {
    pub message_id: String,
    pub operation_id: String,
    pub destination: TransferDestination,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreatePin {
    pub pin_id: String,
    pub peer_id: String,
    pub source: TransferSource,
    pub destination: TransferDestination,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PinControl {
    pub pin_id: String,
    pub action: PinAction,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PinAction {
    Refresh,
    Check,
    Pause,
    Resume,
    Unpin,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum SyncRequest {
    Snapshot {
        root_id: String,
        cursor: Option<String>,
    },
    Changes {
        root_id: String,
        cursor: String,
    },
}
impl SyncRequest {
    pub fn root(&self) -> &str {
        match self {
            Self::Snapshot { root_id, .. } | Self::Changes { root_id, .. } => root_id,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SyncEntry {
    pub object_id: String,
    pub parent_id: Option<String>,
    pub name: String,
    pub relative_path: String,
    pub kind: String,
    pub size: String,
    pub version: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SyncEvent {
    pub sequence: u64,
    pub kind: String,
    pub object_id: String,
    pub entry: Option<SyncEntry>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SyncPage {
    pub root_id: String,
    pub mode: String,
    pub entries: Vec<SyncEntry>,
    pub events: Vec<SyncEvent>,
    pub next_cursor: Option<String>,
    pub changes_cursor: Option<String>,
    pub fence: u64,
}
pub fn chat_peer(id: &str) -> Result<(), RpcError> {
    transfer_hash(id)
}
pub fn chat_text(text: &str, empty: bool) -> Result<(), RpcError> {
    if text.len() > MAX_CHAT_TEXT
        || (!empty && text.trim().is_empty())
        || text
            .chars()
            .any(|c| c.is_control() && !matches!(c, '\n' | '\t'))
    {
        return Err(RpcError::new(
            "INVALID_MESSAGE",
            "Use at most 4096 UTF-8 bytes; non-display controls are refused.",
        ));
    }
    Ok(())
}
impl SendChat {
    pub fn validate(&self) -> Result<(), RpcError> {
        file_id(&self.message_id)?;
        chat_peer(&self.peer_id)?;
        chat_text(&self.text, self.attachment.is_some())?;
        if let Some(s) = &self.attachment {
            s.validate()?;
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn chat_text_is_byte_bounded_and_control_safe() {
        assert!(chat_text("hello\nworld", false).is_ok());
        assert!(chat_text("\0", false).is_err());
        assert!(chat_text("  ", false).is_err());
        assert!(chat_text(&"é".repeat(2049), false).is_err());
    }
    #[test]
    fn private_fields_are_redacted_from_debug() {
        let d = ChatDraft {
            peer_id: "a".repeat(64),
            text: "secret sentinel".into(),
        };
        assert!(!format!("{d:?}").contains("sentinel"));
    }
    #[test]
    fn sync_request_rejects_unknown_paths() {
        assert!(serde_json::from_str::<SyncRequest>(
            r#"{"kind":"snapshot","root_id":"x","cursor":null,"path":"/etc"}"#
        )
        .is_err());
    }
}

#[cfg(test)]
mod corpus_tests {
    use super::*;
    #[test]
    fn strict_owner_corpus_and_deterministic_byte_mutations_never_panic() {
        let seeds: [&[u8]; 2] = [
            include_bytes!("../../../tests/corpus/owner/unknown-field.json"),
            include_bytes!("../../../tests/corpus/owner/unknown-command.json"),
        ];
        for seed in seeds {
            assert!(serde_json::from_slice::<crate::Request>(seed).is_err());
            for index in 0..seed.len() {
                for byte in [0u8, 34, 92, 127, 255] {
                    let mut input = seed.to_vec();
                    input[index] = byte;
                    let _ = serde_json::from_slice::<crate::Request>(&input);
                    let _ = serde_json::from_slice::<SyncRequest>(&input);
                }
            }
        }
        let large = include_bytes!("../../../tests/corpus/owner/oversized-cipher-manifest.json");
        if let Ok(m) = serde_json::from_slice::<crate::TransferManifest>(large) {
            assert!(m.validate().is_err());
        }
    }
}
