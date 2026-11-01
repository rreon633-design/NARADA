//! Approved local roots, bounded catalogs and content operations. Owner paths never enter the peer contract.
mod catalog;
mod content;
mod operations;
mod policy;
pub mod safe_fs;
pub use content::ContentSnapshot;
mod pins;
mod sync;
pub use pins::PinWork;
pub mod transfers;
use catalog::Entry;
use narada_core::Core;
use narada_protocol::*;
use notify::Watcher;
use policy::err;
use serde_json::{json, Value};
use sqlx::{Row, SqlitePool};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex as SyncMutex,
    },
    time::{Duration, Instant},
};
use tokio::sync::{Mutex, OwnedRwLockReadGuard, RwLock};
#[derive(Clone)]
pub struct Files(Arc<Inner>);
struct Inner {
    pool: SqlitePool,
    core: Core,
    data_dir: PathBuf,
    gate: Arc<RwLock<()>>,
    jobs: Arc<tokio::sync::Semaphore>,
    io: Mutex<()>,
    pin_io: Mutex<()>,
    cursors: SyncMutex<HashMap<String, Cursor>>,
    sync_views: SyncMutex<HashMap<String, sync::SyncView>>,
    watcher: SyncMutex<Option<notify::RecommendedWatcher>>,
    dirty: Arc<AtomicBool>,
    stopped: AtomicBool,
    task: Mutex<Option<tokio::task::JoinHandle<()>>>,
}
#[derive(Clone)]
struct Root {
    id: String,
    path: PathBuf,
    identity: String,
    label: String,
    exclusions: Vec<String>,
    revision: u32,
    epoch: u64,
    signature: String,
    state: String,
    error: Option<String>,
    indexed_at: Option<String>,
    omitted: usize,
    trash_identity: Option<String>,
}
struct Cursor {
    actor: String,
    root: String,
    scope: String,
    epoch: u64,
    offset: usize,
    until: Instant,
}
#[derive(Clone)]
pub enum Access {
    Owner,
    Peer { id: String, grants: PeerPermissions },
}
impl Access {
    fn actor(&self) -> &str {
        match self {
            Self::Owner => "local-owner",
            Self::Peer { id, .. } => id,
        }
    }
    fn allows(&self, id: &str, cap: Capability) -> bool {
        match self {
            Self::Owner => true,
            Self::Peer { grants, .. } => grants
                .roots
                .iter()
                .any(|r| r.root_id == id && r.capabilities.contains(&cap)),
        }
    }
    fn require(&self, id: &str, cap: Capability) -> Result<(), RpcError> {
        if self.allows(id, Capability::Browse) && self.allows(id, cap) {
            Ok(())
        } else {
            Err(err(
                "ACCESS_DENIED",
                "The current device grant does not allow this root operation.",
            ))
        }
    }
    fn caps(&self, id: &str) -> Vec<Capability> {
        match self {
            Self::Owner => vec![
                Capability::Browse,
                Capability::Read,
                Capability::Create,
                Capability::Modify,
                Capability::Trash,
                Capability::SyncRead,
            ],
            Self::Peer { grants, .. } => grants
                .roots
                .iter()
                .find(|r| r.root_id == id)
                .map(|r| r.capabilities.clone())
                .unwrap_or_default(),
        }
    }
}
impl Files {
    pub async fn open(core: Core, dir: &Path) -> Result<Self, RpcError> {
        let dirty = Arc::new(AtomicBool::new(true));
        let flag = dirty.clone();
        let watcher = notify::RecommendedWatcher::new(
            move |event: notify::Result<notify::Event>| {
                if !matches!(
                    event,
                    Ok(notify::Event {
                        kind: notify::EventKind::Access(_),
                        ..
                    })
                ) {
                    flag.store(true, Ordering::Relaxed);
                }
            },
            notify::Config::default().with_follow_symlinks(false),
        )
        .ok();
        let watcher = if std::env::var("NARADA_FILE_WATCHER").as_deref() == Ok("poll") {
            None
        } else {
            watcher
        };
        let me = Self(Arc::new(Inner {
            pool: core.metadata_pool().clone(),
            core,
            data_dir: dir.to_owned(),
            gate: Arc::new(RwLock::new(())),
            jobs: Arc::new(tokio::sync::Semaphore::new(8)),
            io: Mutex::new(()),
            pin_io: Mutex::new(()),
            cursors: SyncMutex::new(HashMap::new()),
            sync_views: SyncMutex::new(HashMap::new()),
            watcher: SyncMutex::new(watcher),
            dirty,
            stopped: AtomicBool::new(false),
            task: Mutex::new(None),
        }));
        {
            let _io = me.0.io.lock().await;
            me.recover().await?;
            me.recover_transfers().await?;
        }
        for root in me.roots().await? {
            me.watch(&root.path, true);
        }
        let weak = Arc::downgrade(&me.0);
        let task = tokio::spawn(async move {
            let mut last = Instant::now() - Duration::from_secs(31);
            loop {
                tokio::time::sleep(Duration::from_secs(2)).await;
                let Some(inner) = weak.upgrade() else { break };
                if inner.stopped.load(Ordering::Relaxed) {
                    break;
                }
                let me = Self(inner);
                if me.0.dirty.swap(false, Ordering::Relaxed)
                    || last.elapsed() > Duration::from_secs(30)
                {
                    last = Instant::now();
                    if let Ok(roots) = me.roots().await {
                        for r in roots {
                            if me.0.stopped.load(Ordering::Relaxed) {
                                break;
                            }
                            let _io = me.0.io.lock().await;
                            let _ = me.reindex_locked(&r.id).await;
                        }
                    }
                }
            }
        });
        *me.0.task.lock().await = Some(task);
        Ok(me)
    }
    pub fn gate(&self) -> Arc<RwLock<()>> {
        self.0.gate.clone()
    }
    pub async fn shutdown(&self) {
        self.0.stopped.store(true, Ordering::Relaxed);
        if let Some(t) = self.0.task.lock().await.take() {
            t.abort();
            let _ = t.await;
        }
        let _guard = self.0.gate.clone().write_owned().await;
        if let Ok(mut w) = self.0.watcher.lock() {
            *w = None;
        }
    }
    pub async fn count(&self) -> Result<u32, RpcError> {
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM file_roots WHERE active=1")
            .fetch_one(&self.0.pool)
            .await
            .map(|v| v as u32)
            .map_err(|_| RpcError::storage())
    }
    fn watch(&self, path: &Path, add: bool) {
        if let Ok(mut w) = self.0.watcher.lock() {
            if let Some(w) = w.as_mut() {
                if add {
                    let _ = w.watch(path, notify::RecursiveMode::Recursive);
                } else {
                    let _ = w.unwatch(path);
                }
            }
        }
    }
    async fn roots(&self) -> Result<Vec<Root>, RpcError> {
        let rows = sqlx::query(
            "SELECT * FROM file_roots WHERE active=1 ORDER BY created_at,root_id LIMIT 8",
        )
        .fetch_all(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        rows.into_iter().map(root_row).collect()
    }
    async fn root(&self, id: &str) -> Result<Root, RpcError> {
        file_id(id)?;
        let r = sqlx::query("SELECT * FROM file_roots WHERE root_id=? AND active=1")
            .bind(id)
            .fetch_optional(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?
            .ok_or_else(|| {
                err(
                    "ROOT_UNAVAILABLE",
                    "This root is no longer registered or authorized.",
                )
            })?;
        root_row(r)
    }
    async fn entries(&self, id: &str) -> Result<Vec<Entry>, RpcError> {
        let rows = sqlx::query(
            "SELECT * FROM file_entries WHERE root_id=? ORDER BY relative_path LIMIT 5001",
        )
        .bind(id)
        .fetch_all(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        if rows.len() > 5000 {
            return Err(err(
                "INDEX_LIMIT",
                "The saved catalog exceeds its configured bound.",
            ));
        }
        Ok(rows.into_iter().map(entry_row).collect())
    }
    async fn entry(&self, root: &str, id: &str) -> Result<Entry, RpcError> {
        file_id(id)?;
        let row = sqlx::query("SELECT * FROM file_entries WHERE root_id=? AND object_id=?")
            .bind(root)
            .bind(id)
            .fetch_optional(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?
            .ok_or_else(|| {
                err(
                    "OBJECT_UNAVAILABLE",
                    "This indexed object is no longer available in this root.",
                )
            })?;
        Ok(entry_row(row))
    }
    async fn root_view(&self, r: &Root, access: &Access) -> Result<Value, RpcError> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM file_entries WHERE root_id=?")
            .bind(&r.id)
            .fetch_one(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let mut v = json!({"root_id":r.id,"label":r.label,"revision":r.revision,"index_epoch":r.epoch.to_string(),"state":r.state,"error_code":r.error,"indexed_at":r.indexed_at,"entry_count":count,"omitted_count":r.omitted,"capabilities":access.caps(&r.id)});
        if matches!(access, Access::Owner) {
            v["path"] = json!(r.path);
            v["exclusions"] = json!(r.exclusions);
            v["watcher"] = json!(
                if self.0.watcher.lock().map(|v| v.is_some()).unwrap_or(false) {
                    "native hints + 30-second reconciliation"
                } else {
                    "30-second reconciliation fallback"
                }
            );
        }
        Ok(v)
    }
    pub async fn list_owner(&self) -> Result<Value, RpcError> {
        let access = Access::Owner;
        let mut values = Vec::new();
        for r in self.roots().await? {
            values.push(self.root_view(&r, &access).await?);
        }
        Ok(json!({"roots":values,"limit":MAX_ROOTS}))
    }
    pub async fn register(&self, input: RegisterRoot) -> Result<Value, RpcError> {
        let me = self.clone();
        tokio::spawn(async move{let _g=me.0.gate.clone().write_owned().await;let _io=me.0.io.lock().await;
   policy::exclusions(&input.exclusions)?;let label=input.label.trim();if label.is_empty()||label.chars().count()>48||label.chars().any(char::is_control){return Err(err("INVALID_ROOT_LABEL","Use a folder label of 1–48 characters without controls."));}
   if me.count().await? as usize>=MAX_ROOTS{return Err(err("ROOT_LIMIT","At most eight active roots can be registered."));}
   let path=PathBuf::from(&input.path);policy::root_path(&path,&me.0.data_dir)?;
   let selected=path.clone();let (path,identity)=blocking(move||{let r=safe_fs::SafeRoot::open(&selected,None)?;r.verify()?;Ok((r.path,r.identity))}).await?;
   let protected=std::fs::canonicalize(&me.0.data_dir).map_err(safe_fs::io_error)?;
   #[cfg(windows)] let protected=PathBuf::from(protected.to_string_lossy().strip_prefix("\\\\?\\").unwrap_or(&protected.to_string_lossy()));
   policy::root_path(&path,&protected)?;
   for r in me.roots().await?{if r.identity==identity||policy::within(&path,&r.path)||policy::within(&r.path,&path){return Err(err("ROOT_OVERLAP","Duplicate and overlapping shared roots are not allowed."));}}
   let previous=sqlx::query_scalar::<_,String>("SELECT root_id FROM file_roots WHERE identity=? AND active=0 ORDER BY created_at DESC LIMIT 1").bind(&identity).fetch_optional(&me.0.pool).await.map_err(|_|RpcError::storage())?;
   if let Some(id)=previous{
    let mut tx=me.0.pool.begin().await.map_err(|_|RpcError::storage())?;
    sqlx::query("UPDATE file_roots SET active=1,path=?,label=?,exclusions=?,revision=revision+1,epoch=epoch+1 WHERE root_id=?").bind(path.to_string_lossy().as_ref()).bind(label).bind(serde_json::to_string(&input.exclusions).map_err(|_|RpcError::storage())?).bind(&id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
    sqlx::query("UPDATE authorization_scopes SET active=1 WHERE root_id=?").bind(&id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;tx.commit().await.map_err(|_|RpcError::storage())?;me.watch(&path,true);let _=me.reindex_locked(&id).await;return me.root_view(&me.root(&id).await?,&Access::Owner).await;
   }
   let id=uuid::Uuid::new_v4().to_string();let mut tx=me.0.pool.begin_with("BEGIN IMMEDIATE").await.map_err(|_|RpcError::storage())?;
   sqlx::query("INSERT INTO authorization_scopes(root_id,active) VALUES (?,1)").bind(&id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
   sqlx::query("INSERT INTO file_roots(root_id,path,identity,label,exclusions,created_at) VALUES (?,?,?,?,?,?)").bind(&id).bind(path.to_string_lossy().as_ref()).bind(&identity).bind(label).bind(serde_json::to_string(&input.exclusions).map_err(|_|RpcError::storage())?).bind(chrono::Utc::now().to_rfc3339()).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;tx.commit().await.map_err(|_|RpcError::storage())?;
   me.watch(&path,true);let _=me.reindex_locked(&id).await;me.0.core.record("files","Folder registered","An owner-selected folder was registered. No peer permissions were granted automatically.").await?;me.root_view(&me.root(&id).await?,&Access::Owner).await
  }).await.map_err(|_|err("FILE_WORK_FAILED","The bounded file worker stopped."))?
    }
    pub async fn policy(&self, input: RootPolicy) -> Result<Value, RpcError> {
        let me = self.clone();
        tokio::spawn(async move{let _g=me.0.gate.clone().write_owned().await;let _io=me.0.io.lock().await;policy::exclusions(&input.exclusions)?;let r=me.root(&input.root_id).await?;if r.revision!=input.expected_revision{return Err(err("REVISION_CONFLICT","Root policy changed. Reopen its settings before saving."));}
   sqlx::query("UPDATE file_roots SET exclusions=?,revision=revision+1,epoch=epoch+1,state='indexing' WHERE root_id=?").bind(serde_json::to_string(&input.exclusions).map_err(|_|RpcError::storage())?).bind(&r.id).execute(&me.0.pool).await.map_err(|_|RpcError::storage())?;me.reindex_locked(&r.id).await?;me.root_view(&me.root(&r.id).await?,&Access::Owner).await
  }).await.map_err(|_|err("FILE_WORK_FAILED","The file policy worker stopped."))?
    }
    pub async fn remove(&self, input: RootRevision) -> Result<Value, RpcError> {
        let me = self.clone();
        tokio::spawn(async move{let _g=me.0.gate.clone().write_owned().await;let _io=me.0.io.lock().await;let r=me.root(&input.root_id).await?;if r.revision!=input.expected_revision{return Err(err("REVISION_CONFLICT","Root policy changed. Refresh before removing it."));}
   let mut tx=me.0.pool.begin_with("BEGIN IMMEDIATE").await.map_err(|_|RpcError::storage())?;
   sqlx::query("UPDATE file_roots SET active=0,revision=revision+1,epoch=epoch+1 WHERE root_id=?").bind(&r.id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
   sqlx::query("UPDATE authorization_scopes SET active=0 WHERE root_id=?").bind(&r.id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
   let peers=sqlx::query("SELECT peer_id,permissions FROM trusted_peers WHERE state!='revoked'").fetch_all(&mut *tx).await.map_err(|_|RpcError::storage())?;
   for row in peers{let mut grants:PeerPermissions=serde_json::from_str(row.get::<&str,_>("permissions")).map_err(|_|RpcError::storage())?;let before=grants.roots.len();grants.roots.retain(|g|g.root_id!=r.id);if grants.roots.len()!=before{sqlx::query("UPDATE trusted_peers SET permissions=?,grant_revision=grant_revision+1,session_epoch=session_epoch+1 WHERE peer_id=?").bind(serde_json::to_string(&grants).map_err(|_|RpcError::storage())?).bind(row.get::<&str,_>("peer_id")).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;}}
   sqlx::query("DELETE FROM file_entries WHERE root_id=?").bind(&r.id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;sqlx::query("DELETE FROM file_journal WHERE root_id=?").bind(&r.id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;sqlx::query("UPDATE file_roots SET sync_floor=0 WHERE root_id=?").bind(&r.id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;tx.commit().await.map_err(|_|RpcError::storage())?;me.watch(&r.path,false);me.0.core.record("files","Folder unregistered","Root authorization and device grants were withdrawn. Files on disk were not deleted.").await?;Ok(json!({"removed":true}))
  }).await.map_err(|_|err("FILE_WORK_FAILED","The file policy worker stopped."))?
    }
    pub async fn reindex(&self, id: String) -> Result<Value, RpcError> {
        let me = self.clone();
        tokio::spawn(async move {
            let _io = me.0.io.lock().await;
            me.reindex_locked(&id).await?;
            me.root_view(&me.root(&id).await?, &Access::Owner).await
        })
        .await
        .map_err(|_| err("FILE_WORK_FAILED", "The index worker stopped."))?
    }
    async fn reindex_locked(&self, id: &str) -> Result<(), RpcError> {
        let r = self.root(id).await?;
        let old_entries = self.entries(id).await?;
        let old = old_entries
            .clone()
            .into_iter()
            .map(|e| (e.relative_path, (e.object_id, e.info.identity)))
            .collect();
        let copy = r.clone();
        let scan = match blocking(move || {
            catalog::scan(&copy.path, &copy.identity, &copy.id, &copy.exclusions, old)
        })
        .await
        {
            Ok(v) => v,
            Err(e) => {
                sqlx::query("UPDATE file_roots SET state='blocked',error_code=?,indexed_at=? WHERE root_id=?").bind(&e.code).bind(chrono::Utc::now().to_rfc3339()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                return Err(e);
            }
        };
        if scan.signature == r.signature {
            sqlx::query("UPDATE file_roots SET state=CASE WHEN (EXISTS(SELECT 1 FROM file_operations o WHERE o.root_id=file_roots.root_id AND o.state IN ('prepared','recovery_required')) OR EXISTS(SELECT 1 FROM transfer_receives t WHERE t.root_id=file_roots.root_id AND t.state IN ('committing','recovery_required'))) THEN 'recovery_required' ELSE 'ready' END,error_code=CASE WHEN (EXISTS(SELECT 1 FROM file_operations o WHERE o.root_id=file_roots.root_id AND o.state IN ('prepared','recovery_required')) OR EXISTS(SELECT 1 FROM transfer_receives t WHERE t.root_id=file_roots.root_id AND t.state IN ('committing','recovery_required'))) THEN 'OPERATION_INDETERMINATE' ELSE NULL END,indexed_at=? WHERE root_id=?").bind(chrono::Utc::now().to_rfc3339()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
            return Ok(());
        }
        let mut tx = self.0.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM file_entries WHERE root_id=?")
            .bind(id)
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        for e in &scan.entries {
            insert_entry(&mut tx, e).await?;
        }
        self.journal_index(&mut tx, id, &old_entries, &scan.entries)
            .await?;
        sqlx::query("UPDATE file_roots SET signature=?,epoch=epoch+1,state=CASE WHEN (EXISTS(SELECT 1 FROM file_operations o WHERE o.root_id=file_roots.root_id AND o.state IN ('prepared','recovery_required')) OR EXISTS(SELECT 1 FROM transfer_receives t WHERE t.root_id=file_roots.root_id AND t.state IN ('committing','recovery_required'))) THEN 'recovery_required' ELSE 'ready' END,error_code=CASE WHEN (EXISTS(SELECT 1 FROM file_operations o WHERE o.root_id=file_roots.root_id AND o.state IN ('prepared','recovery_required')) OR EXISTS(SELECT 1 FROM transfer_receives t WHERE t.root_id=file_roots.root_id AND t.state IN ('committing','recovery_required'))) THEN 'OPERATION_INDETERMINATE' ELSE NULL END,indexed_at=?,omitted=? WHERE root_id=?").bind(scan.signature).bind(chrono::Utc::now().to_rfc3339()).bind(scan.omitted as i64).bind(id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn query(
        &self,
        query: FileQuery,
        access: Access,
        guard: OwnedRwLockReadGuard<()>,
    ) -> Result<Value, RpcError> {
        let permit = self.0.jobs.clone().try_acquire_owned().map_err(|_| {
            err(
                "FILE_BUSY",
                "The bounded file worker queue is full. Retry after current work completes.",
            )
        })?;
        let me = self.clone();
        tokio::spawn(async move {
            let _permit = permit;
            let _g = guard;
            let _io = me.0.io.lock().await;
            query.validate()?;
            bounded(me.query_locked(query, &access).await?)
        })
        .await
        .map_err(|_| err("FILE_WORK_FAILED", "The file query worker stopped."))?
    }
    async fn query_locked(&self, q: FileQuery, a: &Access) -> Result<Value, RpcError> {
        if matches!(q, FileQuery::Roots {}) {
            let mut roots = Vec::new();
            for r in self.roots().await? {
                if a.allows(&r.id, Capability::Browse) {
                    roots.push(self.root_view(&r, a).await?);
                }
            }
            return Ok(json!({"roots":roots,"file_protocol":FILE_PROTOCOL_VERSION}));
        }
        let id = q.root().unwrap_or("").to_owned();
        a.require(&id, Capability::Browse)?;
        let r = self.root(&id).await?;
        if matches!(q, FileQuery::Trash { .. }) {
            a.require(&id, Capability::Trash)?;
            return self.trash_view(&r, a, &q).await;
        }
        if r.state == "blocked" || r.state == "indexing" {
            return Err(err(
                "INDEX_UNAVAILABLE",
                "The root index is not ready. Refresh it or review the root error.",
            ));
        }
        match q {
            FileQuery::Details { object_id, .. } => {
                let e = self.entry(&id, &object_id).await?;
                let rr = r.clone();
                let ee = e.clone();
                blocking(move || {
                    catalog::validate_live(&rr.path, &rr.identity, &rr.exclusions, &ee)
                })
                .await?;
                let parent = sqlx::query_scalar::<_, String>(
                    "SELECT object_id FROM file_entries WHERE root_id=? AND relative_path=?",
                )
                .bind(&id)
                .bind(&e.parent_path)
                .fetch_optional(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
                Ok(catalog::entry_view(&e, parent.as_deref()))
            }
            FileQuery::Preview {
                object_id,
                expected_version,
                ..
            } => {
                a.require(&id, Capability::Read)?;
                let e = self.entry(&id, &object_id).await?;
                blocking(move || {
                    catalog::preview(&r.path, &r.identity, &r.exclusions, &e, &expected_version)
                })
                .await
            }
            FileQuery::Browse {
                directory_id,
                cursor,
                sort,
                ..
            } => {
                let parent = if let Some(id) = directory_id {
                    let e = self.entry(&r.id, &id).await?;
                    if e.info.kind != "directory" {
                        return Err(err("NOT_A_DIRECTORY", "Select a directory."));
                    }
                    let rr = r.clone();
                    let ee = e.clone();
                    blocking(move || {
                        catalog::validate_live(&rr.path, &rr.identity, &rr.exclusions, &ee)
                    })
                    .await?;
                    e.relative_path
                } else {
                    String::new()
                };
                self.page(&r, a, &parent, None, cursor, sort).await
            }
            FileQuery::Search {
                query,
                cursor,
                sort,
                ..
            } => self.page(&r, a, "", Some(query), cursor, sort).await,
            _ => Err(err("INVALID_QUERY", "Unsupported query.")),
        }
    }
    async fn page(
        &self,
        r: &Root,
        a: &Access,
        parent: &str,
        search: Option<String>,
        cursor: Option<String>,
        sort: FileSort,
    ) -> Result<Value, RpcError> {
        let scope = catalog::signature_query(&(parent, &search, sort));
        let offset = self.cursor_offset(cursor.as_deref(), a, r, &scope)?;
        let entries = self.entries(&r.id).await?;
        let parents: HashMap<_, _> = entries
            .iter()
            .map(|e| (e.relative_path.clone(), e.object_id.clone()))
            .collect();
        let mut list: Vec<_> = entries
            .iter()
            .filter(|e| {
                if let Some(q) = &search {
                    policy::fold(&e.name).contains(&policy::fold(q.trim()))
                } else {
                    e.parent_path == parent
                }
            })
            .cloned()
            .collect();
        list.sort_by(|a, b| {
            let order = match sort {
                FileSort::Name => policy::fold(&a.name).cmp(&policy::fold(&b.name)),
                FileSort::NameDesc => policy::fold(&b.name).cmp(&policy::fold(&a.name)),
                FileSort::Modified => b.info.modified.cmp(&a.info.modified),
                FileSort::Size => b.info.size.cmp(&a.info.size),
            };
            order.then_with(|| a.object_id.cmp(&b.object_id))
        });
        let selected: Vec<_> = list.iter().skip(offset).take(16).cloned().collect();
        let rr = r.clone();
        let valid = blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            let mut valid = Vec::new();
            for e in selected {
                if !policy::excluded(&e.relative_path, &rr.exclusions)
                    && root.info(&e.relative_path).is_ok_and(|i| {
                        !i.hidden && i.identity == e.info.identity && i.version == e.info.version
                    })
                {
                    valid.push(e);
                }
            }
            root.verify()?;
            Ok(valid)
        })
        .await?;
        let next = (offset + 16 < list.len()).then(|| self.make_cursor(a, r, &scope, offset + 16));
        let mut crumbs = vec![json!({"name":r.label,"object_id":Value::Null})];
        let mut rel = String::new();
        for part in parent.split('/').filter(|s| !s.is_empty()) {
            if !rel.is_empty() {
                rel.push('/');
            }
            rel.push_str(part);
            crumbs.push(json!({"name":part,"object_id":parents.get(&rel)}));
        }
        Ok(
            json!({"root":self.root_view(r,a).await?,"entries":valid.iter().map(|e|catalog::entry_view(e,parents.get(&e.parent_path).map(String::as_str))).collect::<Vec<_>>(),"breadcrumbs":crumbs,"next_cursor":next,"index_epoch":r.epoch.to_string(),"indexed_matches":list.len(),"notice":"Live revalidation can omit changed entries. Refresh reconciles the catalog; cursors are invalidated when its epoch changes."}),
        )
    }
    fn cursor_offset(
        &self,
        id: Option<&str>,
        a: &Access,
        r: &Root,
        scope: &str,
    ) -> Result<usize, RpcError> {
        if let Some(id) = id {
            let cursors = self.0.cursors.lock().map_err(|_| RpcError::storage())?;
            let c = cursors.get(id).ok_or_else(|| {
                err(
                    "CURSOR_STALE",
                    "This page cursor expired. Restart from the first page.",
                )
            })?;
            if c.actor != a.actor()
                || c.root != r.id
                || c.scope != scope
                || c.epoch != r.epoch
                || Instant::now() > c.until
            {
                return Err(err(
                    "CURSOR_STALE",
                    "The root, grant scope, query or index changed. Restart pagination.",
                ));
            }
            Ok(c.offset)
        } else {
            Ok(0)
        }
    }
    fn make_cursor(&self, a: &Access, r: &Root, scope: &str, offset: usize) -> String {
        let id = uuid::Uuid::new_v4().to_string();
        if let Ok(mut map) = self.0.cursors.lock() {
            map.retain(|_, v| Instant::now() < v.until);
            if map.len() >= 64 {
                if let Some(k) = map
                    .iter()
                    .min_by_key(|(_, v)| v.until)
                    .map(|(k, _)| k.clone())
                {
                    map.remove(&k);
                }
            }
            map.insert(
                id.clone(),
                Cursor {
                    actor: a.actor().into(),
                    root: r.id.clone(),
                    scope: scope.into(),
                    epoch: r.epoch,
                    offset,
                    until: Instant::now() + Duration::from_secs(60),
                },
            );
        }
        id
    }
}
async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, RpcError> + Send + 'static,
) -> Result<T, RpcError> {
    tokio::task::spawn_blocking(f).await.map_err(|_| {
        err(
            "FILE_WORK_FAILED",
            "The filesystem worker failed without a successful result.",
        )
    })?
}
fn bounded(v: Value) -> Result<Value, RpcError> {
    if serde_json::to_vec(&v)
        .map_err(|_| RpcError::storage())?
        .len()
        > MAX_FILE_RESPONSE
    {
        return Err(err(
            "FILE_RESPONSE_LIMIT",
            "The file response exceeded its bounded control-frame budget.",
        ));
    }
    Ok(v)
}
fn root_row(r: sqlx::sqlite::SqliteRow) -> Result<Root, RpcError> {
    let exclusions: Vec<String> =
        serde_json::from_str(r.get::<&str, _>("exclusions")).map_err(|_| RpcError::storage())?;
    policy::exclusions(&exclusions)?;
    Ok(Root {
        id: r.get("root_id"),
        path: PathBuf::from(r.get::<String, _>("path")),
        identity: r.get("identity"),
        label: r.get("label"),
        exclusions,
        revision: r.get::<i64, _>("revision") as u32,
        epoch: r.get::<i64, _>("epoch") as u64,
        signature: r.get("signature"),
        state: r.get("state"),
        error: r.get("error_code"),
        indexed_at: r.get("indexed_at"),
        omitted: r.get::<i64, _>("omitted") as usize,
        trash_identity: r.get("trash_identity"),
    })
}
fn entry_row(r: sqlx::sqlite::SqliteRow) -> Entry {
    Entry {
        object_id: r.get("object_id"),
        root_id: r.get("root_id"),
        relative_path: r.get("relative_path"),
        parent_path: r.get("parent_path"),
        name: r.get("name"),
        info: safe_fs::Info {
            identity: r.get("identity"),
            version: r.get("version"),
            kind: r.get("kind"),
            size: r.get::<i64, _>("size") as u64,
            modified: r.get("modified"),
            hidden: false,
        },
    }
}
async fn insert_entry(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    e: &Entry,
) -> Result<(), RpcError> {
    sqlx::query("INSERT INTO file_entries(object_id,root_id,relative_path,parent_path,name,kind,identity,version,size,modified) VALUES (?,?,?,?,?,?,?,?,?,?)").bind(&e.object_id).bind(&e.root_id).bind(&e.relative_path).bind(&e.parent_path).bind(&e.name).bind(&e.info.kind).bind(&e.info.identity).bind(&e.info.version).bind(e.info.size as i64).bind(&e.info.modified).execute(&mut **tx).await.map_err(|_|RpcError::storage())?;
    Ok(())
}

#[cfg(test)]
mod tests;
