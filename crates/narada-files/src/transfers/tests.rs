use super::*;
use uuid::Uuid;
struct Fixture {
    temp: tempfile::TempDir,
    files: Files,
    path: PathBuf,
    root: String,
    source: TransferSource,
    data: Vec<u8>,
}
impl Fixture {
    async fn new(data: Vec<u8>) -> Self {
        let base = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .join("test-fixtures");
        std::fs::create_dir_all(&base).unwrap();
        let temp = tempfile::Builder::new()
            .prefix("rust-transfers-")
            .tempdir_in(base)
            .unwrap();
        let path = temp.path().join("approved");
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("payload.bin"), &data).unwrap();
        let state = temp.path().join("state");
        let core = Core::open(&state).await.unwrap();
        let files = Files::open(core, &state).await.unwrap();
        let result = files
            .register(RegisterRoot {
                path: path.to_string_lossy().into(),
                label: "Transfer fixture".into(),
                exclusions: vec![],
            })
            .await
            .unwrap();
        let root = result["root_id"].as_str().unwrap().to_owned();
        let entry = files.entries(&root).await.unwrap().remove(0);
        let source = TransferSource {
            root_id: root.clone(),
            object_id: entry.object_id,
            expected_version: entry.info.version,
        };
        Self {
            temp,
            files,
            path,
            root,
            source,
            data,
        }
    }
    async fn close(self) {
        self.files.shutdown().await;
        self.files.0.pool.close().await;
        drop(self.files);
        self.temp.close().unwrap();
    }
    fn destination(&self) -> TransferDestination {
        TransferDestination {
            root_id: self.root.clone(),
            directory_id: None,
            name: "copy.bin".into(),
        }
    }
    fn peer(&self, caps: Vec<Capability>) -> Access {
        Access::Peer {
            id: "a".repeat(64),
            grants: PeerPermissions {
                roots: vec![RootGrant {
                    root_id: self.root.clone(),
                    capabilities: caps,
                }],
                ..Default::default()
            },
        }
    }
    async fn action(&self, action: TransferAction, a: Access) -> Result<Value, RpcError> {
        self.files
            .transfer_action(action, a, self.files.gate().read_owned().await)
            .await
    }
    async fn prepare(&self, id: &str) -> TransferManifest {
        let v = self
            .action(
                TransferAction::Prepare {
                    transfer_id: id.into(),
                    source: self.source.clone(),
                },
                Access::Owner,
            )
            .await
            .unwrap();
        serde_json::from_value::<TransferStatus>(v)
            .unwrap()
            .manifest
            .unwrap()
    }
    async fn ready_receive(&self, id: &str, m: &TransferManifest, a: Access) {
        self.action(
            TransferAction::OpenReceive {
                transfer_id: id.into(),
                destination: self.destination(),
                manifest: m.clone(),
            },
            a.clone(),
        )
        .await
        .unwrap();
        if !matches!(a, Access::Owner) {
            self.files
                .control_transfer(TransferControl {
                    transfer_id: id.into(),
                    target: TransferTarget::Receive,
                    action: TransferVerb::Accept,
                    name: None,
                })
                .await
                .unwrap();
        }
        let hashes = self
            .data
            .chunks(TRANSFER_CHUNK_BYTES)
            .map(|b| hex::encode(Sha256::digest(b)))
            .collect();
        self.action(
            TransferAction::ManifestPage {
                transfer_id: id.into(),
                offset: 0,
                hashes,
            },
            a,
        )
        .await
        .unwrap();
    }
    async fn chunks(&self, id: &str, a: Access) {
        for (i, data) in self.data.chunks(TRANSFER_CHUNK_BYTES).enumerate() {
            self.files
                .transfer_chunk(
                    id.into(),
                    i as u32,
                    Some(data.to_vec()),
                    a.clone(),
                    self.files.gate().read_owned().await,
                )
                .await
                .unwrap();
        }
    }
    async fn commit(&self, id: &str, a: Access) -> Result<Value, RpcError> {
        self.action(
            TransferAction::Commit {
                transfer_id: id.into(),
            },
            a,
        )
        .await
    }
}
fn sample() -> Vec<u8> {
    (0..TRANSFER_CHUNK_BYTES + 17)
        .map(|i| (i % 251) as u8)
        .collect()
}
#[test]
fn disk_headroom_fails_closed() {
    assert_eq!(ensure_space(0, 1).unwrap_err().code, "STORAGE_FULL");
    assert!(ensure_space(16 * 1024 * 1024, 0).is_ok());
    assert!(ensure_space(u64::MAX, MAX_TRANSFER_BYTES).is_ok());
}
#[tokio::test]
async fn snapshot_is_immutable_when_original_content_changes() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    std::fs::write(f.path.join("payload.bin"), b"different current content").unwrap();
    let bytes = f
        .files
        .transfer_chunk(
            id,
            0,
            None,
            Access::Owner,
            f.files.gate().read_owned().await,
        )
        .await
        .unwrap();
    assert_eq!(bytes, &f.data[..TRANSFER_CHUNK_BYTES]);
    assert_eq!(m.sha256, hex::encode(Sha256::digest(&f.data)));
    f.close().await;
}
#[tokio::test]
async fn full_file_verification_publishes_partial_tail_and_empty_file() {
    for data in [sample(), Vec::new()] {
        let f = Fixture::new(data).await;
        let id = Uuid::new_v4().to_string();
        let m = f.prepare(&id).await;
        f.ready_receive(&id, &m, Access::Owner).await;
        f.chunks(&id, Access::Owner).await;
        let receipt = f.commit(&id, Access::Owner).await.unwrap();
        assert_eq!(receipt["state"], "completed");
        assert_eq!(std::fs::read(f.path.join("copy.bin")).unwrap(), f.data);
        assert!(!f.path.join(STAGE_DIR).join(&id).exists());
        f.close().await;
    }
}
#[tokio::test]
async fn duplicate_commit_returns_the_same_durable_receipt() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    f.chunks(&id, Access::Owner).await;
    let first = f.commit(&id, Access::Owner).await.unwrap();
    assert_eq!(first, f.commit(&id, Access::Owner).await.unwrap());
    f.close().await;
}
#[tokio::test]
async fn incoming_upload_requires_explicit_owner_approval() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    let a = f.peer(vec![Capability::Browse, Capability::Create]);
    let v = f
        .action(
            TransferAction::OpenReceive {
                transfer_id: id.clone(),
                destination: f.destination(),
                manifest: m,
            },
            a.clone(),
        )
        .await
        .unwrap();
    assert_eq!(v["state"], "awaiting_approval");
    assert!(!f.path.join(STAGE_DIR).join(&id).exists());
    assert_eq!(
        f.files
            .transfer_chunk(
                id.clone(),
                0,
                Some(f.data[..TRANSFER_CHUNK_BYTES].to_vec()),
                a,
                f.files.gate().read_owned().await
            )
            .await
            .unwrap_err()
            .code,
        "RECEIVE_NOT_READY"
    );
    assert!(!f.path.join("copy.bin").exists());
    f.close().await;
}
#[tokio::test]
async fn missing_or_corrupt_chunks_never_publish() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    assert_eq!(
        f.commit(&id, Access::Owner).await.unwrap_err().code,
        "TRANSFER_INCOMPLETE"
    );
    let bad = vec![9u8; TRANSFER_CHUNK_BYTES];
    assert_eq!(
        f.files
            .transfer_chunk(
                id.clone(),
                0,
                Some(bad),
                Access::Owner,
                f.files.gate().read_owned().await
            )
            .await
            .unwrap_err()
            .code,
        "HASH_MISMATCH"
    );
    assert_eq!(f.files.receive_record(&id).await.unwrap().bytes, 0);
    assert!(!f.path.join("copy.bin").exists());
    f.close().await;
}
#[tokio::test]
async fn stale_source_is_refused_before_a_manifest_is_served() {
    let f = Fixture::new(sample()).await;
    std::fs::write(f.path.join("payload.bin"), b"changed before preparation").unwrap();
    let id = Uuid::new_v4().to_string();
    assert_eq!(
        f.action(
            TransferAction::Prepare {
                transfer_id: id,
                source: f.source.clone()
            },
            Access::Owner
        )
        .await
        .unwrap_err()
        .code,
        "STALE_VERSION"
    );
    assert_eq!(
        std::fs::read(f.path.join("payload.bin")).unwrap(),
        b"changed before preparation"
    );
    f.close().await;
}
#[tokio::test]
async fn resume_rehashes_saved_bits_and_requests_only_uncertain_chunks() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    f.chunks(&id, Access::Owner).await;
    let path = f.path.join(STAGE_DIR).join(&id);
    let mut file = std::fs::OpenOptions::new().write(true).open(path).unwrap();
    file.write_all(&[99]).unwrap();
    file.sync_all().unwrap();
    drop(file);
    let row = f.files.receive_record(&id).await.unwrap();
    f.files.revalidate_checkpoints(&row).await.unwrap();
    let row = f.files.receive_record(&id).await.unwrap();
    assert_eq!(row.bitmap, vec![false, true]);
    assert_eq!(row.bytes, 17);
    f.close().await;
}
#[tokio::test]
async fn collision_keeps_existing_file_and_explicit_receiver_rename_preserves_manifest() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    f.chunks(&id, Access::Owner).await;
    std::fs::write(f.path.join("copy.bin"), b"keep existing").unwrap();
    assert_eq!(
        f.commit(&id, Access::Owner).await.unwrap_err().code,
        "NAME_COLLISION"
    );
    assert_eq!(
        std::fs::read(f.path.join("copy.bin")).unwrap(),
        b"keep existing"
    );
    f.files
        .control_transfer(TransferControl {
            transfer_id: id.clone(),
            target: TransferTarget::Receive,
            action: TransferVerb::Retry,
            name: Some("reviewed-copy.bin".into()),
        })
        .await
        .unwrap();
    let result = f.commit(&id, Access::Owner).await.unwrap();
    assert_eq!(result["name"], "reviewed-copy.bin");
    assert_eq!(
        std::fs::read(f.path.join("reviewed-copy.bin")).unwrap(),
        f.data
    );
    f.close().await;
}
#[tokio::test]
async fn cancellation_after_publication_never_deletes_user_content() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    f.chunks(&id, Access::Owner).await;
    f.commit(&id, Access::Owner).await.unwrap();
    f.action(
        TransferAction::Cancel {
            transfer_id: id.clone(),
            side: TransferSide::Receive,
        },
        Access::Owner,
    )
    .await
    .unwrap();
    f.files.cleanup_transfers().await.unwrap();
    assert_eq!(
        f.files.receive_record(&id).await.unwrap().state,
        "completed"
    );
    assert_eq!(std::fs::read(f.path.join("copy.bin")).unwrap(), f.data);
    f.close().await;
}
#[tokio::test]
async fn current_grants_and_actor_identity_are_checked_at_commit() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    let allowed = f.peer(vec![Capability::Browse, Capability::Create]);
    f.ready_receive(&id, &m, allowed.clone()).await;
    f.chunks(&id, allowed).await;
    assert_eq!(
        f.commit(&id, f.peer(vec![Capability::Browse]))
            .await
            .unwrap_err()
            .code,
        "ACCESS_DENIED"
    );
    let other = Access::Peer {
        id: "b".repeat(64),
        grants: PeerPermissions::default(),
    };
    assert_eq!(
        f.commit(&id, other).await.unwrap_err().code,
        "TRANSFER_NOT_FOUND"
    );
    assert!(!f.path.join("copy.bin").exists());
    f.close().await;
}
#[tokio::test]
async fn commit_intent_recovery_never_blindly_publishes_staging() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    f.chunks(&id, Access::Owner).await;
    sqlx::query("UPDATE transfer_receives SET state='committing',intent_identity=stage_identity WHERE transfer_id=?").bind(&id).execute(&f.files.0.pool).await.unwrap();
    let row = f.files.receive_record(&id).await.unwrap();
    f.files.reconcile_receive(&row).await.unwrap();
    assert_eq!(
        f.files.receive_record(&id).await.unwrap().error.as_deref(),
        Some("COMMIT_NOT_APPLIED")
    );
    assert!(!f.path.join("copy.bin").exists());
    f.close().await;
}
#[tokio::test]
async fn recovery_verifies_existing_publication_before_recording_receipt() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    f.chunks(&id, Access::Owner).await;
    f.commit(&id, Access::Owner).await.unwrap();
    sqlx::query("UPDATE transfer_receives SET state='committing',result=NULL WHERE transfer_id=?")
        .bind(&id)
        .execute(&f.files.0.pool)
        .await
        .unwrap();
    f.files
        .reconcile_receive(&f.files.receive_record(&id).await.unwrap())
        .await
        .unwrap();
    assert_eq!(
        f.files.receive_record(&id).await.unwrap().state,
        "completed"
    );
    assert_eq!(std::fs::read(f.path.join("copy.bin")).unwrap(), f.data);
    f.close().await;
}
#[tokio::test]
async fn manifest_hash_binds_filename_size_version_and_order() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let mut m = f.prepare(&id).await;
    let hashes = f.files.source_record(&id).await.unwrap().hashes;
    let original = manifest_digest(&m, &hashes).unwrap();
    m.name = "changed.bin".into();
    assert_ne!(original, manifest_digest(&m, &hashes).unwrap());
    m.name = "payload.bin".into();
    let mut reversed = hashes.clone();
    reversed.reverse();
    assert_ne!(original, manifest_digest(&m, &reversed).unwrap());
    f.close().await;
}
#[tokio::test]
async fn managed_cleanup_does_not_delete_unknown_or_original_objects() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    let unknown = f.path.join(STAGE_DIR).join("unknown.bin");
    std::fs::write(&unknown, b"preserve").unwrap();
    f.action(
        TransferAction::Cancel {
            transfer_id: id.clone(),
            side: TransferSide::Receive,
        },
        Access::Owner,
    )
    .await
    .unwrap();
    f.action(
        TransferAction::Cancel {
            transfer_id: id.clone(),
            side: TransferSide::Source,
        },
        Access::Owner,
    )
    .await
    .unwrap();
    let report = f.files.cleanup_transfers().await.unwrap();
    assert_eq!(report["cleaned"], 2);
    assert!(unknown.exists());
    assert_eq!(std::fs::read(f.path.join("payload.bin")).unwrap(), f.data);
    f.close().await;
}
#[tokio::test]
async fn transfer_id_cannot_be_rebound_to_another_destination() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    let mut destination = f.destination();
    destination.name = "other.bin".into();
    assert_eq!(
        f.action(
            TransferAction::OpenReceive {
                transfer_id: id,
                destination,
                manifest: m
            },
            Access::Owner
        )
        .await
        .unwrap_err()
        .code,
        "IDEMPOTENCY_CONFLICT"
    );
    f.close().await;
}
#[tokio::test]
async fn unrecognized_staging_folder_is_not_adopted() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    std::fs::create_dir(f.path.join(STAGE_DIR)).unwrap();
    std::fs::write(f.path.join(STAGE_DIR).join("sentinel"), b"keep").unwrap();
    assert!(f
        .action(
            TransferAction::OpenReceive {
                transfer_id: id,
                destination: f.destination(),
                manifest: m
            },
            Access::Owner
        )
        .await
        .is_err());
    assert!(f.path.join(STAGE_DIR).join("sentinel").exists());
    f.close().await;
}

#[tokio::test]
async fn unresolved_transfer_intent_blocks_ordinary_root_mutations() {
    let f = Fixture::new(sample()).await;
    let id = Uuid::new_v4().to_string();
    let m = f.prepare(&id).await;
    f.ready_receive(&id, &m, Access::Owner).await;
    sqlx::query("UPDATE transfer_receives SET state='recovery_required',intent_identity=stage_identity WHERE transfer_id=?").bind(&id).execute(&f.files.0.pool).await.unwrap();
    let mutation = FileMutation {
        root_id: f.root.clone(),
        operation_id: Uuid::new_v4().to_string(),
        object_id: f.source.object_id.clone(),
        expected_version: f.source.expected_version.clone(),
        action: MutationAction::Rename {
            name: "not-renamed.bin".into(),
        },
    };
    let result = f
        .files
        .mutate(mutation, Access::Owner, f.files.gate().read_owned().await)
        .await;
    assert_eq!(result.unwrap_err().code, "OPERATION_INDETERMINATE");
    assert!(f.path.join("payload.bin").exists());
    assert!(!f.path.join("not-renamed.bin").exists());
    f.close().await;
}
