use super::*;
impl Files {
    pub(super) async fn prepare_source(
        &self,
        id: &str,
        input: &TransferSource,
        a: &Access,
        offered: bool,
        sync_required: bool,
    ) -> Result<TransferStatus, RpcError> {
        file_id(id)?;
        input.validate()?;
        a.require(&input.root_id, Capability::Read)?;
        if sync_required {
            a.require(&input.root_id, Capability::SyncRead)?;
        }
        let body = if sync_required {
            encoded(&(a.actor(), input, offered, "sync.v1"))?
        } else {
            encoded(&(a.actor(), input, offered))?
        };
        let request_hash = hex::encode(Sha256::digest(body.as_bytes()));
        if let Some(saved) = sqlx::query_scalar::<_, String>(
            "SELECT request_hash FROM transfer_sources WHERE transfer_id=?",
        )
        .bind(id)
        .fetch_optional(&self.0.pool)
        .await
        .map_err(|_| RpcError::storage())?
        {
            if saved != request_hash {
                return Err(err(
                    "IDEMPOTENCY_CONFLICT",
                    "The transfer ID is bound to another source, actor or offer.",
                ));
            }
            let row = self.source_record(id).await?;
            self.authorize_source(&row, a).await?;
            return Ok(source_status(&row));
        }
        let r = self.root(&input.root_id).await?;
        let e = self.entry(&r.id, &input.object_id).await?;
        if policy::excluded(&e.relative_path, &r.exclusions) {
            return Err(err("ACCESS_DENIED", "The source is excluded."));
        }
        if e.info.kind != "file" || e.info.hidden {
            return Err(err(
                "TRANSFER_UNSUPPORTED",
                "Select an ordinary visible file; recursive transfers are not implemented.",
            ));
        }
        if e.info.version != input.expected_version {
            return Err(err(
                "STALE_VERSION",
                "Refresh the selected source before starting a transfer.",
            ));
        }
        if e.info.size > MAX_TRANSFER_BYTES {
            return Err(err(
                "TRANSFER_LIMIT",
                "The source exceeds the explicit 64 MiB development limit.",
            ));
        }
        let (count,reserved,active):(i64,i64,i64)=sqlx::query_as("SELECT COUNT(*),COALESCE(SUM(CASE WHEN state!='cleaned' THEN size ELSE 0 END),0),COALESCE(SUM(CASE WHEN state IN ('preparing','ready') THEN 1 ELSE 0 END),0) FROM transfer_sources").fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        if count >= MAX_HISTORY
            || active >= MAX_ACTIVE
            || reserved as u64 + e.info.size > TRANSFER_STORAGE_BUDGET
        {
            return Err(err("TRANSFER_QUOTA","Source retention is full. Cancel finished offers and explicitly clean eligible staging."));
        }
        ensure_space(
            fs2::available_space(&self.0.data_dir).map_err(storage_io)?,
            e.info.size,
        )?;
        let t = now();
        sqlx::query("INSERT INTO transfer_sources(transfer_id,actor,root_id,object_id,relative_path,source_identity,source_version,name,size,request_hash,state,offered,sync_required,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,'preparing',?,?,?,?)")
            .bind(id).bind(a.actor()).bind(&r.id).bind(&e.object_id).bind(&e.relative_path).bind(&e.info.identity).bind(&e.info.version).bind(&e.name).bind(e.info.size as i64).bind(request_hash).bind(if offered{1i64}else{0}).bind(if sync_required{1i64}else{0}).bind(t).bind(t+TRANSFER_LIFETIME_SECS).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let result = self.snapshot_source(id, &r, &e).await;
        if let Err(error) = &result {
            sqlx::query(
                "UPDATE transfer_sources SET state='failed',error_code=? WHERE transfer_id=?",
            )
            .bind(&error.code)
            .bind(id)
            .execute(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        }
        result?;
        Ok(source_status(&self.source_record(id).await?))
    }
    async fn snapshot_source(&self, id: &str, r: &Root, e: &Entry) -> Result<(), RpcError> {
        let storage = self.source_storage().await?;
        let name = id.to_owned();
        let output = blocking(move || storage.open_owned_write(&name, true, None)).await?;
        let identity = safe_fs::info(&output.file)?.identity;
        sqlx::query("UPDATE transfer_sources SET snapshot_identity=? WHERE transfer_id=?")
            .bind(&identity)
            .bind(id)
            .execute(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let rr = r.clone();
        let ee = e.clone();
        let manifest = blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            let mut original = root.open_relative(&ee.relative_path, false)?;
            let before = safe_fs::info(&original.file)?;
            if before.kind != "file"
                || before.hidden
                || before.version != ee.info.version
                || before.identity != ee.info.identity
                || before.size != ee.info.size
            {
                return Err(err(
                    "STALE_VERSION",
                    "The source changed before its immutable snapshot could be made.",
                ));
            }
            let mut output = output;
            let mut remaining = before.size;
            let mut buffer = vec![0u8; TRANSFER_CHUNK_BYTES];
            let mut full = Sha256::new();
            let mut hashes = Vec::new();
            while remaining > 0 {
                let n = remaining.min(TRANSFER_CHUNK_BYTES as u64) as usize;
                original
                    .file
                    .read_exact(&mut buffer[..n])
                    .map_err(storage_io)?;
                output.file.write_all(&buffer[..n]).map_err(storage_io)?;
                full.update(&buffer[..n]);
                hashes.push(hex::encode(Sha256::digest(&buffer[..n])));
                remaining -= n as u64;
            }
            let mut extra = [0];
            if original.file.read(&mut extra).map_err(storage_io)? != 0
                || safe_fs::info(&original.file)?.version != before.version
                || root.info(&ee.relative_path)?.version != before.version
            {
                return Err(err(
                    "SOURCE_CHANGED",
                    "The source changed during snapshotting. Mixed bytes were not offered.",
                ));
            }
            output.file.sync_all().map_err(storage_io)?;
            root.verify()?;
            let mut m = TransferManifest {
                version: TRANSFER_PROTOCOL_VERSION,
                manifest_id: String::new(),
                source_version: before.version,
                name: ee.name,
                size: before.size.to_string(),
                chunk_size: TRANSFER_CHUNK_BYTES as u32,
                chunk_count: hashes.len() as u32,
                sha256: hex::encode(full.finalize()),
            };
            m.manifest_id = manifest_digest(&m, &hashes)?;
            m.validate()?;
            Ok((m, hashes))
        })
        .await?;
        sqlx::query("UPDATE transfer_sources SET manifest=?,hashes=?,state='ready',error_code=NULL WHERE transfer_id=? AND state='preparing'").bind(encoded(&manifest.0)?).bind(encoded(&manifest.1)?).bind(id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub(super) async fn source_manifest_page(
        &self,
        id: &str,
        offset: u32,
        a: &Access,
    ) -> Result<ManifestPage, RpcError> {
        let s = self.source_record(id).await?;
        self.authorize_source(&s, a).await?;
        if !matches!(s.state.as_str(), "ready" | "completed") {
            return Err(err(
                "TRANSFER_NOT_READY",
                "The source snapshot is not ready.",
            ));
        }
        let m = s.manifest.as_ref().ok_or_else(RpcError::storage)?;
        if offset as usize > s.hashes.len()
            || !(offset as usize).is_multiple_of(TRANSFER_MANIFEST_PAGE)
        {
            return Err(err(
                "INVALID_MANIFEST",
                "Manifest page offsets must be aligned and bounded.",
            ));
        }
        let end = (offset as usize + TRANSFER_MANIFEST_PAGE).min(s.hashes.len());
        Ok(ManifestPage {
            transfer_id: id.into(),
            manifest_id: m.manifest_id.clone(),
            offset,
            hashes: s.hashes[offset as usize..end].to_vec(),
            next_offset: if end < s.hashes.len() {
                Some(end as u32)
            } else {
                None
            },
        })
    }
    pub(super) async fn read_source_chunk(
        &self,
        id: &str,
        index: u32,
        a: &Access,
    ) -> Result<Vec<u8>, RpcError> {
        let s = self.source_record(id).await?;
        self.authorize_source(&s, a).await?;
        if s.state != "ready" {
            return Err(err(
                "TRANSFER_NOT_READY",
                "The source is paused, completed, cancelled or unavailable.",
            ));
        }
        let m = s.manifest.as_ref().ok_or_else(RpcError::storage)?;
        let length = m.chunk_length(index)?;
        let expected = s
            .hashes
            .get(index as usize)
            .cloned()
            .ok_or_else(RpcError::storage)?;
        let storage = self.source_storage().await?;
        let name = s.id;
        let identity = s.snapshot_identity.ok_or_else(RpcError::storage)?;
        blocking(move || {
            let mut file = storage.open_relative(&name, false)?;
            if safe_fs::info(&file.file)?.identity != identity {
                return Err(err(
                    "STAGING_CHANGED",
                    "The immutable snapshot was replaced.",
                ));
            }
            file.file
                .seek(SeekFrom::Start(index as u64 * TRANSFER_CHUNK_BYTES as u64))
                .map_err(storage_io)?;
            let mut data = vec![0u8; length];
            file.file.read_exact(&mut data).map_err(storage_io)?;
            if hex::encode(Sha256::digest(&data)) != expected {
                return Err(err(
                    "HASH_MISMATCH",
                    "The retained source snapshot failed chunk verification.",
                ));
            }
            storage.verify()?;
            Ok(data)
        })
        .await
    }
    pub(super) async fn source_offers(&self, a: &Access) -> Result<Value, RpcError> {
        let ids:Vec<String>=sqlx::query_scalar("SELECT transfer_id FROM transfer_sources WHERE actor=? AND offered=1 AND state='ready' AND expires_at>=? ORDER BY created_at DESC LIMIT 8").bind(a.actor()).bind(now()).fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut offers = Vec::new();
        for id in ids {
            let s = self.source_record(&id).await?;
            if self.authorize_source(&s, a).await.is_ok() {
                offers.push(json!({"transfer_id":s.id,"manifest":s.manifest,"source":{"root_id":s.root_id,"object_id":s.object_id,"expected_version":s.source_version},"expires_at":s.expires}));
            }
        }
        Ok(json!({"offers":offers}))
    }
    pub async fn create_transfer_offer(
        &self,
        input: CreateTransferOffer,
        access: Access,
        guard: OwnedRwLockReadGuard<()>,
    ) -> Result<Value, RpcError> {
        file_id(&input.transfer_id)?;
        transfer_peer(&input.peer_id)?;
        input.source.validate()?;
        if access.actor() != input.peer_id {
            return Err(err(
                "ACCESS_DENIED",
                "The recipient identity must match the approved source grant.",
            ));
        }
        let permit = self
            .0
            .jobs
            .clone()
            .try_acquire_owned()
            .map_err(|_| err("FILE_BUSY", "The bounded file worker is busy."))?;
        let me = self.clone();
        tokio::spawn(async move {
            let _g = guard;
            let _p = permit;
            let _io = me.0.io.lock().await;
            let status = me
                .prepare_source(&input.transfer_id, &input.source, &access, true, false)
                .await?;
            serde_json::to_value(status).map_err(|_| RpcError::storage())
        })
        .await
        .map_err(|_| err("TRANSFER_INTERRUPTED", "The source offer worker stopped."))?
    }
}
