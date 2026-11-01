use crate::RpcError;
use serde::{Deserialize, Serialize};

pub const PEER_PROTOCOL_VERSION: u16 = 1;
pub const MAX_PAIRING_CODE_BYTES: usize = 4096;
pub const INVITATION_TTL_SECONDS: u64 = 120;
pub const MAX_PEERS: usize = 16;
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ConnectionConfig {
    pub enabled: bool,
    pub bind_ip: String,
    pub api_port: u16,
    pub discovery: bool,
}
impl Default for ConnectionConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            bind_ip: "127.0.0.1".into(),
            api_port: 0,
            discovery: false,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct PeerPermissions {
    pub chat: bool,
    pub attachment: bool,
    #[serde(default)]
    pub roots: Vec<RootGrant>,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RootGrant {
    pub root_id: String,
    pub capabilities: Vec<Capability>,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Capability {
    Browse,
    Read,
    Create,
    Modify,
    Trash,
    SyncRead,
    Chat,
    Attachment,
}
impl PeerPermissions {
    pub fn validate(&self) -> Result<(), RpcError> {
        if self.roots.len() > 8 {
            return Err(RpcError::new(
                "INVALID_GRANTS",
                "Too many permission scopes.",
            ));
        }
        let mut seen = std::collections::HashSet::new();
        for root in &self.roots {
            if uuid::Uuid::parse_str(&root.root_id).is_err()
                || !seen.insert(&root.root_id)
                || root.capabilities.len() > 6
                || root
                    .capabilities
                    .iter()
                    .any(|c| matches!(c, Capability::Chat | Capability::Attachment))
            {
                return Err(RpcError::new(
                    "INVALID_GRANTS",
                    "Root grants must use unique registered scope IDs and file capabilities.",
                ));
            }
            for (i, c) in root.capabilities.iter().enumerate() {
                if root.capabilities[..i].contains(c) {
                    return Err(RpcError::new(
                        "INVALID_GRANTS",
                        "Duplicate permissions are not allowed.",
                    ));
                }
            }
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InvitationRef {
    pub invitation_id: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttemptRef {
    pub attempt_id: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PeerRef {
    pub peer_id: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PeerRevision {
    pub peer_id: String,
    pub expected_revision: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PeerEndpoint {
    pub peer_id: String,
    pub endpoint: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ApprovePairing {
    pub attempt_id: String,
    pub permissions: PeerPermissions,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdatePeerPermissions {
    pub peer_id: String,
    pub expected_revision: u32,
    pub permissions: PeerPermissions,
}
// Deliberately no Debug: pairing codes contain a short-lived secret.
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JoinPairing {
    pub code: String,
    #[serde(default)]
    pub endpoint_override: Option<String>,
}
impl std::fmt::Debug for JoinPairing {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("JoinPairing([redacted])")
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IdentityView {
    pub state: String,
    pub protection: String,
    pub device_id: Option<String>,
    pub fingerprint: Option<String>,
    pub error: Option<RpcError>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InterfaceView {
    pub ip: String,
    pub name: String,
    pub profile: String,
    pub eligible: bool,
    pub reason: Option<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerView {
    pub peer_id: String,
    pub name: String,
    pub platform: String,
    pub fingerprint: String,
    pub state: String,
    pub direction: String,
    pub endpoint: Option<String>,
    pub permissions: PeerPermissions,
    pub remote_permissions: PeerPermissions,
    pub grant_revision: u32,
    pub session_epoch: u32,
    pub paired_at: String,
    pub last_seen: Option<String>,
    pub connection_state: String,
    pub last_error: Option<RpcError>,
    pub retry_in_ms: Option<u64>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingView {
    pub attempt_id: String,
    pub name: String,
    pub platform: String,
    pub fingerprint: String,
    pub proof_verified: bool,
    pub approved: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvitationStatus {
    pub invitation_id: String,
    pub expires_at: String,
    pub state: String,
    pub pending: Option<PendingView>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JoinStatus {
    pub attempt_id: String,
    pub state: String,
    pub host_fingerprint: String,
    pub local_fingerprint: String,
    pub error: Option<RpcError>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoveryCandidate {
    pub id: String,
    pub endpoint: String,
    pub protocol: u16,
    pub seen_at: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoveryView {
    pub state: String,
    pub message: String,
    pub candidates: Vec<DiscoveryCandidate>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionsSnapshot {
    pub view_notice: Option<String>,
    pub identity: IdentityView,
    pub config: ConnectionConfig,
    pub listening: bool,
    pub endpoint: Option<String>,
    pub error: Option<RpcError>,
    pub interfaces: Vec<InterfaceView>,
    pub peers: Vec<PeerView>,
    pub invitation: Option<InvitationStatus>,
    pub join: Option<JoinStatus>,
    pub discovery: DiscoveryView,
    pub active_sessions: usize,
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn networking_is_opt_in() {
        let c = ConnectionConfig::default();
        assert!(!c.enabled);
        assert!(!c.discovery);
        assert_eq!(c.bind_ip, "127.0.0.1");
    }
    #[test]
    fn defaults_grant_no_content() {
        let p = PeerPermissions::default();
        assert!(!p.chat && !p.attachment && p.roots.is_empty());
    }
    #[test]
    fn unknown_grants_and_owner_fields_fail_closed() {
        assert!(serde_json::from_str::<Capability>("\"all_files\"").is_err());
        assert!(serde_json::from_str::<PeerPermissions>(
            r#"{"chat":false,"attachment":false,"admin":true}"#
        )
        .is_err());
    }
    #[test]
    fn malformed_or_device_level_root_scopes_fail() {
        let p = PeerPermissions {
            roots: vec![RootGrant {
                root_id: "../private".into(),
                capabilities: vec![Capability::Read],
            }],
            ..Default::default()
        };
        assert!(p.validate().is_err());
    }
    #[test]
    fn invitation_secrets_are_redacted_from_debug() {
        let j = JoinPairing {
            code: "secret".into(),
            endpoint_override: None,
        };
        assert!(!format!("{j:?}").contains("secret"));
    }
}
