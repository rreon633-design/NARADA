use crate::RpcError;
use serde::{Deserialize, Serialize};
pub const FILE_PROTOCOL_VERSION: u16 = 1;
pub const MAX_FILE_RESPONSE: usize = 60_000;
pub const MAX_ROOTS: usize = 8;
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RegisterRoot {
    pub path: String,
    pub label: String,
    pub exclusions: Vec<String>,
}
impl std::fmt::Debug for RegisterRoot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("RegisterRoot([owner path redacted])")
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RootRef {
    pub root_id: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RootRevision {
    pub root_id: String,
    pub expected_revision: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RootPolicy {
    pub root_id: String,
    pub expected_revision: u32,
    pub exclusions: Vec<String>,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FileSort {
    #[default]
    Name,
    NameDesc,
    Modified,
    Size,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum FileQuery {
    Roots {},
    Browse {
        root_id: String,
        directory_id: Option<String>,
        cursor: Option<String>,
        #[serde(default)]
        sort: FileSort,
    },
    Search {
        root_id: String,
        query: String,
        cursor: Option<String>,
        #[serde(default)]
        sort: FileSort,
    },
    Details {
        root_id: String,
        object_id: String,
    },
    Preview {
        root_id: String,
        object_id: String,
        expected_version: String,
    },
    Trash {
        root_id: String,
        cursor: Option<String>,
    },
}
impl FileQuery {
    pub fn root(&self) -> Option<&str> {
        match self {
            Self::Roots {} => None,
            Self::Browse { root_id, .. }
            | Self::Search { root_id, .. }
            | Self::Details { root_id, .. }
            | Self::Preview { root_id, .. }
            | Self::Trash { root_id, .. } => Some(root_id),
        }
    }
    pub fn validate(&self) -> Result<(), RpcError> {
        if let Some(id) = self.root() {
            file_id(id)?;
        }
        match self {
            Self::Browse {
                directory_id,
                cursor,
                ..
            } => {
                if let Some(v) = directory_id {
                    file_id(v)?;
                }
                if let Some(v) = cursor {
                    file_id(v)?;
                }
            }
            Self::Search { query, cursor, .. } => {
                if query.trim().is_empty()
                    || query.len() > 128
                    || query.chars().any(char::is_control)
                {
                    return Err(RpcError::new(
                        "INVALID_QUERY",
                        "Search needs 1–128 UTF-8 bytes without control characters.",
                    ));
                }
                if let Some(v) = cursor {
                    file_id(v)?;
                }
            }
            Self::Details { object_id, .. } => file_id(object_id)?,
            Self::Preview {
                object_id,
                expected_version,
                ..
            } => {
                file_id(object_id)?;
                file_version(expected_version)?;
            }
            Self::Trash { cursor, .. } => {
                if let Some(v) = cursor {
                    file_id(v)?;
                }
            }
            Self::Roots {} => {}
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FileMutation {
    pub root_id: String,
    pub operation_id: String,
    pub object_id: String,
    pub expected_version: String,
    pub action: MutationAction,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum MutationAction {
    Rename { name: String },
    Trash {},
    Restore { name: Option<String> },
}
impl FileMutation {
    pub fn validate(&self) -> Result<(), RpcError> {
        file_id(&self.root_id)?;
        file_id(&self.operation_id)?;
        file_id(&self.object_id)?;
        file_version(&self.expected_version)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RemoteFileQuery {
    pub peer_id: String,
    pub query: FileQuery,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RemoteFileMutation {
    pub peer_id: String,
    pub mutation: FileMutation,
}
pub fn file_id(v: &str) -> Result<(), RpcError> {
    if v.len() != 36 || uuid::Uuid::parse_str(v).is_err() {
        Err(RpcError::new(
            "INVALID_OBJECT_ID",
            "Use an opaque registered root or object identifier, not a path.",
        ))
    } else {
        Ok(())
    }
}
pub fn file_version(v: &str) -> Result<(), RpcError> {
    if v.len() != 64 || !v.bytes().all(|c| c.is_ascii_hexdigit()) {
        Err(RpcError::new(
            "INVALID_VERSION",
            "A current object version is required.",
        ))
    } else {
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paths_are_not_object_ids() {
        for s in ["../secret", "C:\\Windows", "/etc/passwd", "file:secret", ""] {
            assert!(file_id(s).is_err());
        }
    }
    #[test]
    fn unknown_mutation_actions_and_fields_fail() {
        assert!(
            serde_json::from_str::<MutationAction>(r#"{"kind":"delete_permanently"}"#).is_err()
        );
        assert!(serde_json::from_str::<MutationAction>(
            r#"{"kind":"rename","name":"ok","overwrite":true}"#
        )
        .is_err());
    }
    #[test]
    fn queries_are_bounded_and_explicit() {
        let q = FileQuery::Search {
            root_id: uuid::Uuid::new_v4().to_string(),
            query: "a".repeat(129),
            cursor: None,
            sort: FileSort::Name,
        };
        assert!(q.validate().is_err());
        assert!(serde_json::from_str::<FileQuery>(r#"{"kind":"roots","path":"/etc"}"#).is_err());
    }
    #[test]
    fn empty_variants_reject_hidden_payload_fields() {
        assert!(serde_json::from_value::<FileQuery>(
            serde_json::json!({"kind":"roots","path":"/private"})
        )
        .is_err());
        assert!(serde_json::from_value::<MutationAction>(
            serde_json::json!({"kind":"trash","path":"/private"})
        )
        .is_err());
    }
}
