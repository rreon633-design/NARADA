//! Real library and protected-store tests. Synthetic data only. Network/OS
//! qualification is separate from these deterministic in-process fixtures.
#![cfg(any(windows, feature = "dev-vault"))]
use super::*;
use narada_core::Core;
use narada_files::{Access, Files};
use sqlx::Row;
use uuid::Uuid;
struct Fixture {
    temp: tempfile::TempDir,
    core: Core,
    files: Files,
    content: Content,
    device: String,
    state: PathBuf,
    folder: PathBuf,
    root: String,
}
impl Fixture {
    async fn new(device: &str) -> Self {
        let base = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .join("test-fixtures");
        std::fs::create_dir_all(&base).unwrap();
        let temp = tempfile::Builder::new()
            .prefix("rust-content-")
            .tempdir_in(base)
            .unwrap();
        let state = temp.path().join("state");
        let core = Core::open(&state).await.unwrap();
        let files = Files::open(core.clone(), &state).await.unwrap();
        let folder = temp.path().join("approved");
        std::fs::create_dir(&folder).unwrap();
        let root = files
            .register(RegisterRoot {
                path: folder.to_string_lossy().into(),
                label: "Synthetic content tests".into(),
                exclusions: vec![],
            })
            .await
            .unwrap()["root_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let content = Content::open(&state, device, "explicit-test-vault".into())
            .await
            .unwrap();
        Self {
            temp,
            core,
            files,
            content,
            device: device.into(),
            state,
            folder,
            root,
        }
    }
    async fn source(&self, name: &str, bytes: &[u8]) -> TransferSource {
        std::fs::write(self.folder.join(name), bytes).unwrap();
        self.files.reindex(self.root.clone()).await.unwrap();
        let response = self
            .files
            .query(
                FileQuery::Browse {
                    root_id: self.root.clone(),
                    directory_id: None,
                    cursor: None,
                    sort: FileSort::Name,
                },
                Access::Owner,
                self.files.gate().read_owned().await,
            )
            .await
            .unwrap();
        let entry = response["entries"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["name"] == name)
            .unwrap();
        TransferSource {
            root_id: self.root.clone(),
            object_id: entry["object_id"].as_str().unwrap().into(),
            expected_version: entry["version"].as_str().unwrap().into(),
        }
    }
    fn request(&self, peer: &str, text: &str) -> SendChat {
        SendChat {
            message_id: Uuid::new_v4().to_string(),
            peer_id: peer.into(),
            text: text.into(),
            attachment: None,
        }
    }
    async fn queue(&self, input: SendChat) -> Value {
        self.content
            .queue(input, &self.files, &self.files.gate().read_owned().await)
            .await
            .unwrap()
    }
    async fn history(&self, peer: &str) -> Value {
        self.content
            .history(&ChatHistory {
                peer_id: peer.into(),
                before: None,
            })
            .await
            .unwrap()
    }
    async fn due(&self) {
        self.content
            .0
            .vault
            .update(|d| {
                for m in &mut d.messages {
                    m.next_attempt = 0;
                }
                Ok(())
            })
            .await
            .unwrap();
    }
    async fn restart(&mut self) {
        self.content.close().await;
        self.content = Content::open(&self.state, &self.device, "explicit-test-vault".into())
            .await
            .unwrap();
    }
    async fn close(self) {
        self.content.close().await;
        self.files.shutdown().await;
        self.core.metadata_pool().close().await;
        drop(self.content);
        drop(self.files);
        drop(self.core);
        self.temp.close().unwrap();
    }
}
async fn deliver(a: &Fixture, b: &Fixture, request: SendChat) -> (Envelope, Receipt) {
    a.queue(request).await;
    let bb = b.content.bundle(&a.device).await.unwrap();
    let envelope = a
        .content
        .pending(&b.device, &bb, true)
        .await
        .unwrap()
        .unwrap();
    let ba = a.content.bundle(&b.device).await.unwrap();
    let receipt = b
        .content
        .receive(&a.device, &ba, envelope.clone(), true)
        .await
        .unwrap();
    (envelope, receipt)
}
#[tokio::test]
async fn ratchet_outbox_and_inbox_commit_before_receipt() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let marker = "SYNTHETIC_E2EE_SENTINEL_DO_NOT_PERSIST_PLAINTEXT_3417";
    let request = a.request(&b.device, marker);
    let (envelope, receipt) = deliver(&a, &b, request).await;
    assert!(!serde_json::to_string(&envelope).unwrap().contains(marker));
    assert_eq!(
        a.history(&b.device).await["messages"][0]["state"],
        "retry_wait"
    );
    assert_eq!(b.history(&a.device).await["messages"][0]["text"], marker);
    a.content.acknowledge(&b.device, receipt).await.unwrap();
    assert_eq!(
        a.history(&b.device).await["messages"][0]["state"],
        "delivered"
    );
    for f in [&a, &b] {
        for path in std::fs::read_dir(&f.state)
            .unwrap()
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.is_file())
        {
            let bytes = std::fs::read(path).unwrap();
            assert!(
                !bytes.windows(marker.len()).any(|w| w == marker.as_bytes()),
                "Private text entered a state file"
            );
        }
    }
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn lost_receipt_restart_retries_identical_ciphertext_and_displays_once() {
    let mut a = Fixture::new(&"a".repeat(64)).await;
    let mut b = Fixture::new(&"b".repeat(64)).await;
    let request = a.request(&b.device, "Receipt-loss fixture");
    let (first, receipt) = deliver(&a, &b, request).await;
    a.restart().await;
    b.restart().await;
    a.due().await;
    let second = a
        .content
        .pending(&b.device, &b.content.bundle(&a.device).await.unwrap(), true)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(digest(&first).unwrap(), digest(&second).unwrap());
    let before = b
        .content
        .0
        .vault
        .read(|d| encode(&d.peers[&a.device].sessions))
        .await
        .unwrap();
    let replay = b
        .content
        .receive(
            &a.device,
            &a.content.bundle(&b.device).await.unwrap(),
            second,
            true,
        )
        .await
        .unwrap();
    assert_eq!(replay, receipt);
    assert_eq!(
        before,
        b.content
            .0
            .vault
            .read(|d| encode(&d.peers[&a.device].sessions))
            .await
            .unwrap()
    );
    assert_eq!(
        b.history(&a.device).await["messages"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    a.content.acknowledge(&b.device, replay).await.unwrap();
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn same_id_different_envelope_is_not_acknowledged() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let (mut envelope, _) = deliver(&a, &b, a.request(&b.device, "Original fixture")).await;
    envelope.ciphertext.push('A');
    assert_eq!(
        b.content
            .receive(
                &a.device,
                &a.content.bundle(&b.device).await.unwrap(),
                envelope,
                true
            )
            .await
            .unwrap_err()
            .code,
        "MESSAGE_ID_CONFLICT"
    );
    assert_eq!(
        b.history(&a.device).await["messages"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn message_tamper_rolls_back_inbound_account_and_history() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    a.queue(a.request(&b.device, "Authentication fixture"))
        .await;
    let bb = b.content.bundle(&a.device).await.unwrap();
    let mut envelope = a
        .content
        .pending(&b.device, &bb, true)
        .await
        .unwrap()
        .unwrap();
    let before = b
        .content
        .0
        .vault
        .read(|d| Ok(d.account.clone()))
        .await
        .unwrap();
    let mut bytes = B64.decode(&envelope.ciphertext).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 1;
    envelope.ciphertext = B64.encode(bytes);
    assert!(b
        .content
        .receive(
            &a.device,
            &a.content.bundle(&b.device).await.unwrap(),
            envelope,
            true
        )
        .await
        .is_err());
    assert_eq!(
        before,
        b.content
            .0
            .vault
            .read(|d| Ok(d.account.clone()))
            .await
            .unwrap()
    );
    assert!(b.history(&a.device).await["messages"]
        .as_array()
        .unwrap()
        .is_empty());
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn changed_e2ee_identity_is_never_silently_reset() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let other = Fixture::new(&b.device).await;
    let _ = deliver(&a, &b, a.request(&b.device, "Pin initial E2EE identity")).await;
    let before = a
        .content
        .0
        .vault
        .read(|d| encode(&d.peers[&b.device]))
        .await
        .unwrap();
    let result = a
        .content
        .pending(
            &b.device,
            &other.content.bundle(&a.device).await.unwrap(),
            true,
        )
        .await;
    assert_eq!(result.err().unwrap().code, "CHAT_KEY_CHANGED");
    assert_eq!(
        before,
        a.content
            .0
            .vault
            .read(|d| encode(&d.peers[&b.device]))
            .await
            .unwrap()
    );
    a.close().await;
    b.close().await;
    other.close().await;
}
#[tokio::test]
async fn signed_bundle_is_bound_to_both_transport_endpoints() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let mut bundle = b.content.bundle(&a.device).await.unwrap();
    bundle.recipient = "c".repeat(64);
    assert_eq!(
        a.content
            .pending(&b.device, &bundle, true)
            .await
            .err()
            .unwrap()
            .code,
        "CHAT_KEY_INVALID"
    );
    bundle.recipient = a.device.clone();
    bundle.signature.replace_range(..1, "!");
    assert_eq!(
        a.content
            .pending(&b.device, &bundle, true)
            .await
            .err()
            .unwrap()
            .code,
        "CHAT_KEY_INVALID"
    );
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn owner_message_idempotency_does_not_duplicate_outbox() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let peer = "b".repeat(64);
    let input = a.request(&peer, "Stable logical ID");
    let first = a.queue(input.clone()).await;
    assert_eq!(first, a.queue(input.clone()).await);
    let mut changed = input;
    changed.text = "Different body".into();
    assert_eq!(
        a.content
            .queue(changed, &a.files, &a.files.gate().read_owned().await)
            .await
            .unwrap_err()
            .code,
        "MESSAGE_ID_CONFLICT"
    );
    assert_eq!(
        a.history(&peer).await["messages"].as_array().unwrap().len(),
        1
    );
    a.close().await;
}
#[tokio::test]
async fn offline_failures_are_bounded_and_retry_is_explicit() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let peer = "b".repeat(64);
    let input = a.request(&peer, "Offline fixture");
    let id = input.message_id.clone();
    a.queue(input).await;
    for _ in 0..3 {
        a.due().await;
        a.content.error(&peer, "PEER_UNREACHABLE").await.unwrap();
    }
    assert_eq!(
        a.history(&peer).await["messages"][0]["state"],
        "action_required"
    );
    assert_eq!(a.history(&peer).await["messages"][0]["attempts"], 3);
    a.content
        .control(ChatControl {
            message_id: id,
            action: ChatControlAction::Retry,
        })
        .await
        .unwrap();
    assert_eq!(a.history(&peer).await["messages"][0]["state"], "pending");
    a.close().await;
}
#[tokio::test]
async fn verified_late_receipt_outranks_cancellation_but_cannot_be_replaced() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let (_, receipt) = deliver(&a, &b, a.request(&b.device, "Cancellation race fixture")).await;
    a.content
        .control(ChatControl {
            message_id: receipt.message_id.clone(),
            action: ChatControlAction::Cancel,
        })
        .await
        .unwrap();
    a.content
        .acknowledge(&b.device, receipt.clone())
        .await
        .unwrap();
    assert_eq!(
        a.history(&b.device).await["messages"][0]["state"],
        "delivered"
    );
    let mut altered = receipt;
    altered.committed_at = "2000-01-01T00:00:00Z".into();
    assert_eq!(
        a.content
            .acknowledge(&b.device, altered)
            .await
            .unwrap_err()
            .code,
        "INVALID_RECEIPT"
    );
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn empty_poll_does_not_rewrite_protected_state() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let bundle = b.content.bundle(&a.device).await.unwrap();
    assert!(a
        .content
        .pending(&b.device, &bundle, true)
        .await
        .unwrap()
        .is_none());
    let before = std::fs::read(a.state.join("content.guard")).unwrap();
    for _ in 0..3 {
        assert!(a
            .content
            .pending(&b.device, &bundle, true)
            .await
            .unwrap()
            .is_none());
    }
    assert_eq!(
        before,
        std::fs::read(a.state.join("content.guard")).unwrap()
    );
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn protected_drafts_history_pages_and_local_read_have_no_read_receipt() {
    let mut a = Fixture::new(&"a".repeat(64)).await;
    let peer = "b".repeat(64);
    for i in 0..11 {
        a.queue(a.request(&peer, &format!("Synthetic history {i}")))
            .await;
    }
    a.content
        .save_draft(ChatDraft {
            peer_id: peer.clone(),
            text: "తెలుగు protected draft".into(),
        })
        .await
        .unwrap();
    a.restart().await;
    let history = a.history(&peer).await;
    assert_eq!(history["messages"].as_array().unwrap().len(), 10);
    assert_eq!(history["draft"], "తెలుగు protected draft");
    let older = a
        .content
        .history(&ChatHistory {
            peer_id: peer.clone(),
            before: Some(history["older_cursor"].as_str().unwrap().into()),
        })
        .await
        .unwrap();
    assert_eq!(older["messages"].as_array().unwrap().len(), 1);
    assert_eq!(
        a.content.mark_read(&peer).await.unwrap()["read_receipt_sent"],
        false
    );
    a.close().await;
}
#[tokio::test]
async fn full_history_refuses_new_messages_instead_of_evicting_unread_data() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let peer = "b".repeat(64);
    for _ in 0..MAX_CONTENT_MESSAGES {
        a.queue(a.request(&peer, "Bounded fixture")).await;
    }
    assert_eq!(
        a.content
            .queue(
                a.request(&peer, "Over limit"),
                &a.files,
                &a.files.gate().read_owned().await
            )
            .await
            .unwrap_err()
            .code,
        "CONTENT_QUOTA"
    );
    a.close().await;
}
#[tokio::test]
async fn missing_key_or_database_never_generates_a_replacement_identity() {
    let a = Fixture::new(&"a".repeat(64)).await;
    a.content.close().await;
    let key = std::fs::read(a.state.join("content.key")).unwrap();
    std::fs::remove_file(a.state.join("content.key")).unwrap();
    assert_eq!(
        Content::open(&a.state, &a.device, "test".into())
            .await
            .err()
            .unwrap()
            .code,
        "CONTENT_STORE_LOCKED"
    );
    assert!(!a.state.join("content.key").exists());
    std::fs::write(a.state.join("content.key"), key).unwrap();
    std::fs::remove_file(a.state.join("content.db")).unwrap();
    std::fs::remove_file(a.state.join("content.guard")).unwrap();
    assert_eq!(
        Content::open(&a.state, &a.device, "test".into())
            .await
            .err()
            .unwrap()
            .code,
        "CONTENT_STORE_LOCKED"
    );
    a.close().await;
}
#[tokio::test]
async fn stale_guard_and_corrupt_ciphertext_lock_instead_of_resetting_history() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let guard = std::fs::read(a.state.join("content.guard")).unwrap();
    a.queue(a.request(&"b".repeat(64), "Guard fixture")).await;
    a.content.close().await;
    let latest = std::fs::read(a.state.join("content.guard")).unwrap();
    std::fs::write(a.state.join("content.guard"), guard).unwrap();
    assert_eq!(
        Content::open(&a.state, &a.device, "test".into())
            .await
            .err()
            .unwrap()
            .code,
        "CONTENT_STORE_LOCKED"
    );
    std::fs::write(a.state.join("content.guard"), latest).unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new().filename(a.state.join("content.db"));
    let db = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .unwrap();
    let row = sqlx::query("SELECT sealed FROM protected_state WHERE id=1")
        .fetch_one(&db)
        .await
        .unwrap();
    let mut blob: Vec<u8> = row.get("sealed");
    blob[13] ^= 1;
    sqlx::query("UPDATE protected_state SET sealed=? WHERE id=1")
        .bind(blob)
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
    assert_eq!(
        Content::open(&a.state, &a.device, "test".into())
            .await
            .err()
            .unwrap()
            .code,
        "CONTENT_STORE_LOCKED"
    );
    a.close().await;
}
#[tokio::test]
async fn attachment_delivery_download_authentication_and_export_are_distinct() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let b = Fixture::new(&"b".repeat(64)).await;
    let plaintext = b"SYNTHETIC_PRIVATE_ATTACHMENT_BYTES_71419".repeat(17000);
    let source = a.source("private-fixture-71419.bin", &plaintext).await;
    let mut input = a.request(&b.device, "An inert encrypted fixture");
    input.attachment = Some(source);
    let message = input.message_id.clone();
    let (_, receipt) = deliver(&a, &b, input).await;
    a.content.acknowledge(&b.device, receipt).await.unwrap();
    let view = b.history(&a.device).await;
    assert_eq!(view["messages"][0]["state"], "delivered");
    assert_eq!(view["messages"][0]["attachment"]["state"], "not_requested");
    let id = view["messages"][0]["attachment"]["blob_id"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(!std::fs::read(a.state.join("content-blobs").join(&id))
        .unwrap()
        .windows(36)
        .any(|w| w == &plaintext[..36]));
    assert_eq!(
        a.content
            .blob_chunk(&"c".repeat(64), &id, 0)
            .await
            .unwrap_err()
            .code,
        "ACCESS_DENIED"
    );
    std::fs::write(
        a.folder.join("private-fixture-71419.bin"),
        b"Changed original",
    )
    .unwrap();
    b.content.begin_fetch(&message).await.unwrap();
    assert_eq!(
        b.content
            .accept_chunk(&id, 0, vec![0; TRANSFER_CHUNK_BYTES])
            .await
            .unwrap_err()
            .code,
        "HASH_MISMATCH"
    );
    for (_, _, missing) in b.content.pending_blobs().await.unwrap() {
        for index in missing {
            let bytes = a.content.blob_chunk(&b.device, &id, index).await.unwrap();
            b.content.accept_chunk(&id, index, bytes).await.unwrap();
        }
    }
    assert_eq!(
        b.history(&a.device).await["messages"][0]["attachment"]["state"],
        "queued"
    );
    b.content.finish_fetch(&id).await.unwrap();
    assert_eq!(
        b.history(&a.device).await["messages"][0]["attachment"]["state"],
        "available"
    );
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM transfer_receives")
        .fetch_one(&b.core.metadata_pool())
        .await
        .unwrap();
    assert_eq!(
        count, 0,
        "No plaintext receive exists before explicit export"
    );
    let input = ExportAttachment {
        message_id: message,
        operation_id: Uuid::new_v4().to_string(),
        destination: TransferDestination {
            root_id: b.root.clone(),
            directory_id: None,
            name: "owner-reviewed.bin".into(),
        },
    };
    let exported = b.content.export(input.clone(), &b.files).await.unwrap();
    assert_eq!(exported["state"], "completed");
    assert_eq!(
        std::fs::read(b.folder.join("owner-reviewed.bin")).unwrap(),
        plaintext
    );
    assert_eq!(b.content.export(input, &b.files).await.unwrap(), exported);
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn attachment_checkpoint_resume_revalidates_tampered_bytes_after_restart() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let mut b = Fixture::new(&"b".repeat(64)).await;
    let source = a
        .source("resume.bin", &vec![35; TRANSFER_CHUNK_BYTES * 2])
        .await;
    let mut input = a.request(&b.device, "Resume fixture");
    input.attachment = Some(source);
    let message = input.message_id.clone();
    deliver(&a, &b, input).await;
    b.content.begin_fetch(&message).await.unwrap();
    let (id, _, missing) = b.content.pending_blobs().await.unwrap().remove(0);
    assert!(missing.len() >= 2);
    b.content
        .accept_chunk(
            &id,
            0,
            a.content.blob_chunk(&b.device, &id, 0).await.unwrap(),
        )
        .await
        .unwrap();
    b.restart().await;
    assert_eq!(
        b.history(&a.device).await["messages"][0]["attachment"]["state"],
        "paused"
    );
    use std::io::{Seek, SeekFrom, Write};
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .open(b.state.join("content-blobs").join(&id))
        .unwrap();
    file.seek(SeekFrom::Start(0)).unwrap();
    file.write_all(b"bad!").unwrap();
    file.sync_all().unwrap();
    drop(file);
    b.content.begin_fetch(&message).await.unwrap();
    let missing = b.content.pending_blobs().await.unwrap().remove(0).2;
    assert!(missing.contains(&0));
    for index in missing {
        b.content
            .accept_chunk(
                &id,
                index,
                a.content.blob_chunk(&b.device, &id, index).await.unwrap(),
            )
            .await
            .unwrap();
    }
    b.content.finish_fetch(&id).await.unwrap();
    a.close().await;
    b.close().await;
}
#[tokio::test]
async fn export_collision_preserves_original_and_allows_new_confirmed_name() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let source = a.source("export-source.bin", b"verified export data").await;
    let mut input = a.request(&"b".repeat(64), "Export fixture");
    input.attachment = Some(source);
    let id = input.message_id.clone();
    a.queue(input).await;
    std::fs::write(a.folder.join("keep.bin"), b"USER_ORIGINAL").unwrap();
    let request = ExportAttachment {
        message_id: id.clone(),
        operation_id: Uuid::new_v4().to_string(),
        destination: TransferDestination {
            root_id: a.root.clone(),
            directory_id: None,
            name: "keep.bin".into(),
        },
    };
    assert_eq!(
        a.content.export(request, &a.files).await.unwrap_err().code,
        "NAME_COLLISION"
    );
    let request = ExportAttachment {
        message_id: id,
        operation_id: Uuid::new_v4().to_string(),
        destination: TransferDestination {
            root_id: a.root.clone(),
            directory_id: None,
            name: "safe-copy.bin".into(),
        },
    };
    a.content.export(request, &a.files).await.unwrap();
    assert_eq!(
        std::fs::read(a.folder.join("keep.bin")).unwrap(),
        b"USER_ORIGINAL"
    );
    assert_eq!(
        std::fs::read(a.folder.join("safe-copy.bin")).unwrap(),
        b"verified export data"
    );
    a.close().await;
}
#[tokio::test]
async fn cleanup_keeps_unsent_ciphertext_and_unknown_orphans() {
    let a = Fixture::new(&"a".repeat(64)).await;
    let source = a.source("pending.bin", b"Pending attachment").await;
    let mut input = a.request(&"b".repeat(64), "Pending cleanup fixture");
    input.attachment = Some(source);
    a.queue(input).await;
    let id = a
        .content
        .0
        .vault
        .read(|d| Ok(d.blobs.keys().next().unwrap().clone()))
        .await
        .unwrap();
    a.content
        .0
        .vault
        .update(|d| {
            d.blobs.get_mut(&id).unwrap().created = now() - 8 * 86400;
            Ok(())
        })
        .await
        .unwrap();
    let orphan = a
        .state
        .join("content-blobs")
        .join(Uuid::new_v4().to_string());
    std::fs::write(&orphan, b"UNKNOWN_CIPHERTEXT_ORPHAN").unwrap();
    assert_eq!(
        a.content.cleanup().await.unwrap()["ciphertext_files_cleaned"],
        0
    );
    assert!(a.state.join("content-blobs").join(id).exists());
    assert!(orphan.exists());
    a.close().await;
}

#[tokio::test]
async fn bound_identity_refuses_total_private_store_loss_before_creating_any_key() {
    // Post-pass regression source; not executed in the recorded validation.
    let a = Fixture::new(&"a".repeat(64)).await;
    let fingerprint = a.content.identity_fingerprint().await.unwrap();
    a.content.close().await;
    for name in ["content.key", "content.db", "content.guard", "content.db-wal", "content.db-shm"] {
        let path = a.state.join(name);
        if path.exists() {
            std::fs::remove_file(path).unwrap();
        }
    }
    let error = Content::open_bound(&a.state, &a.device, "test".into(), &fingerprint)
        .await
        .err()
        .unwrap();
    assert_eq!(error.code, "CONTENT_STORE_LOCKED");
    for name in ["content.key", "content.db", "content.guard"] {
        assert!(!a.state.join(name).exists());
    }
    a.close().await;
}
