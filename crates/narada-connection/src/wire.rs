use crate::{
    endpoint,
    identity::{err, fingerprint},
};
use narada_protocol::{PeerPermissions, RpcError, MAX_PAIRING_CODE_BYTES, PEER_PROTOCOL_VERSION};
use serde::{Deserialize, Serialize};
use std::time::Instant;
use subtle::ConstantTimeEq;
use zeroize::Zeroize;
pub const CAPABILITIES: [&str; 2] = ["connection.health", "connection.permissions"];
pub const MAX_BODY: usize = 16_384;
pub const PAIR_PATH: &str = "/pair/v1/claim";
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Hello {
    pub version: u16,
    pub capabilities: Vec<String>,
}
impl Default for Hello {
    fn default() -> Self {
        Self {
            version: PEER_PROTOCOL_VERSION,
            capabilities: CAPABILITIES.iter().map(|s| (*s).into()).collect(),
        }
    }
}
impl Hello {
    pub fn validate(&self) -> Result<Vec<String>, RpcError> {
        if self.version != PEER_PROTOCOL_VERSION {
            return Err(err(
                "PROTOCOL_INCOMPATIBLE",
                "Peer protocol versions differ. No downgrade was attempted.",
            ));
        }
        if self.capabilities.len() > 16
            || self
                .capabilities
                .iter()
                .any(|s| s.len() > 64 || !s.bytes().all(|b| b.is_ascii_lowercase() || b == b'.'))
            || !CAPABILITIES
                .iter()
                .all(|c| self.capabilities.iter().any(|s| s == c))
        {
            return Err(err(
                "CAPABILITIES_INCOMPATIBLE",
                "The required connection capabilities are not supported.",
            ));
        }
        let mut seen = std::collections::HashSet::new();
        if self.capabilities.iter().any(|s| !seen.insert(s)) {
            return Err(err(
                "INVALID_HANDSHAKE",
                "Duplicate capabilities are invalid.",
            ));
        }
        Ok(CAPABILITIES.iter().map(|s| (*s).into()).collect())
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairCode {
    pub format: String,
    pub version: u16,
    pub invitation_id: String,
    pub token: String,
    pub endpoint: String,
    pub fingerprint: String,
    pub device_id: String,
    pub name: String,
    pub expires_at: String,
}
impl Drop for PairCode {
    fn drop(&mut self) {
        self.token.zeroize();
    }
}
impl PairCode {
    pub fn parse(text: &str) -> Result<Self, RpcError> {
        if text.len() > MAX_PAIRING_CODE_BYTES {
            return Err(err(
                "INVALID_PAIRING_CODE",
                "The pairing code exceeds the supported size.",
            ));
        }
        let code: Self = serde_json::from_str(text).map_err(|_| {
            err(
                "INVALID_PAIRING_CODE",
                "This is not a supported NARADA pairing code.",
            )
        })?;
        if code.format != "narada-pairing" || code.version != PEER_PROTOCOL_VERSION {
            return Err(err(
                "PROTOCOL_INCOMPATIBLE",
                "The pairing format or protocol version is unsupported.",
            ));
        }
        valid_uuid(&code.invitation_id)?;
        crate::store::validate_id(&code.device_id)?;
        crate::store::validate_id(&code.fingerprint)?;
        validate_name(&code.name)?;
        if code.token.len() != 64 || hex::decode(&code.token).is_err() {
            return Err(err(
                "INVALID_PAIRING_CODE",
                "The invitation secret is malformed.",
            ));
        }
        endpoint::endpoint(&code.endpoint)?;
        let expiry = chrono::DateTime::parse_from_rfc3339(&code.expires_at)
            .map_err(|_| err("INVALID_PAIRING_CODE", "The invitation expiry is invalid."))?
            .with_timezone(&chrono::Utc);
        let remaining = expiry
            .signed_duration_since(chrono::Utc::now())
            .num_seconds();
        if remaining < 0 {
            return Err(err(
                "INVITATION_EXPIRED",
                "This invitation has expired. Generate a new code on the owner device.",
            ));
        }
        if remaining > 150 {
            return Err(err("CLOCK_SKEW","The device clocks differ or the invitation lifetime is invalid. Check both clocks."));
        }
        Ok(code)
    }
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Claim {
    pub hello: Hello,
    pub invitation_id: String,
    pub attempt_id: String,
    pub token: String,
    pub certificate: String,
    pub name: String,
    pub platform: String,
}
impl Drop for Claim {
    fn drop(&mut self) {
        self.token.zeroize();
    }
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClaimReply {
    pub hello: Hello,
    pub attempt_id: String,
    pub api_port: u16,
    pub name: String,
    pub platform: String,
    pub certificate: String,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Proof {
    pub hello: Hello,
    pub invitation_id: String,
    pub attempt_id: String,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairState {
    pub hello: Hello,
    pub state: String,
    pub permissions: PeerPermissions,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Health {
    pub hello: Hello,
    pub device_id: String,
    pub permissions: PeerPermissions,
    pub grant_revision: u32,
    pub session_epoch: u32,
    pub operations: Vec<String>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WireResponse<T> {
    pub ok: bool,
    pub data: Option<T>,
    pub error: Option<RpcError>,
}
pub fn valid_uuid(s: &str) -> Result<(), RpcError> {
    if s.len() != 36 || uuid::Uuid::parse_str(s).is_err() {
        Err(err(
            "INVALID_HANDSHAKE",
            "A valid pairing attempt identifier is required.",
        ))
    } else {
        Ok(())
    }
}
pub fn validate_name(s: &str) -> Result<(), RpcError> {
    if s.trim().is_empty()
        || s.chars().count() > 48
        || s.chars()
            .any(|c| c.is_control() || matches!(c,'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}'))
    {
        Err(err(
            "INVALID_HANDSHAKE",
            "The peer display name is invalid.",
        ))
    } else {
        Ok(())
    }
}
pub fn validate_platform(s: &str) -> Result<(), RpcError> {
    if !matches!(s, "windows" | "linux" | "macos" | "android") {
        Err(err(
            "INVALID_HANDSHAKE",
            "The self-reported peer platform is unsupported.",
        ))
    } else {
        Ok(())
    }
}
pub fn live(deadline: Instant) -> Result<(), RpcError> {
    if Instant::now() >= deadline {
        Err(err(
            "INVITATION_EXPIRED",
            "The one-time invitation has expired. Start a new pairing attempt.",
        ))
    } else {
        Ok(())
    }
}
pub fn token_matches(hash: &str, token: &str) -> bool {
    token.len() == 64
        && bool::from(
            hash.as_bytes()
                .ct_eq(fingerprint(token.as_bytes()).as_bytes()),
        )
}
pub fn transient(error: &RpcError) -> bool {
    matches!(
        error.code.as_str(),
        "PEER_UNREACHABLE" | "PEER_TIMEOUT" | "CONNECTION_INTERRUPTED"
    )
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FileWire {
    pub hello: Hello,
    pub version: u16,
    pub action: FileWireAction,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "type",
    content = "payload",
    rename_all = "snake_case",
    deny_unknown_fields
)]
pub enum FileWireAction {
    Query(narada_protocol::FileQuery),
    Mutate(narada_protocol::FileMutation),
}

pub fn valid_health_operations(v: &[String]) -> bool {
    v == ["health", "permissions"]
        || v == ["health", "permissions", "files.v1"]
        || v == ["health", "permissions", "files.v1", "transfers.v1"]
        || v == [
            "health",
            "permissions",
            "files.v1",
            "transfers.v1",
            "chat.olm.v1",
            "attachments.age.v1",
            "sync.v1",
        ]
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransferWire {
    pub hello: Hello,
    pub version: u16,
    pub action: narada_protocol::TransferAction,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn no_protocol_or_capability_downgrade() {
        assert!(Hello::default().validate().is_ok());
        assert!(Hello {
            version: 0,
            ..Default::default()
        }
        .validate()
        .is_err());
        assert!(Hello {
            version: 2,
            ..Default::default()
        }
        .validate()
        .is_err());
        assert!(Hello {
            version: 1,
            capabilities: vec!["files.read".into()]
        }
        .validate()
        .is_err());
    }
    #[test]
    fn expiry_is_monotonic_and_secrets_are_hashed() {
        assert!(live(Instant::now() - std::time::Duration::from_secs(1)).is_err());
        let token = "a".repeat(64);
        let h = fingerprint(token.as_bytes());
        assert_ne!(h, token);
        assert!(token_matches(&h, &token));
        assert!(!token_matches(&h, &"b".repeat(64)));
    }
    #[test]
    fn forged_or_oversized_codes_and_display_controls_are_rejected() {
        assert!(PairCode::parse("{}").is_err());
        assert!(PairCode::parse(&"a".repeat(MAX_PAIRING_CODE_BYTES + 1)).is_err());
        assert!(validate_name("owner\u{202e}exe").is_err());
    }
    #[test]
    fn health_advertises_only_reviewed_optional_content_protocols() {
        let operations = |v: &[&str]| v.iter().map(|s| (*s).into()).collect::<Vec<String>>();
        assert!(valid_health_operations(&operations(&[
            "health",
            "permissions"
        ])));
        assert!(valid_health_operations(&operations(&[
            "health",
            "permissions",
            "files.v1"
        ])));
        assert!(valid_health_operations(&operations(&[
            "health",
            "permissions",
            "files.v1",
            "transfers.v1"
        ])));
        assert!(!valid_health_operations(&operations(&[
            "health",
            "permissions",
            "files.v1",
            "transfers.v2"
        ])));
        assert!(!valid_health_operations(&operations(&[
            "health",
            "permissions",
            "transfers.v1"
        ])));
        assert!(!valid_health_operations(&operations(&[
            "health",
            "permissions",
            "files.v2"
        ])));
        assert!(!valid_health_operations(&operations(&[
            "health",
            "permissions",
            "files.v1",
            "shutdown"
        ])));
    }
}
