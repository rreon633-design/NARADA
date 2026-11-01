use super::*;
impl Files {
    pub(super) async fn open_receive(
        &self,
        id: &str,
        d: &TransferDestination,
        m: &TransferManifest,
        a: &Access,
    ) -> Result<TransferStatus, RpcError> {
        file_id(id)?;
        d.validate()?;
        catalog::new_name(&d.name)?;
        m.validate()?;
        catalog::new_name(&m.name)?;
        a.require(&d.root_id, Capability::Create)?;
        let request_hash = hex::encode(Sha256::digest(encoded(&(a.actor(), d, m))?.as_bytes()));
        if let Some(saved) = sqlx::query_scalar::<_, String>(
            "SELECT request_hash FROM transfer_receives WHERE transfer_id=?",
        )
        .bind(id)
        .fetch_optional(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?
        {
            if saved != request_hash {
                return Err(err(
                    "IDEMPOTENCY_CONFLICT",
                    "The transfer ID is already bound to another actor, destination or manifest.",
                ));
            }
            let s = self.receive_record(id).await?;
            self.authorize_receive(&s, a).await?;
            return Ok(receive_status(&s));
        }
        if matches!(a, Access::Owner) {
            let state: Option<String> =
                sqlx::query_scalar("SELECT state FROM transfer_jobs WHERE transfer_id=?")
                    .bind(id)
                    .fetch_optional(&self.0.pool)
                    .await
                    .map_err(|_| RpcError::storage())?;
            if state
                .as_deref()
                .is_some_and(|s| matches!(s, "cancelled" | "paused" | "blocked" | "failed"))
            {
                return Err(err(
                    "TRANSFER_PAUSED",
                    "The local job stopped before receive allocation; no staging was created.",
                ));
            }
        }
        let r = self.root(&d.root_id).await?;
        self.transfer_publication_barrier(&r.id, None).await?;
        let (parent, parent_identity) = if let Some(object) = &d.directory_id {
            let e = self.entry(&r.id, object).await?;
            if e.info.kind != "directory" {
                return Err(err(
                    "INVALID_DESTINATION",
                    "The receive destination must be a directory.",
                ));
            }
            (e.relative_path, e.info.identity)
        } else {
            (String::new(), r.identity.clone())
        };
        let destination = if parent.is_empty() {
            d.name.clone()
        } else {
            format!("{parent}/{}", d.name)
        };
        if policy::excluded(&destination, &r.exclusions) {
            return Err(err("ACCESS_DENIED", "The chosen destination is excluded."));
        }
        let rr = r.clone();
        let pp = parent.clone();
        let expected = parent_identity.clone();
        let leaf = d.name.clone();
        blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            let dir = root.open_relative(&pp, false)?;
            let actual = safe_fs::info(&dir.file)?;
            if actual.hidden || actual.identity != expected {
                return Err(err(
                    "DESTINATION_CHANGED",
                    "The destination folder changed.",
                ));
            }
            if root
                .names(&dir)?
                .iter()
                .any(|n| policy::fold(n) == policy::fold(&leaf))
            {
                return Err(err(
                    "NAME_COLLISION",
                    "The destination already exists. Choose a different name.",
                ));
            }
            Ok(())
        })
        .await?;
        let (count,reserved,active):(i64,i64,i64)=sqlx::query_as("SELECT COUNT(*),COALESCE(SUM(CASE WHEN state NOT IN ('completed','cleaned') THEN CAST(json_extract(manifest,'$.size') AS INTEGER) ELSE 0 END),0),COALESCE(SUM(CASE WHEN state NOT IN ('completed','cleaned','cancelled','failed') THEN 1 ELSE 0 END),0) FROM transfer_receives").fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        if count >= MAX_HISTORY
            || active >= MAX_ACTIVE
            || reserved as u64 + m.size_bytes()? > TRANSFER_STORAGE_BUDGET
        {
            return Err(err("TRANSFER_QUOTA","The receive reservation or history limit is full. Review and clean eligible staging."));
        }
        ensure_space(
            fs2::available_space(&r.path).map_err(storage_io)?,
            m.size_bytes()?,
        )?;
        let hashes: Vec<String> = Vec::new();
        let bitmap = vec![false; m.chunk_count as usize];
        let state = if matches!(a, Access::Owner) {
            "allocating"
        } else {
            "awaiting_approval"
        };
        let t = now();
        sqlx::query("INSERT INTO transfer_receives(transfer_id,actor,root_id,parent_path,parent_identity,name,manifest,hashes,bitmap,request_hash,state,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)").bind(id).bind(a.actor()).bind(&r.id).bind(&parent).bind(&parent_identity).bind(&d.name).bind(encoded(m)?).bind(encoded(&hashes)?).bind(encoded(&bitmap)?).bind(request_hash).bind(state).bind(t).bind(t+TRANSFER_LIFETIME_SECS).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        if matches!(a, Access::Owner) {
            if let Err(error) = self.allocate_receive(id).await {
                self.receive_error(id, "blocked", &error.code).await?;
                return Err(error);
            }
        }
        Ok(receive_status(&self.receive_record(id).await?))
    }
    pub(super) async fn allocate_receive(&self, id: &str) -> Result<(), RpcError> {
        let s = self.receive_record(id).await?;
        let r = self.root(&s.root_id).await?;
        self.transfer_publication_barrier(&r.id, Some(id)).await?;
        ensure_space(
            fs2::available_space(&r.path).map_err(storage_io)?,
            s.manifest.size_bytes()?,
        )?;
        let stage = self.staging(&r).await?;
        let name = s.id.clone();
        let expected = s.stage_identity.clone();
        let output = blocking(move || {
            stage.open_owned_write(&name, expected.is_none(), expected.as_deref())
        })
        .await?;
        let identity = safe_fs::info(&output.file)?.identity;
        sqlx::query(
            "UPDATE transfer_receives SET state='allocating',stage_identity=? WHERE transfer_id=?",
        )
        .bind(identity)
        .bind(id)
        .execute(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        let size = s.manifest.size_bytes()?;
        blocking(move || {
            output.file.set_len(size).map_err(storage_io)?;
            output.file.sync_all().map_err(storage_io)
        })
        .await?;
        sqlx::query(
            "UPDATE transfer_receives SET state='receiving',error_code=NULL WHERE transfer_id=?",
        )
        .bind(id)
        .execute(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn accept_manifest(
        &self,
        id: &str,
        offset: u32,
        hashes: &[String],
        a: &Access,
    ) -> Result<TransferStatus, RpcError> {
        let mut s = self.receive_record(id).await?;
        self.authorize_receive(&s, a).await?;
        if s.state != "receiving" {
            return Err(err(
                "RECEIVE_NOT_READY",
                "The receive must be approved and resumed before its manifest is accepted.",
            ));
        }
        if hashes.len() > TRANSFER_MANIFEST_PAGE
            || offset as usize > s.hashes.len()
            || !(offset as usize).is_multiple_of(TRANSFER_MANIFEST_PAGE)
            || offset as usize + hashes.len() > s.manifest.chunk_count as usize
        {
            return Err(err(
                "INVALID_MANIFEST",
                "The manifest page is not sequential, aligned and bounded.",
            ));
        }
        for h in hashes {
            transfer_hash(h)?;
        }
        let end = offset as usize + hashes.len();
        if end < s.manifest.chunk_count as usize && hashes.len() != TRANSFER_MANIFEST_PAGE {
            return Err(err(
                "INVALID_MANIFEST",
                "Only the last manifest page may be short.",
            ));
        }
        if (offset as usize) < s.hashes.len() {
            if end > s.hashes.len() || s.hashes[offset as usize..end] != *hashes {
                return Err(err(
                    "IDEMPOTENCY_CONFLICT",
                    "A repeated manifest page changed its hashes.",
                ));
            }
        } else {
            s.hashes.extend_from_slice(hashes);
        }
        if s.hashes.len() == s.manifest.chunk_count as usize
            && manifest_digest(&s.manifest, &s.hashes)? != s.manifest.manifest_id
        {
            return Err(err(
                "HASH_MISMATCH",
                "The manifest hash does not cover these ordered chunk hashes.",
            ));
        }
        sqlx::query("UPDATE transfer_receives SET hashes=? WHERE transfer_id=?")
            .bind(encoded(&s.hashes)?)
            .bind(id)
            .execute(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        Ok(receive_status(&s))
    }
    pub(super) async fn receive_chunk(
        &self,
        id: &str,
        index: u32,
        data: Vec<u8>,
        a: &Access,
    ) -> Result<(), RpcError> {
        let mut s = self.receive_record(id).await?;
        let r = self.authorize_receive(&s, a).await?;
        if s.state != "receiving" {
            return Err(err(
                "RECEIVE_NOT_READY",
                "This receive is paused, unapproved, cancelled or finished.",
            ));
        }
        let length = s.manifest.chunk_length(index)?;
        if data.len() != length
            || s.hashes.len() != s.manifest.chunk_count as usize
            || manifest_digest(&s.manifest, &s.hashes)? != s.manifest.manifest_id
        {
            return Err(err(
                "INVALID_CHUNK",
                "The complete manifest and exact chunk length are required.",
            ));
        }
        if s.hashes[index as usize] != hex::encode(Sha256::digest(&data)) {
            return Err(err(
                "HASH_MISMATCH",
                "Chunk digest mismatch. This checkpoint was not acknowledged.",
            ));
        }
        let stage = self.staging(&r).await?;
        let name = s.id.clone();
        let identity = s.stage_identity.clone().ok_or_else(RpcError::storage)?;
        blocking(move || {
            let mut out = stage.open_owned_write(&name, false, Some(&identity))?;
            out.file
                .seek(SeekFrom::Start(index as u64 * TRANSFER_CHUNK_BYTES as u64))
                .map_err(storage_io)?;
            out.file.write_all(&data).map_err(storage_io)?;
            out.file.sync_all().map_err(storage_io)?;
            stage.verify()?;
            Ok(())
        })
        .await?;
        s.bitmap[index as usize] = true;
        s.bytes = verified_bytes(&s)?;
        // Bytes reach the filesystem before SQLite FULL commits the bit; only then ACK.
        sqlx::query("UPDATE transfer_receives SET bitmap=?,verified_bytes=? WHERE transfer_id=? AND state='receiving'").bind(encoded(&s.bitmap)?).bind(s.bytes as i64).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn revalidate_checkpoints(&self, s: &ReceiveRecord) -> Result<(), RpcError> {
        let r = self.root(&s.root_id).await?;
        let stage = self.staging(&r).await?;
        let saved = s.clone();
        let bitmap = blocking(move || {
            let mut file = stage.open_relative(&saved.id, false)?;
            let info = safe_fs::info(&file.file)?;
            if saved.stage_identity.as_deref() != Some(info.identity.as_str())
                || info.size != saved.manifest.size_bytes()?
            {
                return Err(err(
                    "STAGING_CHANGED",
                    "The checkpoint file identity or size changed.",
                ));
            }
            let mut bitmap = saved.bitmap.clone();
            for (index, done) in bitmap.iter_mut().enumerate() {
                if !*done {
                    continue;
                }
                if saved.hashes.len() != saved.manifest.chunk_count as usize {
                    *done = false;
                    continue;
                }
                let mut data = vec![0u8; saved.manifest.chunk_length(index as u32)?];
                file.file
                    .seek(SeekFrom::Start(index as u64 * TRANSFER_CHUNK_BYTES as u64))
                    .map_err(storage_io)?;
                file.file.read_exact(&mut data).map_err(storage_io)?;
                *done = hex::encode(Sha256::digest(&data)) == saved.hashes[index];
            }
            stage.verify()?;
            Ok(bitmap)
        })
        .await?;
        let mut current = s.clone();
        current.bitmap = bitmap;
        sqlx::query("UPDATE transfer_receives SET bitmap=?,verified_bytes=? WHERE transfer_id=?")
            .bind(encoded(&current.bitmap)?)
            .bind(verified_bytes(&current)? as i64)
            .bind(&s.id)
            .execute(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn receive_error(
        &self,
        id: &str,
        state: &str,
        code: &str,
    ) -> Result<(), RpcError> {
        sqlx::query("UPDATE transfer_receives SET state=?,error_code=? WHERE transfer_id=? AND state!='completed'").bind(state).bind(code).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn commit_receive(&self, id: &str, a: &Access) -> Result<Value, RpcError> {
        let mut s = self.receive_record(id).await?;
        let r = self.authorize_receive(&s, a).await?;
        if s.state == "committing" {
            self.reconcile_receive(&s).await?;
            s = self.receive_record(id).await?;
        }
        if s.state == "completed" {
            return s.result.ok_or_else(RpcError::storage);
        }
        if s.state != "receiving" {
            return Err(err(
                "RECEIVE_NOT_READY",
                "Approve or explicitly resume this receive before publication.",
            ));
        }
        self.transfer_publication_barrier(&r.id, Some(id)).await?;
        if s.bitmap.iter().any(|b| !*b) || s.hashes.len() != s.manifest.chunk_count as usize {
            return Err(err(
                "TRANSFER_INCOMPLETE",
                "Missing chunks remain. No destination was published.",
            ));
        }
        if manifest_digest(&s.manifest, &s.hashes)? != s.manifest.manifest_id {
            return Err(err(
                "HASH_MISMATCH",
                "The complete manifest failed verification.",
            ));
        }
        let stage = self.staging(&r).await?;
        let saved = s.clone();
        let verified = blocking(move || {
            let mut file = stage.open_relative(&saved.id, false)?;
            let info = safe_fs::info(&file.file)?;
            if saved.stage_identity.as_deref() != Some(info.identity.as_str())
                || info.size != saved.manifest.size_bytes()?
            {
                return Err(err(
                    "STAGING_CHANGED",
                    "The complete staging identity or size changed.",
                ));
            }
            let (hash, hashes) = hash_reader(&mut file.file, info.size)?;
            if hash != saved.manifest.sha256 || hashes != saved.hashes {
                return Err(err(
                    "HASH_MISMATCH",
                    "Full-file verification failed. No publication occurred.",
                ));
            }
            stage.verify()?;
            Ok(info)
        })
        .await;
        let verified = match verified {
            Ok(info) => info,
            Err(e) => {
                self.receive_error(id, "blocked", &e.code).await?;
                return Err(e);
            }
        };
        sqlx::query("UPDATE transfer_receives SET state='committing',intent_identity=?,error_code=NULL WHERE transfer_id=? AND state='receiving'").bind(&verified.identity).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let root = r.clone();
        let saved = s.clone();
        let destination = destination(&s);
        let target = destination.clone();
        let published = blocking(move || {
            let root = safe_fs::SafeRoot::open(&root.path, Some(&root.identity))?;
            let p = root.info(&saved.parent)?;
            if p.identity != saved.parent_identity {
                return Err(err(
                    "DESTINATION_CHANGED",
                    "The destination changed before publication.",
                ));
            }
            let info = root.rename(
                &format!("{STAGE_DIR}/{}", saved.id),
                &target,
                &verified.identity,
                &verified.version,
            )?;
            flush_publication(&root, &target, &info.identity)?;
            Ok(info)
        })
        .await;
        match published {
            Ok(info) => {
                self.record_publication(&s, &destination, &info.identity)
                    .await?;
            }
            Err(e) => {
                if matches!(
                    e.code.as_str(),
                    "NAME_COLLISION" | "DESTINATION_CHANGED" | "STALE_VERSION"
                ) {
                    self.receive_not_published(id, &e.code).await?;
                } else {
                    self.receive_error(id, "recovery_required", "COMMIT_OUTCOME_UNKNOWN")
                        .await?;
                }
                return Err(e);
            }
        }
        self.0.dirty.store(true, Ordering::Release);
        self.receive_record(id)
            .await?
            .result
            .ok_or_else(RpcError::storage)
    }
    async fn receive_not_published(&self, id: &str, code: &str) -> Result<(), RpcError> {
        sqlx::query("UPDATE transfer_receives SET state='blocked',error_code=?,intent_identity=NULL WHERE transfer_id=? AND state!='completed'").bind(code).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    async fn record_publication(
        &self,
        s: &ReceiveRecord,
        path: &str,
        identity: &str,
    ) -> Result<(), RpcError> {
        let result = json!({"transfer_id":s.id,"state":"completed","manifest_id":s.manifest.manifest_id,"root_id":s.root_id,"name":s.name,"size":s.manifest.size,"sha256":s.manifest.sha256,"published_at":chrono::Utc::now().to_rfc3339()});
        // Identity and path are internal recovery facts, never accepted from clients.
        let _ = (path, identity);
        sqlx::query("UPDATE transfer_receives SET state='completed',verified_bytes=?,result=?,completed_at=?,error_code=NULL WHERE transfer_id=? AND state IN ('committing','recovery_required')").bind(s.manifest.size_bytes()? as i64).bind(encoded(&result)?).bind(now()).bind(&s.id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn reconcile_receive(&self, s: &ReceiveRecord) -> Result<(), RpcError> {
        let Some(identity) = s.intent_identity.clone() else {
            return self
                .receive_error(&s.id, "recovery_required", "COMMIT_OUTCOME_UNKNOWN")
                .await;
        };
        let root = match self.root(&s.root_id).await {
            Ok(r) => r,
            Err(_) => {
                return self
                    .receive_error(&s.id, "recovery_required", "ROOT_UNAVAILABLE")
                    .await
            }
        };
        let target = destination(s);
        let saved = s.clone();
        let path = target.clone();
        let outcome = blocking(move || {
            let root = safe_fs::SafeRoot::open(&root.path, Some(&root.identity))?;
            if root.info(&saved.parent)?.identity != saved.parent_identity {
                return Ok("recovery_required");
            }
            let stage_path = format!("{STAGE_DIR}/{}", saved.id);
            let staged = root.info(&stage_path);
            let published = root.info(&path);
            match (staged, published) {
                (Err(a), Ok(info))
                    if a.code == "OBJECT_UNAVAILABLE"
                        && info.identity == identity
                        && info.size == saved.manifest.size_bytes()? =>
                {
                    let mut file = root.open_relative(&path, false)?;
                    let (hash, hashes) = hash_reader(&mut file.file, info.size)?;
                    drop(file);
                    if hash != saved.manifest.sha256 || hashes != saved.hashes {
                        return Ok("recovery_required");
                    }
                    flush_publication(&root, &path, &identity)?;
                    Ok("completed")
                }
                (Ok(info), Err(e))
                    if info.identity == identity && e.code == "OBJECT_UNAVAILABLE" =>
                {
                    Ok("not_applied")
                }
                (Ok(info), Ok(other))
                    if info.identity == identity && other.identity != identity =>
                {
                    Ok("collision")
                }
                _ => Ok("recovery_required"),
            }
        })
        .await
        .unwrap_or("recovery_required");
        match outcome {
            "completed" => {
                self.record_publication(s, &target, s.intent_identity.as_deref().unwrap_or(""))
                    .await
            }
            "not_applied" => {
                self.receive_not_published(&s.id, "COMMIT_NOT_APPLIED")
                    .await
            }
            "collision" => self.receive_not_published(&s.id, "NAME_COLLISION").await,
            _ => {
                self.receive_error(&s.id, "recovery_required", "COMMIT_OUTCOME_UNKNOWN")
                    .await
            }
        }
    }
}
fn destination(s: &ReceiveRecord) -> String {
    if s.parent.is_empty() {
        s.name.clone()
    } else {
        format!("{}/{}", s.parent, s.name)
    }
}
fn verified_bytes(s: &ReceiveRecord) -> Result<u64, RpcError> {
    let mut n = 0;
    for (i, done) in s.bitmap.iter().enumerate() {
        if *done {
            n += s.manifest.chunk_length(i as u32)? as u64;
        }
    }
    Ok(n)
}
fn flush_publication(root: &safe_fs::SafeRoot, path: &str, identity: &str) -> Result<(), RpcError> {
    let file = root.open_owned_write(path, false, Some(identity))?;
    file.file.sync_all().map_err(storage_io)?;
    drop(file);
    #[cfg(target_os = "linux")]
    {
        let parent = path.rsplit_once('/').map_or("", |p| p.0);
        root.open_relative(parent, false)?
            .file
            .sync_all()
            .map_err(storage_io)?;
        root.open_relative(STAGE_DIR, false)?
            .file
            .sync_all()
            .map_err(storage_io)?;
    }
    root.verify()
}
