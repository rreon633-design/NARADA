use super::*;
#[derive(Clone)]
pub struct TransferJob {
    pub request: StartTransfer,
    pub state: String,
    pub attempts: u32,
    pub from_offer: bool,
}
impl Files {
    pub async fn enqueue_transfer(
        &self,
        input: StartTransfer,
        from_offer: bool,
    ) -> Result<Value, RpcError> {
        input.validate()?;
        catalog::new_name(&input.destination.name)?;
        let _g = self.0.gate.clone().read_owned().await;
        let _io = self.0.io.lock().await;
        let hash = hex::encode(Sha256::digest(encoded(&(&input, from_offer))?.as_bytes()));
        if let Some(saved) = sqlx::query_scalar::<_, String>(
            "SELECT request_hash FROM transfer_jobs WHERE transfer_id=?",
        )
        .bind(&input.transfer_id)
        .fetch_optional(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?
        {
            if hash != saved {
                return Err(err(
                    "IDEMPOTENCY_CONFLICT",
                    "That local job ID has different transfer arguments.",
                ));
            }
            return Ok(json!({"transfer_id":input.transfer_id,"queued":true}));
        }
        let (count,active):(i64,i64)=sqlx::query_as("SELECT COUNT(*),COALESCE(SUM(CASE WHEN state NOT IN ('completed','cancelled','failed','blocked') THEN 1 ELSE 0 END),0) FROM transfer_jobs").fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        if count >= MAX_HISTORY || active >= MAX_ACTIVE {
            return Err(err(
                "TRANSFER_QUOTA",
                "The bounded local queue or retained history is full.",
            ));
        }
        let t = now();
        sqlx::query("INSERT INTO transfer_jobs(transfer_id,peer_id,direction,request,request_hash,name,state,phase,from_offer,created_at,updated_at) VALUES (?,?,?,?,?,?,'queued','queued',?,?,?)").bind(&input.transfer_id).bind(&input.peer_id).bind(if input.direction==TransferDirection::Download{"download"}else{"upload"}).bind(encoded(&input)?).bind(hash).bind(&input.destination.name).bind(if from_offer{1i64}else{0}).bind(t).bind(t).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(json!({"transfer_id":input.transfer_id,"queued":true}))
    }
    pub async fn next_transfer_jobs(&self) -> Result<Vec<String>, RpcError> {
        sqlx::query_scalar("SELECT transfer_id FROM transfer_jobs WHERE state='queued' OR (state IN ('retry_wait','awaiting_approval') AND updated_at<=?) ORDER BY updated_at,created_at LIMIT 2").bind(now()-3).fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())
    }
    pub async fn transfer_job(&self, id: &str) -> Result<TransferJob, RpcError> {
        file_id(id)?;
        let r = sqlx::query(
            "SELECT request,state,attempts,from_offer FROM transfer_jobs WHERE transfer_id=?",
        )
        .bind(id)
        .fetch_optional(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?
        .ok_or_else(|| {
            err(
                "TRANSFER_NOT_FOUND",
                "This local transfer job is unavailable.",
            )
        })?;
        Ok(TransferJob {
            request: parse(r.get("request"))?,
            state: r.get("state"),
            attempts: r.get::<i64, _>("attempts") as u32,
            from_offer: r.get::<i64, _>("from_offer") != 0,
        })
    }
    pub async fn claim_transfer_job(&self, id: &str) -> Result<bool, RpcError> {
        let r=sqlx::query("UPDATE transfer_jobs SET state='running',phase='preparing',error_code=NULL,updated_at=? WHERE transfer_id=? AND state IN ('queued','retry_wait','awaiting_approval')").bind(now()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(r.rows_affected() == 1)
    }
    pub async fn transfer_progress(
        &self,
        id: &str,
        phase: &str,
        m: &TransferManifest,
        bytes: u64,
    ) -> Result<(), RpcError> {
        m.validate()?;
        if bytes > m.size_bytes()? {
            return Err(err(
                "INVALID_HANDSHAKE",
                "The peer reported progress beyond the manifest size.",
            ));
        }
        let existing: Option<String> =
            sqlx::query_scalar("SELECT manifest FROM transfer_jobs WHERE transfer_id=?")
                .bind(id)
                .fetch_one(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        if let Some(saved) = existing {
            let previous: TransferManifest = parse(&saved)?;
            if previous != *m {
                return Err(err(
                    "IDEMPOTENCY_CONFLICT",
                    "A resumed transfer cannot silently change its immutable manifest.",
                ));
            }
        }
        sqlx::query("UPDATE transfer_jobs SET phase=?,size=?,verified_bytes=CASE WHEN ?='manifest' THEN verified_bytes ELSE ? END,manifest=?,updated_at=? WHERE transfer_id=? AND state='running'").bind(phase).bind(m.size_bytes()? as i64).bind(phase).bind(bytes as i64).bind(encoded(m)?).bind(now()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub async fn transfer_waiting(&self, id: &str) -> Result<(), RpcError> {
        sqlx::query("UPDATE transfer_jobs SET state='awaiting_approval',phase='receiver_approval',updated_at=? WHERE transfer_id=? AND state='running'").bind(now()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub async fn transfer_job_failed(
        &self,
        id: &str,
        e: &RpcError,
        retry: bool,
    ) -> Result<(), RpcError> {
        sqlx::query("UPDATE transfer_jobs SET state=?,phase='attention',error_code=?,attempts=attempts+1,updated_at=? WHERE transfer_id=? AND state='running'").bind(if retry{"retry_wait"}else{"blocked"}).bind(&e.code).bind(now()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub async fn transfer_completed(
        &self,
        id: &str,
        m: &TransferManifest,
        receipt: Value,
        ack_pending: bool,
    ) -> Result<(), RpcError> {
        if receipt.get("state").and_then(Value::as_str) != Some("completed")
            || receipt.get("transfer_id").and_then(Value::as_str) != Some(id)
            || receipt.get("manifest_id").and_then(Value::as_str) != Some(m.manifest_id.as_str())
            || receipt.get("sha256").and_then(Value::as_str) != Some(m.sha256.as_str())
            || receipt.get("size").and_then(Value::as_str) != Some(m.size.as_str())
        {
            return Err(err("INVALID_RECEIPT","Completion requires a durable receiver receipt matching this manifest, size and SHA-256."));
        }
        let parsed: TransferReceipt = serde_json::from_value(receipt).map_err(|_| {
            err(
                "INVALID_RECEIPT",
                "The publication receipt contains unknown or malformed fields.",
            )
        })?;
        file_id(&parsed.root_id)?;
        catalog::new_name(&parsed.name)?;
        if parsed.published_at.len() > 64
            || chrono::DateTime::parse_from_rfc3339(&parsed.published_at).is_err()
        {
            return Err(err(
                "INVALID_RECEIPT",
                "The publication timestamp is invalid.",
            ));
        }
        let receipt = serde_json::to_value(parsed).map_err(|_| RpcError::storage())?;
        // A verified publication receipt outranks a racing pause/cancel. Never erase it.
        sqlx::query("UPDATE transfer_jobs SET state='completed',phase='published',manifest=?,size=?,verified_bytes=?,result=?,ack_pending=?,error_code=NULL,updated_at=? WHERE transfer_id=?").bind(encoded(m)?).bind(m.size_bytes()? as i64).bind(m.size_bytes()? as i64).bind(encoded(&receipt)?).bind(if ack_pending{1i64}else{0}).bind(now()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub async fn transfer_acknowledged(&self, id: &str) -> Result<(), RpcError> {
        sqlx::query(
            "UPDATE transfer_jobs SET ack_pending=0 WHERE transfer_id=? AND state='completed'",
        )
        .bind(id)
        .execute(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn transfers_owner(&self) -> Result<Value, RpcError> {
        let _g = self.0.gate.clone().read_owned().await;
        let rows=sqlx::query("SELECT transfer_id,peer_id,direction,name,state,phase,size,verified_bytes,error_code,manifest,result,ack_pending,created_at,updated_at FROM transfer_jobs ORDER BY CASE WHEN state NOT IN ('completed','cancelled','failed') THEN 0 ELSE 1 END,created_at DESC LIMIT 24").fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut jobs = Vec::new();
        for r in rows {
            let manifest = r
                .get::<Option<&str>, _>("manifest")
                .map(parse::<TransferManifest>)
                .transpose()?;
            jobs.push(json!({"transfer_id":r.get::<String,_>("transfer_id"),"peer_id":r.get::<String,_>("peer_id"),"direction":r.get::<String,_>("direction"),"name":r.get::<String,_>("name"),"state":r.get::<String,_>("state"),"phase":r.get::<String,_>("phase"),"size":r.get::<i64,_>("size").to_string(),"verified_bytes":r.get::<i64,_>("verified_bytes").to_string(),"error_code":r.get::<Option<String>,_>("error_code"),"sha256":manifest.map(|m|m.sha256),"result":r.get::<Option<&str>,_>("result").map(parse::<Value>).transpose()?,"ack_pending":r.get::<i64,_>("ack_pending")!=0,"created_at":r.get::<i64,_>("created_at"),"updated_at":r.get::<i64,_>("updated_at")}));
        }
        let ids:Vec<String>=sqlx::query_scalar("SELECT transfer_id FROM transfer_receives WHERE actor!='local-owner' ORDER BY CASE WHEN state NOT IN ('completed','cancelled','cleaned') THEN 0 ELSE 1 END,created_at DESC LIMIT 16").fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut receives = Vec::new();
        for id in ids {
            let s = self.receive_record(&id).await?;
            let root_label = self
                .root(&s.root_id)
                .await
                .map(|r| r.label)
                .unwrap_or_else(|_| "Unavailable approved root".into());
            receives.push(json!({"transfer_id":s.id,"peer_id":s.actor,"root_id":s.root_id,"root_label":root_label,"destination_directory":s.parent,"name":s.name,"state":s.state,"size":s.manifest.size,"verified_bytes":s.bytes.to_string(),"sha256":s.manifest.sha256,"error_code":s.error,"result":s.result}));
        }
        let rows=sqlx::query("SELECT peer_id,transfer_id,manifest,seen_at FROM transfer_remote_offers WHERE state='offered' ORDER BY seen_at DESC LIMIT 16").fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut offers = Vec::new();
        for r in rows {
            let m: TransferManifest = parse(r.get("manifest"))?;
            offers.push(json!({"peer_id":r.get::<String,_>("peer_id"),"transfer_id":r.get::<String,_>("transfer_id"),"manifest":m,"seen_at":r.get::<i64,_>("seen_at")}));
        }
        let ids:Vec<String>=sqlx::query_scalar("SELECT transfer_id FROM transfer_sources WHERE offered=1 AND state!='cleaned' ORDER BY created_at DESC LIMIT 8").fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut sent = Vec::new();
        for id in ids {
            let s = self.source_record(&id).await?;
            if s.offered {
                sent.push(json!({"transfer_id":s.id,"peer_id":s.actor,"name":s.name,"size":s.size.to_string(),"state":s.state,"expires_at":s.expires}));
            }
        }
        let source_reserved: i64 = sqlx::query_scalar(
            "SELECT COALESCE(SUM(size),0) FROM transfer_sources WHERE state!='cleaned'",
        )
        .fetch_one(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        let receive_reserved:i64=sqlx::query_scalar("SELECT COALESCE(SUM(CAST(json_extract(manifest,'$.size') AS INTEGER)),0) FROM transfer_receives WHERE state NOT IN ('cleaned','completed')").fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut view = json!({"jobs":jobs,"receives":receives,"offers":offers,"sent_offers":sent,"limits":{"max_file_bytes":MAX_TRANSFER_BYTES.to_string(),"chunk_bytes":TRANSFER_CHUNK_BYTES,"source_budget":TRANSFER_STORAGE_BUDGET.to_string(),"receive_budget":TRANSFER_STORAGE_BUDGET.to_string(),"source_reserved":source_reserved.to_string(),"receive_reserved":receive_reserved.to_string(),"max_active":MAX_ACTIVE,"retention_hours":24,"history_limit":MAX_HISTORY},"history_window":24});
        while serde_json::to_vec(&view)
            .map_err(|_| RpcError::storage())?
            .len()
            > MAX_FILE_RESPONSE - 1024
        {
            let mut trimmed = false;
            for field in ["jobs", "receives"] {
                let Some(rows) = view.get_mut(field).and_then(Value::as_array_mut) else {
                    continue;
                };
                if let Some(at) = rows.iter().rposition(|r| {
                    matches!(
                        r.get("state").and_then(Value::as_str),
                        Some("completed" | "cancelled" | "cleaned" | "failed")
                    )
                }) {
                    rows.remove(at);
                    trimmed = true;
                    break;
                }
            }
            if !trimmed {
                break;
            }
            view["view_notice"]=json!("Some terminal history is omitted to preserve the bounded owner frame. Active and ambiguous work was not trimmed.");
        }
        bounded(view)
    }
    pub async fn active_transfer_count(&self) -> Result<u32, RpcError> {
        let n:i64=sqlx::query_scalar("SELECT (SELECT COUNT(*) FROM transfer_jobs WHERE state IN ('queued','running','retry_wait','awaiting_approval'))+(SELECT COUNT(*) FROM transfer_receives WHERE actor!='local-owner' AND state IN ('receiving','committing','awaiting_approval'))").fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(n as u32)
    }
    pub async fn cache_transfer_offers(&self, peer: &str, value: Value) -> Result<(), RpcError> {
        transfer_peer(peer)?;
        let entries = value
            .get("offers")
            .and_then(Value::as_array)
            .ok_or_else(|| err("INVALID_HANDSHAKE", "The offer list is malformed."))?;
        if entries.len() > 8 {
            return Err(err(
                "INVALID_HANDSHAKE",
                "The peer exceeded the offer bound.",
            ));
        }
        let mut parsed = Vec::new();
        for v in entries {
            let id = v
                .get("transfer_id")
                .and_then(Value::as_str)
                .ok_or_else(|| err("INVALID_HANDSHAKE", "The offer has no identity."))?;
            file_id(id)?;
            let m: TransferManifest =
                serde_json::from_value(v.get("manifest").cloned().unwrap_or(Value::Null))
                    .map_err(|_| err("INVALID_HANDSHAKE", "Malformed offer manifest."))?;
            m.validate()?;
            catalog::new_name(&m.name)?;
            let source: TransferSource =
                serde_json::from_value(v.get("source").cloned().unwrap_or(Value::Null))
                    .map_err(|_| err("INVALID_HANDSHAKE", "Malformed offer source."))?;
            source.validate()?;
            parsed.push((id.to_owned(), m, source));
        }
        let mut tx = self.0.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query(
            "UPDATE transfer_remote_offers SET state='stale' WHERE peer_id=? AND state='offered'",
        )
        .bind(peer)
        .execute(&mut *tx)
        .await
        .map_err(|_| RpcError::storage())?;
        for (id, m, s) in parsed {
            sqlx::query("INSERT INTO transfer_remote_offers(peer_id,transfer_id,manifest,source,state,seen_at) VALUES (?,?,?,?,'offered',?) ON CONFLICT(peer_id,transfer_id) DO UPDATE SET state=CASE WHEN transfer_remote_offers.state='accepted' THEN 'accepted' ELSE 'offered' END,seen_at=excluded.seen_at WHERE transfer_remote_offers.manifest=excluded.manifest AND transfer_remote_offers.source=excluded.source").bind(peer).bind(id).bind(encoded(&m)?).bind(encoded(&s)?).bind(now()).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        }
        sqlx::query("DELETE FROM transfer_remote_offers WHERE state='stale'")
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())
    }
    pub async fn accept_transfer_offer(
        &self,
        input: AcceptTransferOffer,
    ) -> Result<Value, RpcError> {
        file_id(&input.transfer_id)?;
        transfer_peer(&input.peer_id)?;
        input.destination.validate()?;
        let text:String=sqlx::query_scalar("SELECT source FROM transfer_remote_offers WHERE peer_id=? AND transfer_id=? AND state IN ('offered','accepted')").bind(&input.peer_id).bind(&input.transfer_id).fetch_optional(&self.0.pool).await.map_err(|_|RpcError::storage())?.ok_or_else(||err("TRANSFER_NOT_FOUND","Check the peer for a current offer before accepting it."))?;
        let job = StartTransfer {
            transfer_id: input.transfer_id.clone(),
            peer_id: input.peer_id.clone(),
            direction: TransferDirection::Download,
            source: parse(&text)?,
            destination: input.destination,
        };
        let result = self.enqueue_transfer(job, true).await?;
        sqlx::query(
            "UPDATE transfer_remote_offers SET state='accepted' WHERE peer_id=? AND transfer_id=?",
        )
        .bind(&input.peer_id)
        .bind(&input.transfer_id)
        .execute(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        Ok(result)
    }
    pub async fn control_transfer(&self, input: TransferControl) -> Result<Value, RpcError> {
        file_id(&input.transfer_id)?;
        if let Some(n) = &input.name {
            catalog::new_name(n)?;
        }
        let _g = self.0.gate.clone().read_owned().await;
        let _io = self.0.io.lock().await;
        let id = &input.transfer_id;
        match input.target {
            TransferTarget::Job => {
                let job = self.transfer_job(id).await?;
                if job.state == "completed" {
                    return Ok(
                        json!({"state":"completed","notice":"Publication is already confirmed. Cancellation never deletes the destination."}),
                    );
                }
                let state = match input.action {
                    TransferVerb::Pause => "paused",
                    TransferVerb::Cancel => "cancelled",
                    TransferVerb::Resume | TransferVerb::Retry => "queued",
                    _ => {
                        return Err(err(
                            "INVALID_TRANSFER_CONTROL",
                            "This action is not a local queue control.",
                        ))
                    }
                };
                if input.name.is_some() {
                    return Err(err("INVALID_TRANSFER_CONTROL","Job retries preserve their manifest and destination. Rename an approved receive at the receiver, or create a new transfer."));
                }
                if job.state == "cancelled" && state != "cancelled" {
                    return Err(err(
                        "TRANSFER_CANCELLED",
                        "A cancelled job needs a new explicitly reviewed transfer.",
                    ));
                }
                if job.request.direction == TransferDirection::Download {
                    if let Ok(mut s) = self.receive_record(id).await {
                        if matches!(s.state.as_str(), "committing" | "recovery_required") {
                            self.reconcile_receive(&s).await?;
                            s = self.receive_record(id).await?;
                        }
                        if s.state == "completed" {
                            self.transfer_completed(
                                id,
                                &s.manifest,
                                s.result.ok_or_else(RpcError::storage)?,
                                true,
                            )
                            .await?;
                            return Ok(json!({"state":"completed"}));
                        }
                        if matches!(s.state.as_str(), "committing" | "recovery_required") {
                            return Err(err(
                                "COMMIT_OUTCOME_UNKNOWN",
                                "Inspect the receiver publication intent before changing this job.",
                            ));
                        }
                        if state == "queued" {
                            self.authorize_receive(&s, &Access::Owner).await?;
                            if s.stage_identity.is_some() {
                                self.revalidate_checkpoints(&s).await?;
                            } else {
                                self.allocate_receive(id).await?;
                            }
                            sqlx::query("UPDATE transfer_receives SET state='receiving',error_code=NULL WHERE transfer_id=?").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                        } else {
                            sqlx::query("UPDATE transfer_receives SET state=? WHERE transfer_id=?")
                                .bind(if state == "paused" {
                                    "paused"
                                } else {
                                    "cancelled"
                                })
                                .bind(id)
                                .execute(&self.0.pool)
                                .await
                                .map_err(|_| RpcError::storage())?;
                        }
                    }
                }
                if state == "cancelled" && job.request.direction == TransferDirection::Upload {
                    sqlx::query("UPDATE transfer_sources SET state='cancelled' WHERE transfer_id=? AND actor='local-owner' AND state='ready'").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                }
                sqlx::query("UPDATE transfer_jobs SET state=?,phase=?,error_code=NULL,attempts=0,updated_at=? WHERE transfer_id=? AND state!='completed'").bind(state).bind(state).bind(now()).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
            }
            TransferTarget::Receive => {
                let mut s = self.receive_record(id).await?;
                if matches!(s.state.as_str(), "committing" | "recovery_required") {
                    self.reconcile_receive(&s).await?;
                    s = self.receive_record(id).await?;
                }
                if s.state == "completed" {
                    return Ok(json!({"state":"completed","result":s.result}));
                }
                if s.state == "recovery_required" {
                    return Err(err(
                        "COMMIT_OUTCOME_UNKNOWN",
                        "The ambiguous intent is blocked. Do not delete or adopt unknown files.",
                    ));
                }
                match input.action {
                    TransferVerb::Accept | TransferVerb::Retry | TransferVerb::Resume => {
                        if matches!(s.state.as_str(), "cancelled" | "cleaned") {
                            return Err(err("TRANSFER_CANCELLED","A cancelled receive cannot be silently revived. Start a newly approved transfer."));
                        }
                        if let Some(name) = input.name {
                            sqlx::query("UPDATE transfer_receives SET name=?,intent_identity=NULL WHERE transfer_id=?").bind(name).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                            s = self.receive_record(id).await?;
                        }
                        self.authorize_receive(&s, &Access::Owner).await?;
                        if s.stage_identity.is_none() {
                            if let Err(e) = self.allocate_receive(id).await {
                                self.receive_error(id, "blocked", &e.code).await?;
                                return Err(e);
                            }
                        } else {
                            self.revalidate_checkpoints(&s).await?;
                            sqlx::query("UPDATE transfer_receives SET state='receiving',error_code=NULL WHERE transfer_id=?").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                        }
                    }
                    TransferVerb::Pause => {
                        sqlx::query("UPDATE transfer_receives SET state='paused' WHERE transfer_id=? AND state='receiving'").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                    }
                    TransferVerb::Cancel | TransferVerb::Decline => {
                        sqlx::query("UPDATE transfer_receives SET state='cancelled',error_code=NULL WHERE transfer_id=? AND state!='completed'").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                    }
                }
            }
            TransferTarget::Offer => {
                if !matches!(input.action, TransferVerb::Cancel | TransferVerb::Decline) {
                    return Err(err(
                        "INVALID_TRANSFER_CONTROL",
                        "Offers can be cancelled or declined, never implicitly granted.",
                    ));
                }
                let changed=sqlx::query("UPDATE transfer_sources SET state='cancelled' WHERE transfer_id=? AND offered=1 AND state NOT IN ('completed','cleaned')").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?.rows_affected();
                if changed == 0 {
                    sqlx::query("UPDATE transfer_remote_offers SET state='declined' WHERE transfer_id=? AND state='offered'").bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                }
            }
        }
        Ok(json!({"updated":true,"transfer_id":id}))
    }
    pub async fn cleanup_transfers(&self) -> Result<Value, RpcError> {
        // Serialized with all chunk, policy and publication operations. Never delete
        // originals, published files, unknown orphans, or unresolved commit intents.
        let _pin = self.0.pin_io.lock().await;
        let _g = self.0.gate.clone().read_owned().await;
        let _io = self.0.io.lock().await;
        self.cleanup_pin_metadata().await?;
        let mut cleaned = 0u32;
        let mut preserved = 0u32;
        let sources:Vec<String>=sqlx::query_scalar("SELECT transfer_id FROM transfer_sources WHERE state IN ('completed','cancelled','failed') OR (expires_at<? AND state='ready')").bind(now()).fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        for id in sources {
            let s = self.source_record(&id).await?;
            let live:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM transfer_jobs WHERE transfer_id=? AND state IN ('queued','running','paused','retry_wait','awaiting_approval'))").bind(&id).fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
            if live {
                preserved += 1;
                continue;
            }
            let removed = if let Some(identity) = s.snapshot_identity {
                match self.source_storage().await {
                    Ok(root) => {
                        let name = id.clone();
                        blocking(move || root.delete_owned(&name, &identity))
                            .await
                            .is_ok()
                    }
                    Err(_) => false,
                }
            } else {
                false
            };
            if removed {
                sqlx::query("UPDATE transfer_sources SET state='cleaned' WHERE transfer_id=?")
                    .bind(&id)
                    .execute(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?;
                cleaned += 1;
            } else {
                preserved += 1;
            }
        }
        let receives:Vec<String>=sqlx::query_scalar("SELECT transfer_id FROM transfer_receives WHERE state IN ('cancelled','failed') AND intent_identity IS NULL").fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        for id in receives {
            let s = self.receive_record(&id).await?;
            let removed = if let Some(identity) = s.stage_identity.clone() {
                match self.root(&s.root_id).await {
                    Ok(r) => match self.staging(&r).await {
                        Ok(root) => {
                            let name = id.clone();
                            blocking(move || root.delete_owned(&name, &identity))
                                .await
                                .is_ok()
                        }
                        Err(_) => false,
                    },
                    Err(_) => false,
                }
            } else {
                s.state == "cancelled"
            };
            if removed {
                sqlx::query("UPDATE transfer_receives SET state='cleaned' WHERE transfer_id=?")
                    .bind(&id)
                    .execute(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?;
                cleaned += 1;
            } else {
                preserved += 1;
            }
        }
        let cutoff = now() - TRANSFER_LIFETIME_SECS;
        for table in ["transfer_sources", "transfer_receives"] {
            sqlx::query(&format!(
                "DELETE FROM {table} WHERE state='cleaned' AND created_at<?"
            ))
            .bind(cutoff)
            .execute(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        }
        sqlx::query("DELETE FROM transfer_receives WHERE state='completed' AND completed_at<? AND NOT EXISTS(SELECT 1 FROM transfer_jobs j WHERE j.transfer_id=transfer_receives.transfer_id AND j.state NOT IN ('completed','cancelled')) AND NOT EXISTS(SELECT 1 FROM pins p WHERE p.active=1 AND p.pending_transfer_id=transfer_receives.transfer_id)").bind(cutoff).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        sqlx::query("DELETE FROM transfer_remote_offers WHERE state!='offered' AND seen_at<?")
            .bind(cutoff)
            .execute(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::query(
            "DELETE FROM transfer_jobs WHERE state IN ('completed','cancelled') AND updated_at<? AND NOT EXISTS(SELECT 1 FROM pins p WHERE p.active=1 AND p.pending_transfer_id=transfer_jobs.transfer_id)",
        )
        .bind(cutoff)
        .execute(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        Ok(
            json!({"cleaned":cleaned,"preserved":preserved,"notice":"Only identity-matched managed files were removed. Published and unknown objects were preserved."}),
        )
    }
}
