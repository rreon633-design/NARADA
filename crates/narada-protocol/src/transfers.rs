//! Explicit, bounded transfer control. Binary chunks never pass through owner IPC.
use crate::{file_id, file_version, RpcError};
use serde::{Deserialize, Serialize};
pub const TRANSFER_PROTOCOL_VERSION: u16 = 1;
pub const TRANSFER_CHUNK_BYTES: usize = 256 * 1024;
pub const MAX_TRANSFER_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_TRANSFER_CHUNKS: usize = 256;
pub const TRANSFER_MANIFEST_PAGE: usize = 32;
pub const TRANSFER_STORAGE_BUDGET: u64 = 128 * 1024 * 1024;
pub const TRANSFER_LIFETIME_SECS: i64 = 24 * 60 * 60;
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TransferSource {
    pub root_id: String,
    pub object_id: String,
    pub expected_version: String,
}
impl TransferSource {
    pub fn validate(&self) -> Result<(), RpcError> {
        file_id(&self.root_id)?;
        file_id(&self.object_id)?;
        file_version(&self.expected_version)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TransferDestination {
    pub root_id: String,
    pub directory_id: Option<String>,
    pub name: String,
}
impl TransferDestination {
    pub fn validate(&self) -> Result<(), RpcError> {
        file_id(&self.root_id)?;
        if let Some(id) = &self.directory_id {
            file_id(id)?;
        }
        if self.name.is_empty() || self.name.len() > 180 {
            return Err(RpcError::new(
                "INVALID_FILENAME",
                "A bounded destination filename is required.",
            ));
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TransferDirection {
    Download,
    Upload,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StartTransfer {
    pub transfer_id: String,
    pub peer_id: String,
    pub direction: TransferDirection,
    pub source: TransferSource,
    pub destination: TransferDestination,
}
impl StartTransfer {
    pub fn validate(&self) -> Result<(), RpcError> {
        file_id(&self.transfer_id)?;
        transfer_peer(&self.peer_id)?;
        self.source.validate()?;
        self.destination.validate()
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreateTransferOffer {
    pub transfer_id: String,
    pub peer_id: String,
    pub source: TransferSource,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AcceptTransferOffer {
    pub transfer_id: String,
    pub peer_id: String,
    pub destination: TransferDestination,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TransferTarget {
    Job,
    Receive,
    Offer,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TransferVerb {
    Pause,
    Resume,
    Cancel,
    Retry,
    Accept,
    Decline,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransferControl {
    pub transfer_id: String,
    pub target: TransferTarget,
    pub action: TransferVerb,
    pub name: Option<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TransferManifest {
    pub version: u16,
    pub manifest_id: String,
    pub source_version: String,
    pub name: String,
    pub size: String,
    pub chunk_size: u32,
    pub chunk_count: u32,
    pub sha256: String,
}
impl TransferManifest {
    pub fn size_bytes(&self) -> Result<u64, RpcError> {
        decimal_size(&self.size)
    }
    pub fn validate(&self) -> Result<(), RpcError> {
        if self.version != TRANSFER_PROTOCOL_VERSION {
            return Err(RpcError::new(
                "PROTOCOL_INCOMPATIBLE",
                "Unsupported transfer manifest version.",
            ));
        }
        transfer_hash(&self.manifest_id)?;
        transfer_hash(&self.sha256)?;
        file_version(&self.source_version)?;
        let size = self.size_bytes()?;
        if self.name.is_empty()
            || self.name.len() > 180
            || self.name.chars().any(char::is_control)
            || self.chunk_size as usize != TRANSFER_CHUNK_BYTES
            || self.chunk_count as u64 != size.div_ceil(TRANSFER_CHUNK_BYTES as u64)
        {
            return Err(RpcError::new(
                "INVALID_MANIFEST",
                "The manifest dimensions or filename are invalid.",
            ));
        }
        Ok(())
    }
    pub fn chunk_length(&self, index: u32) -> Result<usize, RpcError> {
        self.validate()?;
        if index >= self.chunk_count {
            return Err(RpcError::new(
                "INVALID_CHUNK",
                "The chunk index is out of range.",
            ));
        }
        Ok(
            (self.size_bytes()? - (index as u64 * TRANSFER_CHUNK_BYTES as u64))
                .min(TRANSFER_CHUNK_BYTES as u64) as usize,
        )
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransferStatus {
    pub transfer_id: String,
    pub state: String,
    pub manifest: Option<TransferManifest>,
    pub missing: Vec<u32>,
    pub verified_bytes: String,
    pub error_code: Option<String>,
    pub result: Option<serde_json::Value>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ManifestPage {
    pub transfer_id: String,
    pub manifest_id: String,
    pub offset: u32,
    pub hashes: Vec<String>,
    pub next_offset: Option<u32>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum TransferAction {
    PrepareSync {
        transfer_id: String,
        source: TransferSource,
    },
    Prepare {
        transfer_id: String,
        source: TransferSource,
    },
    Manifest {
        transfer_id: String,
        offset: u32,
    },
    OpenReceive {
        transfer_id: String,
        destination: TransferDestination,
        manifest: TransferManifest,
    },
    ManifestPage {
        transfer_id: String,
        offset: u32,
        hashes: Vec<String>,
    },
    Status {
        transfer_id: String,
        side: TransferSide,
    },
    Commit {
        transfer_id: String,
    },
    Cancel {
        transfer_id: String,
        side: TransferSide,
    },
    Finish {
        transfer_id: String,
        sha256: String,
    },
    Offers {},
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TransferSide {
    Source,
    Receive,
}
pub fn transfer_peer(id: &str) -> Result<(), RpcError> {
    if id.len() != 64 || !id.bytes().all(|c| c.is_ascii_hexdigit()) {
        Err(RpcError::new(
            "INVALID_PEER",
            "A saved peer identity is required.",
        ))
    } else {
        Ok(())
    }
}
pub fn transfer_hash(hash: &str) -> Result<(), RpcError> {
    if hash.len() != 64
        || !hash
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        Err(RpcError::new(
            "INVALID_MANIFEST",
            "A canonical SHA-256 digest is required.",
        ))
    } else {
        Ok(())
    }
}
pub fn decimal_size(value: &str) -> Result<u64, RpcError> {
    if value.is_empty()
        || value.len() > 10
        || !value.bytes().all(|c| c.is_ascii_digit())
        || (value.len() > 1 && value.starts_with('0'))
    {
        return Err(RpcError::new(
            "INVALID_MANIFEST",
            "File size must be a canonical bounded decimal string.",
        ));
    }
    let n = value
        .parse::<u64>()
        .map_err(|_| RpcError::new("INVALID_MANIFEST", "Invalid file size."))?;
    if n > MAX_TRANSFER_BYTES {
        return Err(RpcError::new(
            "TRANSFER_LIMIT",
            "This development slice accepts at most 64 MiB per file.",
        ));
    }
    Ok(n)
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransferReceipt {
    pub transfer_id: String,
    pub state: String,
    pub manifest_id: String,
    pub root_id: String,
    pub name: String,
    pub size: String,
    pub sha256: String,
    pub published_at: String,
}

impl TransferReceipt {
    pub fn validate(&self) -> Result<(), RpcError> {
        file_id(&self.transfer_id)?;
        file_id(&self.root_id)?;
        transfer_hash(&self.manifest_id)?;
        transfer_hash(&self.sha256)?;
        decimal_size(&self.size)?;
        if self.state != "completed"
            || self.name.is_empty()
            || self.name.len() > 180
            || self
                .name
                .chars()
                .any(|c| c.is_control() || matches!(c, '/' | '\\'))
            || chrono::DateTime::parse_from_rfc3339(&self.published_at).is_err()
        {
            return Err(RpcError::new(
                "INVALID_RECEIPT",
                "The publication receipt is invalid.",
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn strict_transfer_envelopes_refuse_paths_and_unknown_fields() {
        assert!(serde_json::from_value::<TransferAction>(
            serde_json::json!({"kind":"offers","path":"/private"})
        )
        .is_err());
        assert!(serde_json::from_value::<TransferAction>(
            serde_json::json!({"kind":"commit","transfer_id":"x","overwrite":true})
        )
        .is_err());
        assert!(file_id("../escape").is_err());
    }
    #[test]
    fn size_and_digest_bounds_are_explicit() {
        for s in ["-1", "1.5", "01", "67108865", "18446744073709551615"] {
            assert!(decimal_size(s).is_err());
        }
        assert_eq!(decimal_size("0").unwrap(), 0);
        assert!(transfer_hash(&"A".repeat(64)).is_err());
        assert!(transfer_hash(&"a".repeat(64)).is_ok());
    }
    #[test]
    fn chunk_arithmetic_handles_empty_and_partial_tail() {
        let mut m = TransferManifest {
            version: 1,
            manifest_id: "a".repeat(64),
            source_version: "b".repeat(64),
            name: "sample.bin".into(),
            size: "0".into(),
            chunk_size: TRANSFER_CHUNK_BYTES as u32,
            chunk_count: 0,
            sha256: "c".repeat(64),
        };
        assert!(m.validate().is_ok());
        assert!(m.chunk_length(0).is_err());
        m.size = (TRANSFER_CHUNK_BYTES + 3).to_string();
        m.chunk_count = 2;
        assert_eq!(m.chunk_length(1).unwrap(), 3);
        assert!(m.chunk_length(2).is_err());
    }
}
