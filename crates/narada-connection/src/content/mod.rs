//! Application-layer E2EE, protected transactional history and ciphertext blobs.
//! Library primitives, never a home-grown ratchet or nonce schedule.
mod attachments;
mod chat;
mod vault;
use attachments::validate_attachment;
#[cfg(test)]
mod tests;
use crate::identity::{err, fingerprint};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use narada_files::safe_fs::SafeRoot;
use narada_protocol::*;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::Arc,
};
use tokio::sync::Mutex;
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
#[derive(Clone)]
pub(crate) struct Content(Arc<Inner>);
struct Inner {
    vault: vault::Vault,
    dir: PathBuf,
    blobs: Mutex<()>,
    protection: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Document {
    version: u16,
    device: String,
    account: Value,
    peers: BTreeMap<String, ChatPeer>,
    messages: Vec<Message>,
    drafts: BTreeMap<String, String>,
    blobs: BTreeMap<String, Blob>,
    blob_directory: Option<String>,
}
impl Document {
    fn new(device: &str) -> Result<Self, RpcError> {
        Ok(Self {
            version: 1,
            device: device.into(),
            account: encode(&Account::new().pickle())?,
            peers: BTreeMap::new(),
            messages: Vec::new(),
            drafts: BTreeMap::new(),
            blobs: BTreeMap::new(),
            blob_directory: None,
        })
    }
}
#[derive(Clone, Serialize, Deserialize)]
struct ChatPeer {
    ours: Bundle,
    remote: Option<Bundle>,
    sessions: Vec<StoredSession>,
}
#[derive(Clone, Serialize, Deserialize)]
struct StoredSession {
    id: String,
    pickle: Value,
}
#[derive(Clone, Serialize, Deserialize)]
struct Message {
    id: String,
    peer: String,
    direction: String,
    state: String,
    request_hash: String,
    body: MessageBody,
    envelope: Option<Envelope>,
    digest: Option<String>,
    receipt: Option<Receipt>,
    created: i64,
    retry_until: i64,
    attempts: u8,
    next_attempt: i64,
    error: Option<String>,
    unread: bool,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct MessageBody {
    domain: String,
    message_id: String,
    sender: String,
    recipient: String,
    text: String,
    attachment: Option<AttachmentSecret>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AttachmentSecret {
    descriptor: BlobDescriptor,
    name: String,
    plaintext_size: u64,
    plaintext_sha256: String,
    identity: String,
}
#[derive(Clone, Serialize, Deserialize)]
struct Blob {
    id: String,
    peer: String,
    direction: String,
    descriptor: BlobDescriptor,
    file_identity: Option<String>,
    state: String,
    verified: Vec<bool>,
    error: Option<String>,
    created: i64,
    export: Option<Value>,
    export_id: Option<String>,
    export_destination: Option<TransferDestination>,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub(crate) struct Bundle {
    pub version: u16,
    pub transport_id: String,
    pub recipient: String,
    pub curve: String,
    pub ed: String,
    pub one_time: String,
    pub signature: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Envelope {
    pub version: u16,
    pub message_id: String,
    pub sender: String,
    pub recipient: String,
    pub session_id: String,
    pub message_type: usize,
    pub ciphertext: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub(crate) struct Receipt {
    pub message_id: String,
    pub sender: String,
    pub recipient: String,
    pub digest: String,
    pub state: String,
    pub committed_at: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub(crate) struct BlobDescriptor {
    pub version: u16,
    pub blob_id: String,
    pub size: u64,
    pub sha256: String,
    pub hashes: Vec<String>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum ChatAction {
    Bundle {},
    Push { bundle: Bundle, envelope: Envelope },
    Pull { bundle: Bundle },
    Ack { receipt: Receipt },
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PullReply {
    pub bundle: Bundle,
    pub envelope: Option<Envelope>,
}
fn encode<T: Serialize>(t: &T) -> Result<Value, RpcError> {
    serde_json::to_value(t).map_err(|_| RpcError::storage())
}
fn decode<T: serde::de::DeserializeOwned>(v: Value) -> Result<T, RpcError> {
    serde_json::from_value(v).map_err(|_| vault::locked())
}
fn now() -> i64 {
    chrono::Utc::now().timestamp()
}
fn account(doc: &Document) -> Result<Account, RpcError> {
    Ok(Account::from_pickle(decode::<AccountPickle>(
        doc.account.clone(),
    )?))
}
fn session(s: &StoredSession) -> Result<Session, RpcError> {
    Ok(Session::from_pickle(decode::<SessionPickle>(
        s.pickle.clone(),
    )?))
}
fn digest<T: Serialize>(v: &T) -> Result<String, RpcError> {
    Ok(fingerprint(
        &serde_json::to_vec(v).map_err(|_| RpcError::storage())?,
    ))
}
fn bounded(v: Value) -> Result<Value, RpcError> {
    if serde_json::to_vec(&v)
        .map_err(|_| RpcError::storage())?
        .len()
        > MAX_FILE_RESPONSE
    {
        return Err(err(
            "CONTENT_RESPONSE_LIMIT",
            "This protected view exceeds the bounded owner response.",
        ));
    }
    Ok(v)
}
impl Content {
    pub async fn open(dir: &Path, device: &str, protection: String) -> Result<Self, RpcError> {
        Self::open_inner(dir, device, protection, None, true).await
    }
    pub async fn open_existing(
        dir: &Path,
        device: &str,
        protection: String,
    ) -> Result<Self, RpcError> {
        Self::open_inner(dir, device, protection, None, false).await
    }
    pub async fn open_bound(
        dir: &Path,
        device: &str,
        protection: String,
        expected_identity: &str,
    ) -> Result<Self, RpcError> {
        Self::open_inner(dir, device, protection, Some(expected_identity), false).await
    }
    async fn open_inner(
        dir: &Path,
        device: &str,
        protection: String,
        expected_identity: Option<&str>,
        allow_initialize: bool,
    ) -> Result<Self, RpcError> {
        let me = Self(Arc::new(Inner {
            vault: vault::Vault::open(dir, device, allow_initialize).await?,
            dir: dir.into(),
            blobs: Mutex::new(()),
            protection,
        }));
        if let Some(expected) = expected_identity {
            let actual = me.identity_fingerprint().await;
            if !actual.as_ref().is_ok_and(|value| value == expected) {
                me.close().await;
                return Err(err(
                    "CONTENT_STORE_LOCKED",
                    "The private-content identity does not match the saved workspace marker.",
                ));
            }
        }
        if me
            .0
            .vault
            .read(|d| {
                Ok(d.blobs
                    .values()
                    .any(|b| matches!(b.state.as_str(), "queued" | "allocating" | "receiving_push")))
            })
            .await?
        {
            me.0.vault
                .update(|d| {
                    for b in d.blobs.values_mut() {
                        if matches!(b.state.as_str(), "queued" | "allocating" | "receiving_push") {
                            b.state = "paused".into();
                            b.error = Some("RESTART_REVIEW".into());
                        }
                    }
                    Ok(())
                })
                .await?;
        }
        Ok(me)
    }
    pub async fn attachment_peer(&self, message: &str) -> Result<String, RpcError> {
        file_id(message)?;
        self.0
            .vault
            .read(|d| {
                d.messages
                    .iter()
                    .find(|m| m.id == message && m.body.attachment.is_some())
                    .map(|m| m.peer.clone())
                    .ok_or_else(|| err("INVALID_ATTACHMENT", "No attachment message is selected."))
            })
            .await
    }
    pub async fn close(&self) {
        self.0.vault.close().await;
    }
    pub async fn identity_fingerprint(&self) -> Result<String, RpcError> {
        self.0
            .vault
            .read(|d| {
                let account = account(d)?;
                Ok(fingerprint(
                    format!(
                        "{}:{}",
                        account.curve25519_key().to_base64(),
                        account.ed25519_key().to_base64()
                    )
                    .as_bytes(),
                ))
            })
            .await
    }
    pub async fn status(&self) -> Result<Value, RpcError> {
        self.0.vault.read(|d|{let a=account(d)?;Ok(json!({"ready":true,"protection":self.0.protection,"protocol":"vodozemac Olm v1","attachment_format":"age-encryption.org/v1 · X25519/STREAM","identity_fingerprint":fingerprint(format!("{}:{}",a.curve25519_key().to_base64(),a.ed25519_key().to_base64()).as_bytes()),"messages":d.messages.len(),"pending":d.messages.iter().filter(|m|m.direction=="outgoing"&&!matches!(m.state.as_str(),"delivered"|"cancelled")).count(),"unread":d.messages.iter().filter(|m|m.unread).count(),"limits":{"text_bytes":MAX_CHAT_TEXT,"messages":MAX_CONTENT_MESSAGES,"attachment_bytes":MAX_ATTACHMENT_BYTES,"cipher_budget":128*1024*1024,"peers":32},"qualification":"Development implementation; independent review and native qualification are open."}))}).await
    }
    pub async fn conversations(&self) -> Result<Value, RpcError> {
        self.0.vault.read(|d|{let mut peers:BTreeMap<String,Value>=BTreeMap::new();for (id,p) in &d.peers{peers.insert(id.clone(),json!({"peer_id":id,"unread":0,"messages":0,"last_at":null,"key_state":if p.remote.is_some(){"bound_to_paired_identity"}else{"awaiting_peer"}}));}for m in &d.messages{let row=peers.entry(m.peer.clone()).or_insert_with(||json!({"peer_id":m.peer,"unread":0,"messages":0,"last_at":null,"key_state":"awaiting_peer"}));row["messages"]=json!(row["messages"].as_u64().unwrap_or(0)+1);if m.unread{row["unread"]=json!(row["unread"].as_u64().unwrap_or(0)+1);}row["last_at"]=json!(m.created);}bounded(json!({"conversations":peers.values().collect::<Vec<_>>()}))}).await
    }
    pub async fn history(&self, input: &ChatHistory) -> Result<Value, RpcError> {
        chat_peer(&input.peer_id)?;
        if let Some(id) = &input.before {
            file_id(id)?;
        }
        self.0.vault.read(|d|{let all=d.messages.iter().filter(|m|m.peer==input.peer_id).collect::<Vec<_>>();let end=if let Some(id)=&input.before{all.iter().position(|m|m.id==*id).ok_or_else(||err("HISTORY_CURSOR_EXPIRED","The history cursor is no longer available."))?}else{all.len()};let start=end.saturating_sub(10);let rows=all[start..end].iter().map(|m|message_view(d,m)).collect::<Vec<_>>();bounded(json!({"messages":rows,"older_cursor":if start>0{Some(all[start].id.clone())}else{None},"draft":d.drafts.get(&input.peer_id).cloned().unwrap_or_default(),"peer_id":input.peer_id}))}).await
    }
    pub async fn save_draft(&self, draft: ChatDraft) -> Result<Value, RpcError> {
        chat_peer(&draft.peer_id)?;
        chat_text(&draft.text, true)?;
        self.0
            .vault
            .update(|d| {
                if d.drafts.len() >= 32 && !d.drafts.contains_key(&draft.peer_id) {
                    return Err(err("CONTENT_QUOTA", "The protected draft limit is full."));
                }
                if draft.text.is_empty() {
                    d.drafts.remove(&draft.peer_id);
                } else {
                    d.drafts.insert(draft.peer_id, draft.text);
                }
                Ok(json!({"saved":true}))
            })
            .await
    }
    pub async fn mark_read(&self, peer: &str) -> Result<Value, RpcError> {
        chat_peer(peer)?;
        self.0
            .vault
            .update(|d| {
                for m in &mut d.messages {
                    if m.peer == peer {
                        m.unread = false;
                    }
                }
                Ok(json!({"read_locally":true,"read_receipt_sent":false}))
            })
            .await
    }
    pub async fn control(&self, input: ChatControl) -> Result<Value, RpcError> {
        file_id(&input.message_id)?;
        self.0
            .vault
            .update(|d| {
                let m = d
                    .messages
                    .iter_mut()
                    .find(|m| m.id == input.message_id && m.direction == "outgoing")
                    .ok_or_else(|| {
                        err(
                            "MESSAGE_UNAVAILABLE",
                            "The outgoing message is unavailable.",
                        )
                    })?;
                if m.state == "delivered" {
                    return Ok(json!({"state":"delivered"}));
                }
                match input.action {
                    ChatControlAction::Retry => {
                        if m.state == "cancelled" {
                            return Err(err(
                                "MESSAGE_CANCELLED",
                                "A cancelled message is not revived; compose a new message.",
                            ));
                        }
                        m.state = "pending".into();
                        m.attempts = 0;
                        m.next_attempt = 0;
                        m.error = None;
                        m.retry_until = now() + 86400;
                    }
                    ChatControlAction::Cancel => {
                        m.state = "cancelled".into();
                        m.error = None;
                    }
                }
                Ok(json!({"state":m.state}))
            })
            .await
    }
    pub async fn error(&self, peer: &str, code: &str) -> Result<(), RpcError> {
        if !self
            .0
            .vault
            .read(|d| {
                Ok(d.messages.iter().any(|m| {
                    m.peer == peer
                        && m.direction == "outgoing"
                        && matches!(m.state.as_str(), "pending" | "retry_wait")
                        && m.next_attempt <= now()
                }))
            })
            .await?
        {
            return Ok(());
        }
        self.0
            .vault
            .update(|d| {
                for m in &mut d.messages {
                    if m.peer == peer
                        && m.direction == "outgoing"
                        && matches!(m.state.as_str(), "pending" | "retry_wait")
                    {
                        m.error = Some(code.into());
                        if m.next_attempt <= now() {
                            m.attempts = m.attempts.saturating_add(1);
                            m.next_attempt = now() + 5;
                        }
                        if m.attempts >= 3 {
                            m.state = "action_required".into();
                        }
                    }
                }
                Ok(())
            })
            .await
    }
}
fn message_view(d: &Document, m: &Message) -> Value {
    let attachment=m.body.attachment.as_ref().map(|a|{let b=d.blobs.get(&a.descriptor.blob_id);json!({"blob_id":a.descriptor.blob_id,"name":a.name,"size":a.plaintext_size.to_string(),"sha256":a.plaintext_sha256,"state":b.map(|b|b.state.as_str()).unwrap_or("not_requested"),"verified_bytes":b.map(|b|b.verified.iter().enumerate().filter(|(_,v)|**v).map(|(i,_)|(a.descriptor.size.saturating_sub(i as u64*TRANSFER_CHUNK_BYTES as u64)).min(TRANSFER_CHUNK_BYTES as u64)).sum::<u64>()).unwrap_or(0).to_string(),"cipher_size":a.descriptor.size.to_string(),"error_code":b.and_then(|b|b.error.clone()),"export":b.and_then(|b|b.export.clone()),"export_operation_id":b.and_then(|b|b.export_id.clone()),"export_destination":b.and_then(|b|b.export_destination.clone())})});
    json!({"message_id":m.id,"peer_id":m.peer,"direction":m.direction,"state":m.state,"text":m.body.text,"created_at":m.created,"committed_at":m.receipt.as_ref().map(|r|r.committed_at.clone()),"unread":m.unread,"error_code":m.error,"attempts":m.attempts,"attachment":attachment})
}
