//! Stable bounded index snapshots followed by an ordered journal. Cursors are
//! opaque, actor/scope-bound and short lived; expiry always requires a snapshot.
use super::*;
use sha2::{Digest, Sha256};
const PAGE: usize = 16;
const SNAPSHOT_BYTES: usize = 2 * 1024 * 1024;
#[derive(Clone)]
pub(super) struct SyncView {
    root: String,
    actor: String,
    scope: String,
    epoch: u64,
    fence: u64,
    offset: usize,
    rows: Option<Arc<Vec<SyncEntry>>>,
    snapshot: String,
    until: Instant,
}
fn view(e: &Entry, entries: &[Entry]) -> SyncEntry {
    SyncEntry {
        object_id: e.object_id.clone(),
        parent_id: entries
            .iter()
            .find(|p| p.relative_path == e.parent_path)
            .map(|p| p.object_id.clone()),
        name: e.name.clone(),
        relative_path: e.relative_path.clone(),
        kind: e.info.kind.clone(),
        size: e.info.size.to_string(),
        version: e.info.version.clone(),
    }
}
fn scope(r: &Root, a: &Access, lease_scope: &str) -> Result<String, RpcError> {
    let values = serde_json::to_vec(&(
        a.actor(),
        a.caps(&r.id),
        r.revision,
        &r.identity,
        lease_scope,
    ))
    .map_err(|_| RpcError::storage())?;
    Ok(hex::encode(Sha256::digest(values)))
}
impl Files {
    fn save_sync_cursor(&self, token: String, next: SyncView) -> Result<(), RpcError> {
        let mut cursors = self.0.sync_views.lock().map_err(|_| RpcError::storage())?;
        cursors.retain(|_, v| v.until > Instant::now());
        if cursors.len() >= 2048 {
            return Err(err("SYNC_BUSY", "The bounded cursor set is full."));
        }
        cursors.insert(token, next);
        Ok(())
    }
    pub(super) async fn journal_index(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
        root: &str,
        old: &[Entry],
        new: &[Entry],
    ) -> Result<(), RpcError> {
        let before: HashMap<&str, &Entry> = old.iter().map(|e| (e.object_id.as_str(), e)).collect();
        let after: HashMap<&str, &Entry> = new.iter().map(|e| (e.object_id.as_str(), e)).collect();
        let t = chrono::Utc::now().timestamp();
        for e in new {
            let changed = before.get(e.object_id.as_str()).is_none_or(|o| {
                o.info.version != e.info.version || o.relative_path != e.relative_path
            });
            if changed {
                sqlx::query("INSERT INTO file_journal(root_id,kind,object_id,entry,created_at) VALUES(?,'upsert',?,?,?)").bind(root).bind(&e.object_id).bind(serde_json::to_string(&view(e,new)).map_err(|_|RpcError::storage())?).bind(t).execute(&mut **tx).await.map_err(|_|RpcError::storage())?;
            }
        }
        for e in old {
            if !after.contains_key(e.object_id.as_str()) {
                sqlx::query("INSERT INTO file_journal(root_id,kind,object_id,created_at) VALUES(?,'deleted',?,?)").bind(root).bind(&e.object_id).bind(t).execute(&mut **tx).await.map_err(|_|RpcError::storage())?;
            }
        }
        let cutoff:Option<i64>=sqlx::query_scalar("SELECT sequence FROM file_journal WHERE root_id=? ORDER BY sequence DESC LIMIT 1 OFFSET 2048").bind(root).fetch_optional(&mut **tx).await.map_err(|_|RpcError::storage())?;
        if let Some(cutoff) = cutoff {
            sqlx::query("DELETE FROM file_journal WHERE root_id=? AND sequence<=?")
                .bind(root)
                .bind(cutoff)
                .execute(&mut **tx)
                .await
                .map_err(|_| RpcError::storage())?;
            sqlx::query("UPDATE file_roots SET sync_floor=MAX(sync_floor,?) WHERE root_id=?")
                .bind(cutoff)
                .bind(root)
                .execute(&mut **tx)
                .await
                .map_err(|_| RpcError::storage())?;
        }
        Ok(())
    }
    pub async fn sync_query(
        &self,
        request: SyncRequest,
        access: Access,
        _guard: OwnedRwLockReadGuard<()>,
        lease_scope: &str,
    ) -> Result<SyncPage, RpcError> {
        file_id(request.root())?;
        access.require(request.root(), Capability::Read)?;
        access.require(request.root(), Capability::SyncRead)?;
        let _io = self.0.io.lock().await;
        let r = self.root(request.root()).await?;
        if matches!(r.state.as_str(), "blocked" | "indexing") {
            return Err(err("INDEX_UNAVAILABLE", "The approved index is not ready."));
        }
        let copy = r.clone();
        blocking(move || {
            safe_fs::SafeRoot::open(&copy.path, Some(&copy.identity)).and_then(|r| r.verify())
        })
        .await?;
        let current_scope = scope(&r, &access, lease_scope)?;
        let high: i64 = sqlx::query_scalar(
            "SELECT COALESCE(MAX(sequence),0) FROM file_journal WHERE root_id=?",
        )
        .bind(&r.id)
        .fetch_one(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        let selected = match &request {
            SyncRequest::Snapshot {
                cursor: Some(c), ..
            }
            | SyncRequest::Changes { cursor: c, .. } => {
                file_id(c)?;
                let mut cursors = self.0.sync_views.lock().map_err(|_| RpcError::storage())?;
                cursors.retain(|_, v| v.until > Instant::now());
                cursors.get(c).cloned().ok_or_else(|| {
                    err(
                        "CURSOR_EXPIRED",
                        "This journal/snapshot cursor expired; request a new snapshot.",
                    )
                })?
            }
            SyncRequest::Snapshot { cursor: None, .. } => {
                let entries = self.entries(&r.id).await?;
                let rows = entries
                    .iter()
                    .map(|e| view(e, &entries))
                    .collect::<Vec<_>>();
                if serde_json::to_vec(&rows)
                    .map_err(|_| RpcError::storage())?
                    .len()
                    > SNAPSHOT_BYTES
                {
                    return Err(err(
                        "SYNC_SNAPSHOT_LIMIT",
                        "This index exceeds the 2 MiB snapshot bound; narrow the approved root.",
                    ));
                }
                let mut cursors = self.0.sync_views.lock().map_err(|_| RpcError::storage())?;
                cursors.retain(|_, v| v.until > Instant::now());
                let distinct = cursors
                    .values()
                    .filter(|v| v.rows.is_some())
                    .map(|v| v.snapshot.as_str())
                    .collect::<std::collections::HashSet<_>>()
                    .len();
                if distinct >= 4 {
                    return Err(err("SYNC_BUSY","Four stable snapshots are already retained. Retry after their short expiry."));
                }
                SyncView {
                    root: r.id.clone(),
                    actor: access.actor().into(),
                    scope: current_scope.clone(),
                    epoch: r.epoch,
                    fence: high as u64,
                    offset: 0,
                    rows: Some(Arc::new(rows)),
                    snapshot: uuid::Uuid::new_v4().to_string(),
                    until: Instant::now() + Duration::from_secs(120),
                }
            }
        };
        if selected.root != r.id
            || selected.actor != access.actor()
            || selected.scope != current_scope
            || selected.epoch > r.epoch
        {
            return Err(err(
                "CURSOR_EXPIRED",
                "The cursor no longer matches the approved identity or permission scope.",
            ));
        }
        match request {
            SyncRequest::Snapshot { .. } => {
                let rows = selected.rows.as_ref().ok_or_else(|| {
                    err(
                        "CURSOR_EXPIRED",
                        "Use a snapshot cursor for snapshot pages.",
                    )
                })?;
                let end = (selected.offset + PAGE).min(rows.len());
                let values = rows[selected.offset..end].to_vec();
                let done = end == rows.len();
                let token = uuid::Uuid::new_v4().to_string();
                let mut next = selected.clone();
                next.offset = end;
                if done {
                    next.rows = None;
                    next.until = Instant::now() + Duration::from_secs(120);
                }
                self.save_sync_cursor(token.clone(), next)?;
                Ok(SyncPage {
                    root_id: r.id,
                    mode: "snapshot".into(),
                    entries: values,
                    events: Vec::new(),
                    next_cursor: if done { None } else { Some(token.clone()) },
                    changes_cursor: if done { Some(token) } else { None },
                    fence: selected.fence,
                })
            }
            SyncRequest::Changes { .. } => {
                if selected.rows.is_some() {
                    return Err(err(
                        "CURSOR_EXPIRED",
                        "Finish the stable snapshot before following changes.",
                    ));
                }
                let floor: i64 =
                    sqlx::query_scalar("SELECT sync_floor FROM file_roots WHERE root_id=?")
                        .bind(&r.id)
                        .fetch_one(&self.0.pool)
                        .await
                        .map_err(|_| RpcError::storage())?;
                if selected.fence < floor as u64 {
                    return Err(err(
                        "CURSOR_EXPIRED",
                        "The bounded journal advanced beyond this cursor; take a fresh snapshot.",
                    ));
                }
                let records=sqlx::query("SELECT sequence,kind,object_id,entry FROM file_journal WHERE root_id=? AND sequence>? ORDER BY sequence LIMIT 16").bind(&r.id).bind(selected.fence as i64).fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                let mut events = Vec::new();
                for row in records {
                    events.push(SyncEvent {
                        sequence: row.get::<i64, _>("sequence") as u64,
                        kind: row.get("kind"),
                        object_id: row.get("object_id"),
                        entry: row
                            .get::<Option<String>, _>("entry")
                            .map(|s| serde_json::from_str(&s).map_err(|_| RpcError::storage()))
                            .transpose()?,
                    });
                }
                let fence = events.last().map(|e| e.sequence).unwrap_or(selected.fence);
                let more = fence < high as u64;
                let token = uuid::Uuid::new_v4().to_string();
                let mut next = selected;
                next.fence = fence;
                next.until = Instant::now() + Duration::from_secs(120);
                self.save_sync_cursor(token.clone(), next)?;
                Ok(SyncPage {
                    root_id: r.id,
                    mode: "changes".into(),
                    entries: Vec::new(),
                    events,
                    next_cursor: if more { Some(token.clone()) } else { None },
                    changes_cursor: Some(token),
                    fence,
                })
            }
        }
    }
}
