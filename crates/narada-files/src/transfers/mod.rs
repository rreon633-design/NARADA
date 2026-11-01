//! Quota-bound immutable source snapshots, durable receive checkpoints and a
//! separate commit intent. Ordinary file bytes are not encrypted at rest here.
use super::*;
use sha2::{Digest, Sha256};
use std::io::{Read, Seek, SeekFrom, Write};
mod jobs;
mod receive;
mod source;
pub use jobs::TransferJob;
#[cfg(test)]
mod tests;
const PRIVATE_DIR: &str = "transfer-snapshots";
const STAGE_DIR: &str = ".narada-transfers";
const MAX_ACTIVE: i64 = 8;
const MAX_HISTORY: i64 = 128;
#[derive(Clone)]
struct SourceRecord {
    id: String,
    actor: String,
    root_id: String,
    object_id: String,
    path: String,
    source_identity: String,
    source_version: String,
    name: String,
    size: u64,
    manifest: Option<TransferManifest>,
    hashes: Vec<String>,
    snapshot_identity: Option<String>,
    state: String,
    error: Option<String>,
    offered: bool,
    sync_required: bool,
    expires: i64,
}
#[derive(Clone)]
struct ReceiveRecord {
    id: String,
    actor: String,
    root_id: String,
    parent: String,
    parent_identity: String,
    name: String,
    manifest: TransferManifest,
    hashes: Vec<String>,
    bitmap: Vec<bool>,
    stage_identity: Option<String>,
    state: String,
    error: Option<String>,
    bytes: u64,
    result: Option<Value>,
    intent_identity: Option<String>,
    expires: i64,
}
pub fn manifest_digest(m: &TransferManifest, hashes: &[String]) -> Result<String, RpcError> {
    Ok(hex::encode(Sha256::digest(
        serde_json::to_vec(&(
            m.version,
            &m.source_version,
            &m.name,
            &m.size,
            m.chunk_size,
            m.chunk_count,
            &m.sha256,
            hashes,
        ))
        .map_err(|_| RpcError::storage())?,
    )))
}
fn parse<T: serde::de::DeserializeOwned>(s: &str) -> Result<T, RpcError> {
    serde_json::from_str(s).map_err(|_| RpcError::storage())
}
fn encoded<T: serde::Serialize>(v: &T) -> Result<String, RpcError> {
    serde_json::to_string(v).map_err(|_| RpcError::storage())
}
fn now() -> i64 {
    chrono::Utc::now().timestamp()
}
fn storage_io(e: std::io::Error) -> RpcError {
    match e.kind(){std::io::ErrorKind::StorageFull|std::io::ErrorKind::FileTooLarge=>err("STORAGE_FULL","The transfer could not reserve or write its bounded staging data. Original files were not replaced."),_=>safe_fs::io_error(e)}
}
fn ensure_space(available: u64, required: u64) -> Result<(), RpcError> {
    if available < required.saturating_add(16 * 1024 * 1024) {
        Err(err(
            "STORAGE_FULL",
            "Insufficient staging space plus the 16 MiB safety margin.",
        ))
    } else {
        Ok(())
    }
}
fn hash_reader(file: &mut std::fs::File, size: u64) -> Result<(String, Vec<String>), RpcError> {
    file.seek(SeekFrom::Start(0)).map_err(storage_io)?;
    let mut full = Sha256::new();
    let mut hashes = Vec::new();
    let mut remaining = size;
    let mut buffer = vec![0u8; TRANSFER_CHUNK_BYTES];
    while remaining > 0 {
        let n = remaining.min(TRANSFER_CHUNK_BYTES as u64) as usize;
        file.read_exact(&mut buffer[..n]).map_err(storage_io)?;
        full.update(&buffer[..n]);
        hashes.push(hex::encode(Sha256::digest(&buffer[..n])));
        remaining -= n as u64;
    }
    let mut extra = [0];
    if file.read(&mut extra).map_err(storage_io)? != 0 {
        return Err(err(
            "HASH_MISMATCH",
            "The staged length changed. No publication occurred.",
        ));
    }
    Ok((hex::encode(full.finalize()), hashes))
}
impl Files {
    pub(crate) async fn transfer_publication_barrier(
        &self,
        root: &str,
        exclude: Option<&str>,
    ) -> Result<(), RpcError> {
        let pending:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM file_operations WHERE root_id=? AND state IN ('prepared','recovery_required')) OR EXISTS(SELECT 1 FROM transfer_receives WHERE root_id=? AND transfer_id!=? AND state IN ('committing','recovery_required'))").bind(root).bind(root).bind(exclude.unwrap_or("")).fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        if pending {
            Err(err("OPERATION_INDETERMINATE","This root has an unresolved file or transfer intent. Preserve its contents; new publication and mutations are blocked."))
        } else {
            Ok(())
        }
    }
    pub async fn transfer_action(
        &self,
        action: TransferAction,
        access: Access,
        guard: OwnedRwLockReadGuard<()>,
    ) -> Result<Value, RpcError> {
        let permit = self
            .0
            .jobs
            .clone()
            .try_acquire_owned()
            .map_err(|_| err("FILE_BUSY", "The bounded file worker is busy."))?;
        let me = self.clone();
        tokio::spawn(async move{let _p=permit;let _g=guard;let _io=me.0.io.lock().await;let value=match action{
            TransferAction::PrepareSync{transfer_id,source}=>serde_json::to_value(me.prepare_source(&transfer_id,&source,&access,false,true).await?).map_err(|_|RpcError::storage())?,
            TransferAction::Prepare{transfer_id,source}=>serde_json::to_value(me.prepare_source(&transfer_id,&source,&access,false,false).await?).map_err(|_|RpcError::storage())?,
            TransferAction::Manifest{transfer_id,offset}=>serde_json::to_value(me.source_manifest_page(&transfer_id,offset,&access).await?).map_err(|_|RpcError::storage())?,
            TransferAction::OpenReceive{transfer_id,destination,manifest}=>serde_json::to_value(me.open_receive(&transfer_id,&destination,&manifest,&access).await?).map_err(|_|RpcError::storage())?,
            TransferAction::ManifestPage{transfer_id,offset,hashes}=>serde_json::to_value(me.accept_manifest(&transfer_id,offset,&hashes,&access).await?).map_err(|_|RpcError::storage())?,
            TransferAction::Status{transfer_id,side}=>serde_json::to_value(me.transfer_status_locked(&transfer_id,side,&access).await?).map_err(|_|RpcError::storage())?,
            TransferAction::Commit{transfer_id}=>me.commit_receive(&transfer_id,&access).await?,
            TransferAction::Cancel{transfer_id,side}=>{me.cancel_transfer_side(&transfer_id,side,&access).await?;json!({"cancelled":true})},
            TransferAction::Finish{transfer_id,sha256}=>{let row=me.source_record(&transfer_id).await?;me.authorize_source(&row,&access).await?;if row.manifest.as_ref().is_none_or(|m|m.sha256!=sha256){return Err(err("HASH_MISMATCH","The completion receipt does not match the immutable source."));}sqlx::query("UPDATE transfer_sources SET state='completed' WHERE transfer_id=? AND state IN ('ready','completed')").bind(&transfer_id).execute(&me.0.pool).await.map_err(|_|RpcError::storage())?;json!({"acknowledged":true})},
            TransferAction::Offers{}=>me.source_offers(&access).await?,
        };bounded(value)}).await.map_err(|_|err("TRANSFER_INTERRUPTED","The transfer worker stopped without a confirmed result."))?
    }
    pub async fn transfer_chunk(
        &self,
        id: String,
        index: u32,
        data: Option<Vec<u8>>,
        access: Access,
        guard: OwnedRwLockReadGuard<()>,
    ) -> Result<Vec<u8>, RpcError> {
        let permit = self
            .0
            .jobs
            .clone()
            .try_acquire_owned()
            .map_err(|_| err("FILE_BUSY", "The bounded file worker is busy."))?;
        let me = self.clone();
        tokio::spawn(async move {
            let _p = permit;
            let _g = guard;
            let _io = me.0.io.lock().await;
            file_id(&id)?;
            if let Some(data) = data {
                me.receive_chunk(&id, index, data, &access).await?;
                Ok(Vec::new())
            } else {
                me.read_source_chunk(&id, index, &access).await
            }
        })
        .await
        .map_err(|_| err("TRANSFER_INTERRUPTED", "The bounded chunk worker stopped."))?
    }
    async fn source_record(&self, id: &str) -> Result<SourceRecord, RpcError> {
        file_id(id)?;
        let r = sqlx::query("SELECT * FROM transfer_sources WHERE transfer_id=?")
            .bind(id)
            .fetch_optional(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?
            .ok_or_else(|| err("TRANSFER_NOT_FOUND", "This transfer is unavailable."))?;
        Ok(SourceRecord {
            id: r.get("transfer_id"),
            actor: r.get("actor"),
            root_id: r.get("root_id"),
            object_id: r.get("object_id"),
            path: r.get("relative_path"),
            source_identity: r.get("source_identity"),
            source_version: r.get("source_version"),
            name: r.get("name"),
            size: r.get::<i64, _>("size") as u64,
            manifest: r
                .get::<Option<&str>, _>("manifest")
                .map(parse)
                .transpose()?,
            hashes: r
                .get::<Option<&str>, _>("hashes")
                .map(parse)
                .transpose()?
                .unwrap_or_default(),
            snapshot_identity: r.get("snapshot_identity"),
            state: r.get("state"),
            error: r.get("error_code"),
            offered: r.get::<i64, _>("offered") != 0,
            sync_required: r.get::<i64, _>("sync_required") != 0,
            expires: r.get("expires_at"),
        })
    }
    async fn receive_record(&self, id: &str) -> Result<ReceiveRecord, RpcError> {
        file_id(id)?;
        let r = sqlx::query("SELECT * FROM transfer_receives WHERE transfer_id=?")
            .bind(id)
            .fetch_optional(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?
            .ok_or_else(|| err("TRANSFER_NOT_FOUND", "This transfer is unavailable."))?;
        Ok(ReceiveRecord {
            id: r.get("transfer_id"),
            actor: r.get("actor"),
            root_id: r.get("root_id"),
            parent: r.get("parent_path"),
            parent_identity: r.get("parent_identity"),
            name: r.get("name"),
            manifest: parse(r.get("manifest"))?,
            hashes: parse(r.get("hashes"))?,
            bitmap: parse(r.get("bitmap"))?,
            stage_identity: r.get("stage_identity"),
            state: r.get("state"),
            error: r.get("error_code"),
            bytes: r.get::<i64, _>("verified_bytes") as u64,
            result: r.get::<Option<&str>, _>("result").map(parse).transpose()?,
            intent_identity: r.get("intent_identity"),
            expires: r.get("expires_at"),
        })
    }
    async fn source_storage(&self) -> Result<safe_fs::SafeRoot, RpcError> {
        let expected: Option<String> =
            sqlx::query_scalar("SELECT identity FROM transfer_storage WHERE id=1")
                .fetch_optional(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        let dir = self.0.data_dir.clone();
        let e = expected.clone();
        let root = blocking(move || {
            let parent = safe_fs::SafeRoot::open(&dir, None)?;
            parent.private_directory(PRIVATE_DIR, e.as_deref())
        })
        .await?;
        if expected.is_none() {
            sqlx::query("INSERT INTO transfer_storage(id,identity) VALUES (1,?)")
                .bind(&root.identity)
                .execute(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        }
        Ok(root)
    }
    async fn staging(&self, r: &Root) -> Result<safe_fs::SafeRoot, RpcError> {
        let expected: Option<String> =
            sqlx::query_scalar("SELECT transfer_identity FROM file_roots WHERE root_id=?")
                .bind(&r.id)
                .fetch_one(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        let rr = r.clone();
        let e = expected.clone();
        let stage = blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            root.private_directory(STAGE_DIR, e.as_deref())
        })
        .await?;
        if expected.is_none() {
            sqlx::query("UPDATE file_roots SET transfer_identity=? WHERE root_id=?")
                .bind(&stage.identity)
                .bind(&r.id)
                .execute(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        }
        Ok(stage)
    }
    async fn authorize_source(&self, s: &SourceRecord, a: &Access) -> Result<Root, RpcError> {
        if s.actor != a.actor() {
            return Err(err(
                "TRANSFER_NOT_FOUND",
                "This transfer belongs to a different actor.",
            ));
        }
        a.require(&s.root_id, Capability::Read)?;
        if s.sync_required {
            a.require(&s.root_id, Capability::SyncRead)?;
        }
        let r = self.root(&s.root_id).await?;
        if policy::excluded(&s.path, &r.exclusions) {
            return Err(err(
                "ACCESS_DENIED",
                "The source is excluded by current policy.",
            ));
        }
        let e = self.entry(&r.id, &s.object_id).await?;
        if e.relative_path != s.path || e.info.identity != s.source_identity {
            return Err(err(
                "SOURCE_CHANGED",
                "The approved source moved or was replaced. No other version was substituted.",
            ));
        }
        let rr = r.clone();
        let path = s.path.clone();
        let identity = s.source_identity.clone();
        blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            let i = root.info(&path)?;
            if i.hidden || i.identity != identity {
                return Err(err(
                    "SOURCE_CHANGED",
                    "The approved source is no longer available.",
                ));
            }
            Ok(())
        })
        .await?;
        if s.expires < now() {
            return Err(err(
                "TRANSFER_EXPIRED",
                "The retained source snapshot expired. Start a newly reviewed transfer.",
            ));
        }
        Ok(r)
    }
    async fn authorize_receive(&self, s: &ReceiveRecord, a: &Access) -> Result<Root, RpcError> {
        if s.actor != a.actor() && !matches!(a, Access::Owner) {
            return Err(err(
                "TRANSFER_NOT_FOUND",
                "This receive belongs to a different actor.",
            ));
        }
        a.require(&s.root_id, Capability::Create)?;
        let r = self.root(&s.root_id).await?;
        let destination = if s.parent.is_empty() {
            s.name.clone()
        } else {
            format!("{}/{}", s.parent, s.name)
        };
        catalog::new_name(&s.name)?;
        if policy::excluded(&destination, &r.exclusions) {
            return Err(err(
                "ACCESS_DENIED",
                "The destination is excluded by current policy.",
            ));
        }
        let rr = r.clone();
        let parent = s.parent.clone();
        let expected = s.parent_identity.clone();
        blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            let p = root.info(&parent)?;
            if p.kind != "directory" || p.hidden || p.identity != expected {
                return Err(err(
                    "DESTINATION_CHANGED",
                    "The approved destination folder changed.",
                ));
            }
            Ok(())
        })
        .await?;
        if s.expires < now() && s.state != "completed" {
            return Err(err(
                "TRANSFER_EXPIRED",
                "This receive expired. Staging was preserved for explicit cleanup.",
            ));
        }
        Ok(r)
    }
    async fn transfer_status_locked(
        &self,
        id: &str,
        side: TransferSide,
        a: &Access,
    ) -> Result<TransferStatus, RpcError> {
        match side {
            TransferSide::Source => {
                let s = self.source_record(id).await?;
                self.authorize_source(&s, a).await?;
                Ok(source_status(&s))
            }
            TransferSide::Receive => {
                let s = self.receive_record(id).await?;
                self.authorize_receive(&s, a).await?;
                if s.state == "committing" {
                    self.reconcile_receive(&s).await?;
                }
                Ok(receive_status(&self.receive_record(id).await?))
            }
        }
    }
    async fn cancel_transfer_side(
        &self,
        id: &str,
        side: TransferSide,
        a: &Access,
    ) -> Result<(), RpcError> {
        match side {
            TransferSide::Source => {
                let s = self.source_record(id).await?;
                self.authorize_source(&s, a).await?;
                sqlx::query("UPDATE transfer_sources SET state='cancelled' WHERE transfer_id=? AND state NOT IN ('completed','cleaned')").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
            }
            TransferSide::Receive => {
                let s = self.receive_record(id).await?;
                self.authorize_receive(&s, a).await?;
                if s.state == "committing" {
                    self.reconcile_receive(&s).await?;
                }
                sqlx::query("UPDATE transfer_receives SET state='cancelled' WHERE transfer_id=? AND state NOT IN ('completed','committing','recovery_required','cleaned')").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
            }
        }
        Ok(())
    }
    pub(super) async fn recover_transfers(&self) -> Result<(), RpcError> {
        sqlx::query("UPDATE transfer_sources SET state='failed',error_code='SOURCE_PREPARATION_INTERRUPTED' WHERE state='preparing'").execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        sqlx::query("UPDATE transfer_jobs SET state='paused',phase='restart_review',error_code='RESTART_PAUSED' WHERE state IN ('queued','running','retry_wait','awaiting_approval')").execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let ids:Vec<String>=sqlx::query_scalar("SELECT transfer_id FROM transfer_receives WHERE state IN ('receiving','paused','blocked','committing','allocating') LIMIT 128").fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        for id in ids {
            let s = self.receive_record(&id).await?;
            if s.state == "committing" {
                let _ = self.reconcile_receive(&s).await;
            } else if s.state == "allocating" {
                self.receive_error(&id, "recovery_required", "STAGING_UNCONFIRMED")
                    .await?;
            } else if s.stage_identity.is_some() {
                if let Err(e) = self.revalidate_checkpoints(&s).await {
                    self.receive_error(&id, "blocked", &e.code).await?;
                }
            }
        }
        Ok(())
    }
}
fn source_status(s: &SourceRecord) -> TransferStatus {
    TransferStatus {
        transfer_id: s.id.clone(),
        state: s.state.clone(),
        manifest: s.manifest.clone(),
        missing: vec![],
        verified_bytes: if s.manifest.is_some() { s.size } else { 0 }.to_string(),
        error_code: s.error.clone(),
        result: None,
    }
}
fn receive_status(s: &ReceiveRecord) -> TransferStatus {
    TransferStatus {
        transfer_id: s.id.clone(),
        state: s.state.clone(),
        manifest: Some(s.manifest.clone()),
        missing: s
            .bitmap
            .iter()
            .enumerate()
            .filter(|(_, v)| !**v)
            .map(|(i, _)| i as u32)
            .collect(),
        verified_bytes: s.bytes.to_string(),
        error_code: s.error.clone(),
        result: s.result.clone(),
    }
}
