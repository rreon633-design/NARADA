use super::*;
use tempfile::TempDir;
struct Fixture {
    dir: TempDir,
    files: Files,
    root: Value,
    path: PathBuf,
}
impl Fixture {
    async fn new() -> Self {
        let base = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .join("test-fixtures");
        std::fs::create_dir_all(&base).unwrap();
        let dir = tempfile::Builder::new()
            .prefix("rust-files-")
            .tempdir_in(base)
            .unwrap();
        let path = dir.path().join("shared");
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("note.txt"), b"actual fixture bytes").unwrap();
        let core = Core::open(&dir.path().join("state")).await.unwrap();
        let files = Files::open(core, &dir.path().join("state")).await.unwrap();
        let root = files
            .register(RegisterRoot {
                path: path.to_string_lossy().into(),
                label: "Fixture".into(),
                exclusions: vec![],
            })
            .await
            .unwrap();
        Self {
            dir,
            files,
            root,
            path,
        }
    }
    async fn close(self) {
        self.files.shutdown().await;
        self.files.0.pool.close().await;
        drop(self.files);
        self.dir.close().unwrap();
    }
    fn id(&self) -> String {
        self.root["root_id"].as_str().unwrap().into()
    }
    async fn query(&self, q: FileQuery, a: Access) -> Result<Value, RpcError> {
        self.files
            .query(q, a, self.files.gate().read_owned().await)
            .await
    }
    async fn browse(&self) -> Value {
        self.query(
            FileQuery::Browse {
                root_id: self.id(),
                directory_id: None,
                cursor: None,
                sort: FileSort::Name,
            },
            Access::Owner,
        )
        .await
        .unwrap()
    }
    async fn mutate(&self, e: &Value, action: MutationAction) -> Result<Value, RpcError> {
        self.files
            .mutate(
                FileMutation {
                    root_id: self.id(),
                    operation_id: uuid::Uuid::new_v4().to_string(),
                    object_id: e["object_id"].as_str().unwrap().into(),
                    expected_version: e["version"].as_str().unwrap().into(),
                    action,
                },
                Access::Owner,
                self.files.gate().read_owned().await,
            )
            .await
    }
}
#[tokio::test]
async fn root_registration_is_explicit_and_never_grants_a_peer() {
    let f = Fixture::new().await;
    let peer = Access::Peer {
        id: "fixture-peer".into(),
        grants: PeerPermissions::default(),
    };
    assert!(
        f.query(FileQuery::Roots {}, peer.clone()).await.unwrap()["roots"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert!(f
        .query(
            FileQuery::Browse {
                root_id: f.id(),
                directory_id: None,
                cursor: None,
                sort: FileSort::Name
            },
            peer
        )
        .await
        .is_err());
    f.close().await;
}
#[tokio::test]
async fn overlap_and_profile_ancestors_are_rejected() {
    let f = Fixture::new().await;
    for p in [f.path.clone(), f.dir.path().to_owned()] {
        assert!(f
            .files
            .register(RegisterRoot {
                path: p.to_string_lossy().into(),
                label: "No".into(),
                exclusions: vec![]
            })
            .await
            .is_err());
    }
    f.close().await;
}
#[tokio::test]
async fn stale_versions_and_overwrite_are_refused() {
    let f = Fixture::new().await;
    let e = f.browse().await["entries"][0].clone();
    std::fs::write(f.path.join("note.txt"), b"changed fixture bytes").unwrap();
    assert_eq!(
        f.mutate(&e, MutationAction::Trash {})
            .await
            .unwrap_err()
            .code,
        "STALE_VERSION"
    );
    f.files.reindex(f.id()).await.unwrap();
    let e = f.browse().await["entries"][0].clone();
    std::fs::write(f.path.join("taken.txt"), b"do not replace").unwrap();
    assert_eq!(
        f.mutate(
            &e,
            MutationAction::Rename {
                name: "taken.txt".into()
            }
        )
        .await
        .unwrap_err()
        .code,
        "NAME_COLLISION"
    );
    assert_eq!(
        std::fs::read(f.path.join("taken.txt")).unwrap(),
        b"do not replace"
    );
    f.close().await;
}
#[tokio::test]
async fn rename_trash_restore_is_actual_and_non_destructive() {
    let f = Fixture::new().await;
    let e = f.browse().await["entries"][0].clone();
    f.mutate(
        &e,
        MutationAction::Rename {
            name: "renamed.txt".into(),
        },
    )
    .await
    .unwrap();
    assert!(!f.path.join("note.txt").exists());
    let e = f.browse().await["entries"][0].clone();
    f.mutate(&e, MutationAction::Trash {}).await.unwrap();
    assert!(!f.path.join("renamed.txt").exists());
    let t = f
        .query(
            FileQuery::Trash {
                root_id: f.id(),
                cursor: None,
            },
            Access::Owner,
        )
        .await
        .unwrap();
    f.mutate(&t["entries"][0], MutationAction::Restore { name: None })
        .await
        .unwrap();
    assert_eq!(
        std::fs::read(f.path.join("renamed.txt")).unwrap(),
        b"actual fixture bytes"
    );
    f.close().await;
}
#[tokio::test]
async fn receipt_is_idempotent_and_body_bound() {
    let f = Fixture::new().await;
    let e = f.browse().await["entries"][0].clone();
    let mut op = FileMutation {
        root_id: f.id(),
        operation_id: uuid::Uuid::new_v4().to_string(),
        object_id: e["object_id"].as_str().unwrap().into(),
        expected_version: e["version"].as_str().unwrap().into(),
        action: MutationAction::Rename {
            name: "done.txt".into(),
        },
    };
    let first = f
        .files
        .mutate(op.clone(), Access::Owner, f.files.gate().read_owned().await)
        .await
        .unwrap();
    assert_eq!(
        first,
        f.files
            .mutate(op.clone(), Access::Owner, f.files.gate().read_owned().await)
            .await
            .unwrap()
    );
    op.action = MutationAction::Rename {
        name: "different.txt".into(),
    };
    assert_eq!(
        f.files
            .mutate(op, Access::Owner, f.files.gate().read_owned().await)
            .await
            .unwrap_err()
            .code,
        "IDEMPOTENCY_CONFLICT"
    );
    f.close().await;
}
#[tokio::test]
async fn exclusion_changes_invalidate_cursors_and_objects() {
    let f = Fixture::new().await;
    for n in 0..35 {
        std::fs::write(f.path.join(format!("file-{n:02}.txt")), b"fixture").unwrap();
    }
    f.files.reindex(f.id()).await.unwrap();
    let page = f.browse().await;
    let cursor = page["next_cursor"].as_str().unwrap().to_string();
    f.files
        .policy(RootPolicy {
            root_id: f.id(),
            expected_revision: 1,
            exclusions: vec!["note.txt".into()],
        })
        .await
        .unwrap();
    assert_eq!(
        f.query(
            FileQuery::Browse {
                root_id: f.id(),
                directory_id: None,
                cursor: Some(cursor),
                sort: FileSort::Name
            },
            Access::Owner
        )
        .await
        .unwrap_err()
        .code,
        "CURSOR_STALE"
    );
    assert!(!f.browse().await["entries"].to_string().contains("note.txt"));
    f.close().await;
}
#[tokio::test]
async fn nonempty_directories_are_not_moved_implicitly() {
    let f = Fixture::new().await;
    std::fs::create_dir(f.path.join("folder")).unwrap();
    std::fs::write(f.path.join("folder/.private"), b"excluded fixture").unwrap();
    f.files.reindex(f.id()).await.unwrap();
    let page = f.browse().await;
    let dir = page["entries"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["name"] == "folder")
        .unwrap();
    assert_eq!(
        f.mutate(dir, MutationAction::Trash {})
            .await
            .unwrap_err()
            .code,
        "DIRECTORY_NOT_EMPTY"
    );
    assert!(f.path.join("folder/.private").exists());
    f.close().await;
}
#[tokio::test]
async fn unregister_preserves_files_trash_and_ownership() {
    let f = Fixture::new().await;
    let e = f.browse().await["entries"][0].clone();
    f.mutate(&e, MutationAction::Trash {}).await.unwrap();
    f.files
        .remove(RootRevision {
            root_id: f.id(),
            expected_revision: 1,
        })
        .await
        .unwrap();
    assert_eq!(f.files.count().await.unwrap(), 0);
    assert!(f.path.join(".narada-trash").exists());
    let r = f
        .files
        .register(RegisterRoot {
            path: f.path.to_string_lossy().into(),
            label: "Again".into(),
            exclusions: vec![],
        })
        .await
        .unwrap();
    assert_eq!(r["root_id"], f.root["root_id"]);
    let t = f
        .query(
            FileQuery::Trash {
                root_id: f.id(),
                cursor: None,
            },
            Access::Owner,
        )
        .await
        .unwrap();
    assert_eq!(t["entries"].as_array().unwrap().len(), 1);
    f.close().await;
}
#[tokio::test]
async fn preview_reencodes_pixels_and_enforces_read_scope() {
    let f = Fixture::new().await;
    image::RgbImage::from_pixel(20, 10, image::Rgb([80, 130, 50]))
        .save(f.path.join("image.png"))
        .unwrap();
    f.files.reindex(f.id()).await.unwrap();
    let page = f.browse().await;
    let e = page["entries"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["name"] == "image.png")
        .unwrap();
    let q = FileQuery::Preview {
        root_id: f.id(),
        object_id: e["object_id"].as_str().unwrap().into(),
        expected_version: e["version"].as_str().unwrap().into(),
    };
    let p = f.query(q.clone(), Access::Owner).await.unwrap();
    assert_eq!(p["mime"], "image/jpeg");
    assert_eq!(p["width"], 20);
    let peer = Access::Peer {
        id: "peer".into(),
        grants: PeerPermissions {
            roots: vec![RootGrant {
                root_id: f.id(),
                capabilities: vec![Capability::Browse],
            }],
            ..Default::default()
        },
    };
    assert_eq!(f.query(q, peer).await.unwrap_err().code, "ACCESS_DENIED");
    f.close().await;
}
#[cfg(target_os = "linux")]
#[tokio::test]
async fn symlink_hardlink_and_fifo_are_never_indexed_or_followed() {
    use std::os::unix::fs::symlink;
    let f = Fixture::new().await;
    let outside = f.dir.path().join("private.txt");
    std::fs::write(&outside, b"outside sentinel").unwrap();
    symlink(&outside, f.path.join("escape.txt")).unwrap();
    std::fs::hard_link(&outside, f.path.join("hard.txt")).unwrap();
    std::fs::create_dir(f.path.join("folder")).unwrap();
    symlink(f.dir.path(), f.path.join("outside-dir")).unwrap();
    let fifo = f.path.join("pipe");
    std::process::Command::new("mkfifo")
        .arg(&fifo)
        .status()
        .unwrap();
    f.files.reindex(f.id()).await.unwrap();
    let text = f.browse().await.to_string();
    for name in ["escape.txt", "hard.txt", "outside-dir", "pipe"] {
        assert!(!text.contains(name));
    }
    assert_eq!(std::fs::read(outside).unwrap(), b"outside sentinel");
    f.close().await;
}
#[cfg(target_os = "linux")]
#[tokio::test]
async fn a_replaced_root_is_not_silently_adopted() {
    use std::os::unix::fs::symlink;
    let f = Fixture::new().await;
    std::fs::rename(&f.path, f.dir.path().join("old")).unwrap();
    symlink(f.dir.path(), &f.path).unwrap();
    assert!(f.files.reindex(f.id()).await.is_err());
    assert!(f
        .query(
            FileQuery::Browse {
                root_id: f.id(),
                directory_id: None,
                cursor: None,
                sort: FileSort::Name
            },
            Access::Owner
        )
        .await
        .is_err());
    f.close().await;
}

#[tokio::test]
async fn cancelled_waiter_keeps_its_policy_lease_and_durable_operation() {
    let f = Fixture::new().await;
    let e = f.browse().await["entries"][0].clone();
    let op = FileMutation {
        root_id: f.id(),
        operation_id: uuid::Uuid::new_v4().to_string(),
        object_id: e["object_id"].as_str().unwrap().into(),
        expected_version: e["version"].as_str().unwrap().into(),
        action: MutationAction::Rename {
            name: "completed.txt".into(),
        },
    };
    let gate = f.files.gate();
    let io = f.files.0.io.lock().await;
    let files = f.files.clone();
    let input = op.clone();
    let read = gate.clone().read_owned().await;
    let waiter = tokio::spawn(async move { files.mutate(input, Access::Owner, read).await });
    tokio::time::timeout(Duration::from_secs(2), async {
        while f.files.0.jobs.available_permits() == 8 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    waiter.abort();
    let _ = waiter.await;
    assert!(
        gate.clone().try_write_owned().is_err(),
        "A cancelled client must not release the in-flight operation's policy lease"
    );
    drop(io);
    let write = tokio::time::timeout(Duration::from_secs(3), gate.clone().write_owned())
        .await
        .unwrap();
    assert_eq!(
        std::fs::read(f.path.join("completed.txt")).unwrap(),
        b"actual fixture bytes"
    );
    drop(write);
    assert_eq!(
        f.files
            .mutate(op, Access::Owner, gate.read_owned().await)
            .await
            .unwrap()["state"],
        "committed"
    );
    f.close().await;
}

// Desktop sync/pin regression fixtures: no sockets, no simulated native claims.
async fn offline_page(
    f: &Fixture,
    q: SyncRequest,
    access: Access,
    epoch: &str,
) -> Result<SyncPage, RpcError> {
    f.files
        .sync_query(q, access, f.files.gate().read_owned().await, epoch)
        .await
}
fn offline_access(f: &Fixture, caps: Vec<Capability>) -> Access {
    Access::Peer {
        id: "a".repeat(64),
        grants: PeerPermissions {
            chat: false,
            attachment: false,
            roots: vec![RootGrant {
                root_id: f.id(),
                capabilities: caps,
            }],
        },
    }
}
async fn offline_pin(f: &Fixture) -> String {
    let entry = f
        .files
        .entries(&f.id())
        .await
        .unwrap()
        .into_iter()
        .find(|e| e.name == "note.txt")
        .unwrap();
    let id = uuid::Uuid::new_v4().to_string();
    f.files
        .create_pin(
            CreatePin {
                pin_id: id.clone(),
                peer_id: "a".repeat(64),
                source: TransferSource {
                    root_id: f.id(),
                    object_id: entry.object_id,
                    expected_version: entry.info.version,
                },
                destination: TransferDestination {
                    root_id: f.id(),
                    directory_id: None,
                    name: "pinned.txt".into(),
                },
            },
            entry.info.size,
        )
        .await
        .unwrap();
    id
}
async fn offline_action(f: &Fixture, action: TransferAction) -> Result<Value, RpcError> {
    f.files
        .transfer_action(action, Access::Owner, f.files.gate().read_owned().await)
        .await
}
async fn offline_complete_pin(f: &Fixture, id: &str) -> TransferReceipt {
    let p = f
        .files
        .pin_work()
        .await
        .unwrap()
        .into_iter()
        .find(|p| p.pin_id == id)
        .unwrap();
    let job = p.pending_request.unwrap();
    let transfer = job.transfer_id;
    assert!(f.files.claim_transfer_job(&transfer).await.unwrap());
    let prepared: TransferStatus = serde_json::from_value(
        offline_action(
            f,
            TransferAction::PrepareSync {
                transfer_id: transfer.clone(),
                source: job.source,
            },
        )
        .await
        .unwrap(),
    )
    .unwrap();
    let manifest = prepared.manifest.unwrap();
    offline_action(
        f,
        TransferAction::OpenReceive {
            transfer_id: transfer.clone(),
            destination: job.destination,
            manifest: manifest.clone(),
        },
    )
    .await
    .unwrap();
    let page: ManifestPage = serde_json::from_value(
        offline_action(
            f,
            TransferAction::Manifest {
                transfer_id: transfer.clone(),
                offset: 0,
            },
        )
        .await
        .unwrap(),
    )
    .unwrap();
    offline_action(
        f,
        TransferAction::ManifestPage {
            transfer_id: transfer.clone(),
            offset: 0,
            hashes: page.hashes,
        },
    )
    .await
    .unwrap();
    for index in 0..manifest.chunk_count {
        let bytes = f
            .files
            .transfer_chunk(
                transfer.clone(),
                index,
                None,
                Access::Owner,
                f.files.gate().read_owned().await,
            )
            .await
            .unwrap();
        f.files
            .transfer_chunk(
                transfer.clone(),
                index,
                Some(bytes),
                Access::Owner,
                f.files.gate().read_owned().await,
            )
            .await
            .unwrap();
    }
    let receipt = offline_action(
        f,
        TransferAction::Commit {
            transfer_id: transfer.clone(),
        },
    )
    .await
    .unwrap();
    f.files
        .transfer_completed(&transfer, &manifest, receipt.clone(), false)
        .await
        .unwrap();
    f.files.reconcile_pins().await.unwrap();
    serde_json::from_value(receipt).unwrap()
}
async fn offline_cache(f: &Fixture) {
    f.files.reindex(f.id()).await.unwrap();
    let page = offline_page(
        f,
        SyncRequest::Snapshot {
            root_id: f.id(),
            cursor: None,
        },
        Access::Owner,
        "owner",
    )
    .await
    .unwrap();
    assert!(page.next_cursor.is_none());
    f.files
        .apply_sync(
            &"a".repeat(64),
            &f.id(),
            Some(page.entries),
            vec![],
            &page.changes_cursor.unwrap(),
        )
        .await
        .unwrap();
}
#[tokio::test]
async fn offline_snapshot_is_materialized_at_a_high_water_fence() {
    let f = Fixture::new().await;
    for i in 0..35 {
        std::fs::write(f.path.join(format!("stable-{i:02}.txt")), b"frozen version").unwrap();
    }
    f.files.reindex(f.id()).await.unwrap();
    let first = offline_page(
        &f,
        SyncRequest::Snapshot {
            root_id: f.id(),
            cursor: None,
        },
        Access::Owner,
        "owner",
    )
    .await
    .unwrap();
    let fence = first.fence;
    let mut values = first.entries;
    let mut cursor = first.next_cursor;
    std::fs::remove_file(f.path.join("stable-30.txt")).unwrap();
    std::fs::write(f.path.join("new-after-fence.txt"), b"new").unwrap();
    f.files.reindex(f.id()).await.unwrap();
    let changes = loop {
        let page = offline_page(
            &f,
            SyncRequest::Snapshot {
                root_id: f.id(),
                cursor,
            },
            Access::Owner,
            "owner",
        )
        .await
        .unwrap();
        assert_eq!(page.fence, fence);
        values.extend(page.entries);
        cursor = page.next_cursor;
        if cursor.is_none() {
            break page.changes_cursor.unwrap();
        }
    };
    assert!(values.iter().any(|e| e.name == "stable-30.txt"));
    assert!(!values.iter().any(|e| e.name == "new-after-fence.txt"));
    let page = offline_page(
        &f,
        SyncRequest::Changes {
            root_id: f.id(),
            cursor: changes,
        },
        Access::Owner,
        "owner",
    )
    .await
    .unwrap();
    assert!(page.events.iter().any(|e| e.kind == "deleted"));
    assert!(page.events.iter().any(|e| e
        .entry
        .as_ref()
        .is_some_and(|e| e.name == "new-after-fence.txt")));
    f.close().await;
}
#[tokio::test]
async fn offline_cursor_binds_actor_grant_revision_and_session_epoch() {
    let f = Fixture::new().await;
    let caps = vec![Capability::Browse, Capability::Read, Capability::SyncRead];
    let page = offline_page(
        &f,
        SyncRequest::Snapshot {
            root_id: f.id(),
            cursor: None,
        },
        offline_access(&f, caps.clone()),
        "7:11",
    )
    .await
    .unwrap();
    let q = SyncRequest::Changes {
        root_id: f.id(),
        cursor: page.changes_cursor.unwrap(),
    };
    assert_eq!(
        offline_page(&f, q.clone(), offline_access(&f, caps.clone()), "8:12")
            .await
            .unwrap_err()
            .code,
        "CURSOR_EXPIRED"
    );
    assert_eq!(
        offline_page(&f, q, Access::Owner, "7:11")
            .await
            .unwrap_err()
            .code,
        "CURSOR_EXPIRED"
    );
    assert_eq!(
        offline_page(
            &f,
            SyncRequest::Snapshot {
                root_id: f.id(),
                cursor: None
            },
            offline_access(&f, vec![Capability::Browse, Capability::Read]),
            "7:11"
        )
        .await
        .unwrap_err()
        .code,
        "ACCESS_DENIED"
    );
    f.close().await;
}
#[tokio::test]
async fn offline_cursor_expires_after_restart_scope_change_or_journal_pruning() {
    let f = Fixture::new().await;
    let page = offline_page(
        &f,
        SyncRequest::Snapshot {
            root_id: f.id(),
            cursor: None,
        },
        Access::Owner,
        "owner",
    )
    .await
    .unwrap();
    let cursor = page.changes_cursor.unwrap();
    sqlx::query("UPDATE file_roots SET sync_floor=? WHERE root_id=?")
        .bind(page.fence as i64 + 1)
        .bind(f.id())
        .execute(&f.files.0.pool)
        .await
        .unwrap();
    assert_eq!(
        offline_page(
            &f,
            SyncRequest::Changes {
                root_id: f.id(),
                cursor: cursor.clone()
            },
            Access::Owner,
            "owner"
        )
        .await
        .unwrap_err()
        .code,
        "CURSOR_EXPIRED"
    );
    f.files.0.sync_views.lock().unwrap().clear();
    assert_eq!(
        offline_page(
            &f,
            SyncRequest::Changes {
                root_id: f.id(),
                cursor
            },
            Access::Owner,
            "owner"
        )
        .await
        .unwrap_err()
        .code,
        "CURSOR_EXPIRED"
    );
    f.close().await;
}
#[tokio::test]
async fn offline_four_stable_snapshots_bound_memory() {
    let f = Fixture::new().await;
    for i in 0..20 {
        std::fs::write(f.path.join(format!("part-{i}.txt")), b"data").unwrap();
    }
    f.files.reindex(f.id()).await.unwrap();
    for _ in 0..4 {
        assert!(offline_page(
            &f,
            SyncRequest::Snapshot {
                root_id: f.id(),
                cursor: None
            },
            Access::Owner,
            "owner"
        )
        .await
        .unwrap()
        .next_cursor
        .is_some());
    }
    assert_eq!(
        offline_page(
            &f,
            SyncRequest::Snapshot {
                root_id: f.id(),
                cursor: None
            },
            Access::Owner,
            "owner"
        )
        .await
        .unwrap_err()
        .code,
        "SYNC_BUSY"
    );
    f.close().await;
}
#[tokio::test]
async fn offline_permission_reduction_blocks_existing_sync_source_chunks() {
    let f = Fixture::new().await;
    let e = f.files.entries(&f.id()).await.unwrap().remove(0);
    let id = uuid::Uuid::new_v4().to_string();
    let source = TransferSource {
        root_id: f.id(),
        object_id: e.object_id,
        expected_version: e.info.version,
    };
    f.files
        .transfer_action(
            TransferAction::PrepareSync {
                transfer_id: id.clone(),
                source,
            },
            offline_access(
                &f,
                vec![Capability::Browse, Capability::Read, Capability::SyncRead],
            ),
            f.files.gate().read_owned().await,
        )
        .await
        .unwrap();
    assert_eq!(
        f.files
            .transfer_chunk(
                id,
                0,
                None,
                offline_access(&f, vec![Capability::Browse, Capability::Read]),
                f.files.gate().read_owned().await
            )
            .await
            .unwrap_err()
            .code,
        "ACCESS_DENIED"
    );
    f.close().await;
}
#[tokio::test]
async fn offline_pin_refresh_preserves_local_edits_and_prior_version() {
    let f = Fixture::new().await;
    let id = offline_pin(&f).await;
    let first = offline_complete_pin(&f, &id).await;
    assert_eq!(first.name, "pinned.txt");
    std::fs::write(f.path.join("pinned.txt"), b"USER_EDIT_MUST_SURVIVE").unwrap();
    std::fs::write(
        f.path.join("note.txt"),
        b"New source version with different bytes",
    )
    .unwrap();
    offline_cache(&f).await;
    assert_eq!(f.files.pin_work().await.unwrap()[0].state, "refresh_needed");
    f.files.refresh_pin(&id).await.unwrap();
    let second = offline_complete_pin(&f, &id).await;
    assert_ne!(first.name, second.name);
    assert_eq!(
        std::fs::read(f.path.join("pinned.txt")).unwrap(),
        b"USER_EDIT_MUST_SURVIVE"
    );
    assert_eq!(
        std::fs::read(f.path.join(second.name)).unwrap(),
        b"New source version with different bytes"
    );
    assert_eq!(
        f.files.pins_owner().await.unwrap()["pins"][0]["versions"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    f.close().await;
}
#[tokio::test]
async fn offline_tombstone_never_deletes_the_last_published_copy() {
    let f = Fixture::new().await;
    let id = offline_pin(&f).await;
    offline_complete_pin(&f, &id).await;
    std::fs::remove_file(f.path.join("note.txt")).unwrap();
    offline_cache(&f).await;
    assert_eq!(f.files.pin_work().await.unwrap()[0].state, "source_deleted");
    assert_eq!(
        std::fs::read(f.path.join("pinned.txt")).unwrap(),
        b"actual fixture bytes"
    );
    f.close().await;
}
#[tokio::test]
async fn offline_unpin_and_cleanup_keep_user_publications() {
    let f = Fixture::new().await;
    let id = offline_pin(&f).await;
    offline_complete_pin(&f, &id).await;
    f.files
        .control_pin(PinControl {
            pin_id: id.clone(),
            action: PinAction::Unpin,
        })
        .await
        .unwrap();
    sqlx::query("UPDATE pins SET updated_at=0 WHERE pin_id=?")
        .bind(id)
        .execute(&f.files.0.pool)
        .await
        .unwrap();
    f.files.cleanup_transfers().await.unwrap();
    assert!(f.files.pin_work().await.unwrap().is_empty());
    assert!(f.path.join("pinned.txt").exists());
    f.close().await;
}
#[tokio::test]
async fn offline_pending_receipt_is_protected_from_transfer_history_cleanup() {
    let f = Fixture::new().await;
    let id = offline_pin(&f).await;
    let pin = f.files.pin_work().await.unwrap().remove(0);
    let job = pin.pending.unwrap();
    sqlx::query("UPDATE transfer_jobs SET state='completed',updated_at=0 WHERE transfer_id=?")
        .bind(&job)
        .execute(&f.files.0.pool)
        .await
        .unwrap();
    f.files.cleanup_transfers().await.unwrap();
    assert!(f.files.transfer_job(&job).await.is_ok());
    // This is deliberately an incomplete synthetic receipt; cleanup is not allowed
    // to destroy it and turn an unknown publication into a new transfer.
    assert_eq!(
        f.files.reconcile_pins().await.unwrap_err().code,
        "STORAGE_UNAVAILABLE"
    );
    assert_eq!(f.files.pin_work().await.unwrap()[0].pin_id, id);
    f.close().await;
}
#[tokio::test]
async fn offline_stale_pin_intent_needs_a_new_metadata_fence() {
    let f = Fixture::new().await;
    let id = offline_pin(&f).await;
    let job = f.files.pin_work().await.unwrap()[0]
        .pending
        .clone()
        .unwrap();
    sqlx::query(
        "UPDATE transfer_jobs SET state='blocked',error_code='STALE_VERSION' WHERE transfer_id=?",
    )
    .bind(&job)
    .execute(&f.files.0.pool)
    .await
    .unwrap();
    f.files.reconcile_pins().await.unwrap();
    f.files
        .control_pin(PinControl {
            pin_id: id,
            action: PinAction::Refresh,
        })
        .await
        .unwrap();
    let p = f.files.pin_work().await.unwrap().remove(0);
    assert!(p.pending.is_none());
    assert_eq!(p.state, "check_requested");
    assert_eq!(f.files.transfer_job(&job).await.unwrap().state, "cancelled");
    f.close().await;
}
#[tokio::test]
async fn offline_cache_and_cursor_are_committed_as_one_transaction() {
    let f = Fixture::new().await;
    offline_pin(&f).await;
    offline_cache(&f).await;
    let old = f.files.sync_cursor(&"a".repeat(64), &f.id()).await.unwrap();
    let event = SyncEvent {
        sequence: 999,
        kind: "upsert".into(),
        object_id: uuid::Uuid::new_v4().to_string(),
        entry: Some(SyncEntry {
            object_id: uuid::Uuid::new_v4().to_string(),
            parent_id: None,
            name: "bad".into(),
            relative_path: "../escape".into(),
            kind: "file".into(),
            size: "1".into(),
            version: "a".repeat(64),
        }),
    };
    assert!(f
        .files
        .apply_sync(
            &"a".repeat(64),
            &f.id(),
            None,
            vec![event],
            &uuid::Uuid::new_v4().to_string()
        )
        .await
        .is_err());
    assert_eq!(
        old,
        f.files.sync_cursor(&"a".repeat(64), &f.id()).await.unwrap()
    );
    f.close().await;
}
#[tokio::test]
async fn offline_pin_intents_are_idempotent_and_quota_bounded() {
    let f = Fixture::new().await;
    let id = offline_pin(&f).await;
    let p = f.files.pin_work().await.unwrap().remove(0);
    let input = CreatePin {
        pin_id: id,
        peer_id: p.peer_id,
        source: p.source,
        destination: p.destination,
    };
    f.files.create_pin(input.clone(), 20).await.unwrap();
    let mut changed = input;
    changed.destination.name = "different.txt".into();
    assert_eq!(
        f.files.create_pin(changed, 20).await.unwrap_err().code,
        "IDEMPOTENCY_CONFLICT"
    );
    sqlx::query("UPDATE pins SET reservation=?")
        .bind(128i64 * 1024 * 1024)
        .execute(&f.files.0.pool)
        .await
        .unwrap();
    let e = f.files.entries(&f.id()).await.unwrap().remove(0);
    let over = CreatePin {
        pin_id: uuid::Uuid::new_v4().to_string(),
        peer_id: "a".repeat(64),
        source: TransferSource {
            root_id: f.id(),
            object_id: e.object_id,
            expected_version: e.info.version,
        },
        destination: TransferDestination {
            root_id: f.id(),
            directory_id: None,
            name: "other.txt".into(),
        },
    };
    assert_eq!(
        f.files.create_pin(over, 1).await.unwrap_err().code,
        "PIN_QUOTA"
    );
    f.close().await;
}

#[tokio::test]
async fn shutdown_waits_for_inflight_blocking_index_handles() {
    let f = Fixture::new().await;
    // Drain the ordinary worker, then install a deterministic in-flight scan.
    f.files.shutdown().await;
    f.files.0.stopped.store(false, Ordering::Release);
    let (started_tx, started_rx) = tokio::sync::oneshot::channel();
    let (release_tx, release_rx) = std::sync::mpsc::channel();
    let path = f.path.clone();
    let finished = Arc::new(AtomicBool::new(false));
    let done = finished.clone();
    let worker = tokio::spawn(async move {
        tokio::task::spawn_blocking(move || {
            let pinned = safe_fs::SafeRoot::open(&path, None).unwrap();
            started_tx.send(()).unwrap();
            release_rx.recv_timeout(Duration::from_secs(10)).unwrap();
            drop(pinned);
            done.store(true, Ordering::Release);
        }).await.unwrap();
    });
    *f.files.0.task.lock().await = Some(worker);
    tokio::time::timeout(Duration::from_secs(10), started_rx).await.unwrap().unwrap();
    let files = f.files.clone();
    let mut shutdown = tokio::spawn(async move { files.shutdown().await });
    assert!(tokio::time::timeout(Duration::from_millis(100), &mut shutdown).await.is_err(),
        "Shutdown must not abandon the blocking operation and its pinned handles");
    assert!(!finished.load(Ordering::Acquire));
    release_tx.send(()).unwrap();
    tokio::time::timeout(Duration::from_secs(10), shutdown).await.unwrap().unwrap();
    assert!(finished.load(Ordering::Acquire));
    f.close().await;
}
