//! One-way, version-preserving pins. Each refresh publishes a new no-overwrite
//! file; a failed refresh never replaces the last known-good local copy.
use super::*;
use sha2::{Digest, Sha256};
#[derive(Clone)]
pub struct PinWork {
    pub pin_id: String,
    pub peer_id: String,
    pub source: TransferSource,
    pub destination: TransferDestination,
    pub state: String,
    pub pending: Option<String>,
    pub pending_request: Option<StartTransfer>,
    pub current_version: Option<String>,
    pub current_receipt: Option<Value>,
}
fn parse<T: serde::de::DeserializeOwned>(s: &str) -> Result<T, RpcError> {
    serde_json::from_str(s).map_err(|_| RpcError::storage())
}
fn enc<T: serde::Serialize>(v: &T) -> Result<String, RpcError> {
    serde_json::to_string(v).map_err(|_| RpcError::storage())
}
fn record(r: sqlx::sqlite::SqliteRow) -> Result<PinWork, RpcError> {
    Ok(PinWork {
        pin_id: r.get("pin_id"),
        peer_id: r.get("peer_id"),
        source: parse(r.get("source"))?,
        destination: parse(r.get("destination"))?,
        state: r.get("state"),
        pending: r.get("pending_transfer_id"),
        pending_request: r
            .get::<Option<&str>, _>("pending_request")
            .map(parse)
            .transpose()?,
        current_version: r.get("current_version"),
        current_receipt: r
            .get::<Option<&str>, _>("current_receipt")
            .map(parse)
            .transpose()?,
    })
}
impl Files {
    pub async fn pin_work(&self) -> Result<Vec<PinWork>, RpcError> {
        let rows = sqlx::query("SELECT * FROM pins WHERE active=1 ORDER BY created_at")
            .fetch_all(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        rows.into_iter().map(record).collect()
    }
    pub async fn pin_requires_sync(&self, transfer: &str) -> Result<bool, RpcError> {
        sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM pins WHERE pending_transfer_id=? AND active=1)",
        )
        .bind(transfer)
        .fetch_one(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())
    }
    pub async fn create_pin(&self, input: CreatePin, size: u64) -> Result<Value, RpcError> {
        file_id(&input.pin_id)?;
        chat_peer(&input.peer_id)?;
        input.source.validate()?;
        input.destination.validate()?;
        catalog::new_name(&input.destination.name)?;
        if size > MAX_TRANSFER_BYTES {
            return Err(err("TRANSFER_LIMIT", "The pin exceeds 64 MiB."));
        }
        let _pin = self.0.pin_io.lock().await;
        self.cleanup_pin_metadata().await?;
        let hash = hex::encode(Sha256::digest(enc(&input)?));
        if let Some(old) =
            sqlx::query_scalar::<_, String>("SELECT request_hash FROM pins WHERE pin_id=?")
                .bind(&input.pin_id)
                .fetch_optional(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?
        {
            if old != hash {
                return Err(err(
                    "IDEMPOTENCY_CONFLICT",
                    "This pin identity already describes another selection.",
                ));
            }
            return Ok(json!({"pin_id":input.pin_id,"saved":true}));
        }
        let history: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM pins")
            .fetch_one(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        if history >= 128 {
            return Err(err("PIN_QUOTA","The retained pin history is full; explicit cleanup can prune eligible inactive metadata without deleting files."));
        }
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM pins WHERE active=1")
            .fetch_one(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        if count >= 8 {
            return Err(err("PIN_QUOTA", "At most eight active pins are retained."));
        }
        self.root(&input.destination.root_id).await?;
        self.pin_budget(size).await?;
        let job = StartTransfer {
            transfer_id: uuid::Uuid::new_v4().to_string(),
            peer_id: input.peer_id.clone(),
            direction: TransferDirection::Download,
            source: input.source.clone(),
            destination: input.destination.clone(),
        };
        let t = chrono::Utc::now().timestamp();
        sqlx::query("INSERT INTO pins(pin_id,peer_id,request_hash,source,destination,state,pending_transfer_id,pending_request,reservation,created_at,updated_at) VALUES(?,?,?,?,?,'queued',?,?,?,?,?)").bind(&input.pin_id).bind(&input.peer_id).bind(hash).bind(enc(&input.source)?).bind(enc(&input.destination)?).bind(&job.transfer_id).bind(enc(&job)?).bind(size as i64).bind(t).bind(t).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        match self.enqueue_transfer(job, false).await {
            Ok(_) => Ok(json!({"pin_id":input.pin_id,"saved":true})),
            Err(e) => {
                self.pin_error(&input.pin_id, &e.code).await?;
                Err(e)
            }
        }
    }
    async fn pin_budget(&self, extra: u64) -> Result<(), RpcError> {
        let copies:i64=sqlx::query_scalar("SELECT COALESCE(SUM(v.size),0) FROM pin_versions v JOIN pins p ON p.pin_id=v.pin_id WHERE p.active=1").fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let pending: i64 =
            sqlx::query_scalar("SELECT COALESCE(SUM(reservation),0) FROM pins WHERE active=1")
                .fetch_one(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        if copies as u64 + pending as u64 + extra > 128 * 1024 * 1024 {
            return Err(err(
                "PIN_QUOTA",
                "The 128 MiB active-pin reservation is full; existing copies were preserved.",
            ));
        }
        Ok(())
    }
    pub async fn pin_error(&self, id: &str, code: &str) -> Result<(), RpcError> {
        sqlx::query("UPDATE pins SET state=CASE WHEN state='paused' THEN state ELSE 'attention' END,error_code=?,updated_at=? WHERE pin_id=? AND active=1").bind(code).bind(chrono::Utc::now().timestamp()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub async fn reconcile_pins(&self) -> Result<(), RpcError> {
        let _pin = self.0.pin_io.lock().await;
        for pin in self.pin_work().await? {
            let Some(id) = &pin.pending else { continue };
            let job = sqlx::query(
                "SELECT state,result,error_code FROM transfer_jobs WHERE transfer_id=?",
            )
            .bind(id)
            .fetch_optional(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
            let Some(job) = job else {
                if pin.state == "queued" {
                    if let Some(request) = pin.pending_request.clone() {
                        let _ = self.enqueue_transfer(request, false).await;
                    }
                }
                continue;
            };
            let state = job.get::<&str, _>("state");
            if state == "completed" {
                let encoded = job
                    .get::<Option<&str>, _>("result")
                    .ok_or_else(RpcError::storage)?;
                let receipt: TransferReceipt = parse(encoded)?;
                receipt.validate()?;
                let request = pin.pending_request.as_ref().ok_or_else(RpcError::storage)?;
                if receipt.transfer_id != *id
                    || receipt.root_id != request.destination.root_id
                    || receipt.name != request.destination.name
                {
                    return Err(err(
                        "INVALID_RECEIPT",
                        "The completed pin job does not match its selected destination.",
                    ));
                }
                let mut tx = self
                    .0
                    .pool
                    .begin_with("BEGIN IMMEDIATE")
                    .await
                    .map_err(|_| RpcError::storage())?;
                let ordinal: i64 = sqlx::query_scalar(
                    "SELECT COALESCE(MAX(ordinal),0)+1 FROM pin_versions WHERE pin_id=?",
                )
                .bind(&pin.pin_id)
                .fetch_one(&mut *tx)
                .await
                .map_err(|_| RpcError::storage())?;
                sqlx::query("INSERT INTO pin_versions(pin_id,ordinal,version,receipt,size) VALUES(?,?,?,?,?)").bind(&pin.pin_id).bind(ordinal).bind(&request.source.expected_version).bind(encoded).bind(receipt.size.parse::<i64>().map_err(|_|RpcError::storage())?).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
                sqlx::query("UPDATE pins SET current_version=?,current_receipt=?,pending_transfer_id=NULL,pending_request=NULL,reservation=0,state=CASE WHEN state='paused' THEN state ELSE 'current' END,error_code=NULL,updated_at=? WHERE pin_id=?").bind(&request.source.expected_version).bind(encoded).bind(chrono::Utc::now().timestamp()).bind(&pin.pin_id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
                tx.commit().await.map_err(|_| RpcError::storage())?;
            } else if matches!(state, "blocked" | "failed" | "cancelled") {
                self.pin_error(
                    &pin.pin_id,
                    job.get::<Option<&str>, _>("error_code")
                        .unwrap_or("TRANSFER_STOPPED"),
                )
                .await?;
            }
        }
        Ok(())
    }
    pub async fn pins_owner(&self) -> Result<Value, RpcError> {
        self.reconcile_pins().await?;
        let mut rows = Vec::new();
        for p in self.pin_work().await? {
            let versions=sqlx::query("SELECT ordinal,version,receipt,size FROM pin_versions WHERE pin_id=? ORDER BY ordinal DESC").bind(&p.pin_id).fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
            let versions=versions.into_iter().map(|r|Ok(json!({"ordinal":r.get::<i64,_>("ordinal"),"version":r.get::<&str,_>("version"),"receipt":parse::<Value>(r.get("receipt"))?,"size":r.get::<i64,_>("size").to_string()}))).collect::<Result<Vec<_>,RpcError>>()?;
            let error: Option<String> =
                sqlx::query_scalar("SELECT error_code FROM pins WHERE pin_id=?")
                    .bind(&p.pin_id)
                    .fetch_one(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?;
            let progress = if let Some(id) = &p.pending {
                sqlx::query("SELECT state,phase,size,verified_bytes FROM transfer_jobs WHERE transfer_id=?").bind(id).fetch_optional(&self.0.pool).await.map_err(|_|RpcError::storage())?.map(|r|json!({"state":r.get::<&str,_>("state"),"phase":r.get::<&str,_>("phase"),"size":r.get::<i64,_>("size").to_string(),"verified_bytes":r.get::<i64,_>("verified_bytes").to_string()}))
            } else {
                None
            };
            rows.push(json!({"pin_id":p.pin_id,"peer_id":p.peer_id,"source":p.source,"destination":p.destination,"state":p.state,"current_version":p.current_version,"current_receipt":p.current_receipt,"pending_transfer_id":p.pending,"error_code":error,"versions":versions,"progress":progress}));
        }
        let value = json!({"pins":rows,"limits":{"pins":8,"versions_per_pin":3,"reservation_bytes":(128u64*1024*1024).to_string()},"policy":"Refresh publishes a new file. Previous versions and source-deleted copies are kept. Unpin never deletes published files."});
        if serde_json::to_vec(&value)
            .map_err(|_| RpcError::storage())?
            .len()
            > MAX_FILE_RESPONSE
        {
            return Err(err(
                "FILE_RESPONSE_LIMIT",
                "The pin view exceeded its bounded response.",
            ));
        }
        Ok(value)
    }
    pub async fn control_pin(&self, input: PinControl) -> Result<Value, RpcError> {
        file_id(&input.pin_id)?;
        self.reconcile_pins().await?;
        let _pin = self.0.pin_io.lock().await;
        let p = self
            .pin_work()
            .await?
            .into_iter()
            .find(|p| p.pin_id == input.pin_id)
            .ok_or_else(|| err("PIN_UNAVAILABLE", "The selected pin is unavailable."))?;
        match input.action {
            PinAction::Pause | PinAction::Unpin => {
                if let Some(id) = p.pending {
                    if let Err(e) = self
                        .control_transfer(TransferControl {
                            transfer_id: id,
                            target: TransferTarget::Job,
                            action: if matches!(input.action, PinAction::Unpin) {
                                TransferVerb::Cancel
                            } else {
                                TransferVerb::Pause
                            },
                            name: None,
                        })
                        .await
                    {
                        if e.code != "TRANSFER_NOT_FOUND" {
                            return Err(e);
                        }
                    }
                }
                let state = if matches!(input.action, PinAction::Unpin) {
                    "untracked"
                } else {
                    "paused"
                };
                sqlx::query("UPDATE pins SET state=?,active=?,updated_at=? WHERE pin_id=?")
                    .bind(state)
                    .bind(if state == "untracked" { 0 } else { 1 })
                    .bind(chrono::Utc::now().timestamp())
                    .bind(&p.pin_id)
                    .execute(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?;
            }
            PinAction::Refresh | PinAction::Resume => {
                if let Some(job) = p.pending_request {
                    let saved = self.transfer_job(&job.transfer_id).await;
                    let code: Option<String> = sqlx::query_scalar(
                        "SELECT error_code FROM transfer_jobs WHERE transfer_id=?",
                    )
                    .bind(&job.transfer_id)
                    .fetch_optional(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?
                    .flatten();
                    let abandon = matches!(&saved,Err(e) if e.code=="TRANSFER_NOT_FOUND")
                        || saved.as_ref().is_ok_and(|s| {
                            s.state == "cancelled"
                                || matches!(s.state.as_str(), "blocked" | "failed")
                                    && matches!(
                                        code.as_deref(),
                                        Some(
                                            "STALE_VERSION"
                                                | "TRANSFER_EXPIRED"
                                                | "TRANSFER_NOT_READY"
                                                | "TRANSFER_CANCELLED"
                                                | "SOURCE_CHANGED"
                                                | "NAME_COLLISION"
                                                | "DESTINATION_CHANGED"
                                        )
                                    )
                        });
                    if abandon {
                        if saved.is_ok() {
                            self.control_transfer(TransferControl {
                                transfer_id: job.transfer_id.clone(),
                                target: TransferTarget::Job,
                                action: TransferVerb::Cancel,
                                name: None,
                            })
                            .await?;
                            if self.transfer_job(&job.transfer_id).await?.state == "completed" {
                                return Ok(
                                    json!({"updated":true,"receipt_pending_reconciliation":true}),
                                );
                            }
                        }
                        sqlx::query("UPDATE pins SET state='check_requested',pending_transfer_id=NULL,pending_request=NULL,reservation=0,error_code=NULL,updated_at=? WHERE pin_id=?").bind(chrono::Utc::now().timestamp()).bind(&p.pin_id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                        // A fresh metadata fence must be consulted. Never relabel old snapshot bytes.
                        sqlx::query(
                            "UPDATE sync_cache_roots SET cursor=NULL WHERE peer_id=? AND root_id=?",
                        )
                        .bind(&p.peer_id)
                        .bind(&p.source.root_id)
                        .execute(&self.0.pool)
                        .await
                        .map_err(|_| RpcError::storage())?;
                    } else {
                        saved?;
                        self.control_transfer(TransferControl {
                            transfer_id: job.transfer_id,
                            target: TransferTarget::Job,
                            action: TransferVerb::Retry,
                            name: None,
                        })
                        .await?;
                        sqlx::query(
                            "UPDATE pins SET state='queued',error_code=NULL WHERE pin_id=?",
                        )
                        .bind(&p.pin_id)
                        .execute(&self.0.pool)
                        .await
                        .map_err(|_| RpcError::storage())?;
                    }
                } else {
                    sqlx::query(
                        "UPDATE pins SET state='check_requested',error_code=NULL WHERE pin_id=?",
                    )
                    .bind(&p.pin_id)
                    .execute(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?;
                }
            }
            PinAction::Check => {
                if p.state != "paused" {
                    sqlx::query("UPDATE pins SET state='check_requested',error_code=NULL WHERE pin_id=? AND pending_transfer_id IS NULL").bind(&p.pin_id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                }
            }
        }
        Ok(json!({"updated":true,"published_files_deleted":0}))
    }
    pub async fn sync_cursor(&self, peer: &str, root: &str) -> Result<Option<String>, RpcError> {
        sqlx::query_scalar("SELECT cursor FROM sync_cache_roots WHERE peer_id=? AND root_id=?")
            .bind(peer)
            .bind(root)
            .fetch_optional(&self.0.pool)
            .await
            .map(|v: Option<Option<String>>| v.flatten())
            .map_err(|_| RpcError::storage())
    }
    pub async fn apply_sync(
        &self,
        peer: &str,
        root: &str,
        snapshot: Option<Vec<SyncEntry>>,
        events: Vec<SyncEvent>,
        cursor: &str,
    ) -> Result<(), RpcError> {
        chat_peer(peer)?;
        file_id(root)?;
        file_id(cursor)?;
        let _pin = self.0.pin_io.lock().await;
        let mut tx = self
            .0
            .pool
            .begin_with("BEGIN IMMEDIATE")
            .await
            .map_err(|_| RpcError::storage())?;
        let live:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pins WHERE peer_id=? AND active=1 AND json_extract(source,'$.root_id')=?)").bind(peer).bind(root).fetch_one(&mut *tx).await.map_err(|_|RpcError::storage())?;
        if !live {
            return Err(err(
                "PIN_UNAVAILABLE",
                "The selected sync scope is no longer tracked.",
            ));
        }
        if let Some(entries) = snapshot {
            if entries.len() > 5000 || enc(&entries)?.len() > 2 * 1024 * 1024 {
                return Err(err(
                    "SYNC_SNAPSHOT_LIMIT",
                    "The remote snapshot exceeded its bounded contract.",
                ));
            }
            sqlx::query("DELETE FROM sync_cache_entries WHERE peer_id=? AND root_id=?")
                .bind(peer)
                .bind(root)
                .execute(&mut *tx)
                .await
                .map_err(|_| RpcError::storage())?;
            for e in entries {
                cache_entry(&mut tx, peer, root, &e).await?;
            }
        }
        for event in events {
            file_id(&event.object_id)?;
            match (event.kind.as_str(), event.entry) {
                ("upsert", Some(e)) if e.object_id == event.object_id => {
                    cache_entry(&mut tx, peer, root, &e).await?
                }
                ("deleted", None) => {
                    sqlx::query("DELETE FROM sync_cache_entries WHERE peer_id=? AND root_id=? AND object_id=?").bind(peer).bind(root).bind(event.object_id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
                }
                _ => {
                    return Err(err(
                        "INVALID_SYNC_PAGE",
                        "The journal event schema is invalid.",
                    ))
                }
            }
        }
        let (count,bytes):(i64,i64)=sqlx::query_as("SELECT COUNT(*),COALESCE(SUM(length(CAST(entry AS BLOB))),0) FROM sync_cache_entries WHERE peer_id=? AND root_id=?").bind(peer).bind(root).fetch_one(&mut *tx).await.map_err(|_|RpcError::storage())?;
        if count > 5000 || bytes > 2 * 1024 * 1024 {
            return Err(err("SYNC_SNAPSHOT_LIMIT","The transactional scope cache exceeds 5000 entries or 2 MiB; its previous state was retained."));
        }
        sqlx::query("INSERT INTO sync_cache_roots(peer_id,root_id,cursor,updated_at) VALUES(?,?,?,?) ON CONFLICT(peer_id,root_id) DO UPDATE SET cursor=excluded.cursor,updated_at=excluded.updated_at").bind(peer).bind(root).bind(cursor).bind(chrono::Utc::now().timestamp()).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        let pins=sqlx::query("SELECT * FROM pins WHERE peer_id=? AND active=1 AND state!='paused' AND pending_transfer_id IS NULL AND json_extract(source,'$.root_id')=?").bind(peer).bind(root).fetch_all(&mut *tx).await.map_err(|_|RpcError::storage())?;
        for row in pins {
            let p = record(row)?;
            let entry:Option<String>=sqlx::query_scalar("SELECT entry FROM sync_cache_entries WHERE peer_id=? AND root_id=? AND object_id=?").bind(peer).bind(root).bind(&p.source.object_id).fetch_optional(&mut *tx).await.map_err(|_|RpcError::storage())?;
            let (state, source) = if let Some(entry) = entry {
                let e: SyncEntry = parse(&entry)?;
                let mut source = p.source.clone();
                source.expected_version = e.version.clone();
                (
                    if e.kind != "file" {
                        "source_changed"
                    } else if p.current_version.as_deref() == Some(e.version.as_str()) {
                        "current"
                    } else {
                        "refresh_needed"
                    },
                    source,
                )
            } else {
                ("source_deleted", p.source.clone())
            };
            sqlx::query(
                "UPDATE pins SET state=?,source=?,error_code=NULL,updated_at=? WHERE pin_id=?",
            )
            .bind(state)
            .bind(enc(&source)?)
            .bind(chrono::Utc::now().timestamp())
            .bind(&p.pin_id)
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        }
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn cleanup_pin_metadata(&self) -> Result<(), RpcError> {
        let cutoff = chrono::Utc::now().timestamp() - 86400;
        let mut tx = self
            .0
            .pool
            .begin_with("BEGIN IMMEDIATE")
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM sync_cache_entries WHERE NOT EXISTS(SELECT 1 FROM pins p WHERE p.active=1 AND p.peer_id=sync_cache_entries.peer_id AND json_extract(p.source,'$.root_id')=sync_cache_entries.root_id)").execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        sqlx::query("DELETE FROM sync_cache_roots WHERE NOT EXISTS(SELECT 1 FROM pins p WHERE p.active=1 AND p.peer_id=sync_cache_roots.peer_id AND json_extract(p.source,'$.root_id')=sync_cache_roots.root_id)").execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        sqlx::query("DELETE FROM pin_versions WHERE pin_id IN (SELECT p.pin_id FROM pins p WHERE p.active=0 AND p.updated_at<? AND NOT EXISTS(SELECT 1 FROM transfer_jobs j WHERE j.transfer_id=p.pending_transfer_id AND j.state NOT IN ('completed','cancelled')))").bind(cutoff).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        sqlx::query("DELETE FROM pins WHERE active=0 AND updated_at<? AND NOT EXISTS(SELECT 1 FROM transfer_jobs j WHERE j.transfer_id=pins.pending_transfer_id AND j.state NOT IN ('completed','cancelled'))").bind(cutoff).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn refresh_pin(&self, id: &str) -> Result<(), RpcError> {
        let _pin = self.0.pin_io.lock().await;
        let p = self
            .pin_work()
            .await?
            .into_iter()
            .find(|p| p.pin_id == id)
            .ok_or_else(|| err("PIN_UNAVAILABLE", "The pin is unavailable."))?;
        if p.pending.is_some() || p.state != "refresh_needed" {
            return Ok(());
        }
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM pin_versions WHERE pin_id=?")
            .bind(id)
            .fetch_one(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        if count >= 3 {
            return Err(err("PIN_VERSION_LIMIT","Three local versions are retained. Unpin/archive deliberately before creating more; nothing was overwritten."));
        }
        let entry: String = sqlx::query_scalar(
            "SELECT entry FROM sync_cache_entries WHERE peer_id=? AND root_id=? AND object_id=?",
        )
        .bind(&p.peer_id)
        .bind(&p.source.root_id)
        .bind(&p.source.object_id)
        .fetch_one(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        let e: SyncEntry = parse(&entry)?;
        let size = e.size.parse::<u64>().map_err(|_| RpcError::storage())?;
        if size > MAX_TRANSFER_BYTES {
            return Err(err(
                "TRANSFER_LIMIT",
                "The changed pin exceeds the 64 MiB development limit.",
            ));
        }
        self.pin_budget(size).await?;
        let next_id = uuid::Uuid::new_v4().to_string();
        let mut dst = p.destination;
        dst.name = version_name(&dst.name, count + 1, &next_id);
        let job = StartTransfer {
            transfer_id: next_id,
            peer_id: p.peer_id,
            direction: TransferDirection::Download,
            source: p.source,
            destination: dst,
        };
        sqlx::query("UPDATE pins SET pending_transfer_id=?,pending_request=?,reservation=?,state='queued',error_code=NULL WHERE pin_id=?").bind(&job.transfer_id).bind(enc(&job)?).bind(size as i64).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        self.enqueue_transfer(job, false).await?;
        Ok(())
    }
}
async fn cache_entry(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    peer: &str,
    root: &str,
    e: &SyncEntry,
) -> Result<(), RpcError> {
    file_id(&e.object_id)?;
    file_version(&e.version)?;
    policy::relative(&e.relative_path)?;
    if e.name.len() > 180
        || !matches!(e.kind.as_str(), "file" | "directory")
        || e.size.parse::<u64>().is_err()
    {
        return Err(err("INVALID_SYNC_PAGE", "The remote entry is invalid."));
    }
    sqlx::query("INSERT INTO sync_cache_entries(peer_id,root_id,object_id,entry) VALUES(?,?,?,?) ON CONFLICT(peer_id,root_id,object_id) DO UPDATE SET entry=excluded.entry").bind(peer).bind(root).bind(&e.object_id).bind(enc(e)?).execute(&mut **tx).await.map_err(|_|RpcError::storage())?;
    Ok(())
}
fn version_name(name: &str, n: i64, id: &str) -> String {
    fn trim(s: &str, max: usize) -> String {
        let mut out = String::new();
        for c in s.chars() {
            if out.len() + c.len_utf8() > max {
                break;
            }
            out.push(c);
        }
        out
    }
    let (stem, ext) = name.rsplit_once('.').map_or((name, ""), |(s, e)| (s, e));
    let base = format!("{}-v{}-{}", trim(stem, 90), n, &id[..8]);
    if ext.is_empty() {
        base
    } else {
        format!("{}.{}", base, trim(ext, 24))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn refreshed_names_are_bounded_and_distinct() {
        let a = version_name(&"é".repeat(90), 2, "b798eff0-94c4-4728-a680-de49875b6dd6");
        assert!(a.len() <= 180);
        assert_ne!(
            a,
            version_name(&"é".repeat(90), 3, "b798eff0-94c4-4728-a680-de49875b6dd6")
        );
        catalog::new_name(&a).unwrap();
    }
}
