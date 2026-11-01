use super::*;
use narada_files::Access;
use serde::de::DeserializeOwned;
use serde_json::{json, Value};
const PACE: Duration = Duration::from_millis(160);
fn decode<T: DeserializeOwned>(value: Value) -> Result<T, RpcError> {
    serde_json::from_value(value).map_err(|_| {
        err(
            "INVALID_HANDSHAKE",
            "The transfer response violates the strict schema.",
        )
    })
}
impl Service {
    pub(crate) async fn route_transfer_chunk(
        &self,
        request: HttpRequest<Incoming>,
        bootstrap: bool,
        context: &SessionContext,
    ) -> Result<Vec<u8>, RpcError> {
        if bootstrap
            || context.cancel.is_cancelled()
            || request.uri().query().is_some()
            || request.uri().scheme().is_some()
        {
            return Err(err(
                "NO_PEER_ROUTE",
                "Binary transfer data requires an active mutual-TLS content session.",
            ));
        }
        {
            let mut budget = context
                .budget
                .lock()
                .map_err(|_| err("RATE_LIMITED", "The session budget is unavailable."))?;
            if budget.0.elapsed() > Duration::from_secs(1) {
                *budget = (Instant::now(), 0);
            }
            if budget.1 >= 12 {
                return Err(err(
                    "RATE_LIMITED",
                    "The control and data requests share the existing session rate bound.",
                ));
            }
            budget.1 += 1;
        }
        let (id, index) = chunk_address(request.uri().path())?;
        let upload = if request.method() == hyper::Method::PUT {
            true
        } else if request.method() == hyper::Method::GET {
            false
        } else {
            return Err(err(
                "NO_PEER_ROUTE",
                "Binary chunks support GET and PUT only.",
            ));
        };
        let _lane = self.0.data_slots.clone().try_acquire_owned().map_err(|_| {
            err(
                "DATA_BUSY",
                "Both binary data lanes are occupied; control traffic remains available.",
            )
        })?;
        let guard = self.0.files.gate().read_owned().await;
        if context.cancel.is_cancelled() {
            return Err(err(
                "SESSION_INVALIDATED",
                "Owner policy closed this transfer session.",
            ));
        }
        let fp = context
            .fingerprint
            .as_deref()
            .ok_or_else(|| err("CERTIFICATE_REQUIRED", "Mutual TLS is required."))?;
        let p = self.file_peer(fp, context).await?;
        let data = transport::binary_body(request, upload).await?;
        if context.cancel.is_cancelled() {
            return Err(err(
                "SESSION_INVALIDATED",
                "Owner policy closed this transfer session.",
            ));
        }
        self.0
            .files
            .transfer_chunk(
                id,
                index,
                if upload { Some(data) } else { None },
                Access::Peer {
                    id: p.id,
                    grants: p.permissions,
                },
                guard,
            )
            .await
    }
    pub(super) async fn start_transfer(&self, input: StartTransfer) -> Result<Value, RpcError> {
        input.validate()?;
        let peer = self.0.store.peer(&input.peer_id).await?;
        if peer.state != "active" || peer.endpoint.is_none() {
            return Err(err("PEER_INITIATES","A direct transfer needs an active saved outbound endpoint. Use a pull offer for an inbound-only peer."));
        }
        self.0.files.enqueue_transfer(input, false).await
    }
    pub(super) async fn create_transfer_offer(
        &self,
        input: CreateTransferOffer,
    ) -> Result<Value, RpcError> {
        let guard = self.0.files.gate().read_owned().await;
        let p = self.0.store.peer(&input.peer_id).await?;
        self.0
            .store
            .authorize(
                &p.id,
                p.revision,
                p.epoch,
                Capability::Read,
                Some(&input.source.root_id),
            )
            .await?;
        self.0
            .files
            .create_transfer_offer(
                input,
                Access::Peer {
                    id: p.id,
                    grants: p.permissions,
                },
                guard,
            )
            .await
    }
    async fn transfer_client(&self, peer_id: &str) -> Result<(Client, Peer), RpcError> {
        let p = self.0.store.peer(peer_id).await?;
        self.0
            .store
            .authorize_session(&p.id, p.revision, p.epoch)
            .await?;
        let endpoint = p.endpoint.as_deref().ok_or_else(|| {
            err(
                "PEER_INITIATES",
                "There is no saved outbound endpoint. The recipient must poll a pull offer.",
            )
        })?;
        let address = endpoint::endpoint(endpoint)?;
        let source = self.check_route(address).await?;
        let mut client = Client::connect(
            address,
            source,
            Some(&self.identity()?),
            &p.fingerprint,
            &p.id,
        )
        .await?;
        let h: Health = client.post("/api/v1/health", &Hello::default()).await?;
        h.hello.validate()?;
        if h.device_id != p.id
            || h.grant_revision == 0
            || h.session_epoch == 0
            || !wire::valid_health_operations(&h.operations)
            || !h.operations.iter().any(|s| s == "transfers.v1")
        {
            return Err(err("CAPABILITIES_INCOMPATIBLE","The pinned peer does not advertise the reviewed transfers.v1 protocol. No downgrade was attempted."));
        }
        Ok((client, p))
    }
    async fn local_transfer<T: DeserializeOwned>(
        &self,
        action: TransferAction,
    ) -> Result<T, RpcError> {
        let guard = self.0.files.gate().read_owned().await;
        decode(
            self.0
                .files
                .transfer_action(action, Access::Owner, guard)
                .await?,
        )
    }
    async fn remote_transfer<T: DeserializeOwned>(
        &self,
        client: &mut Client,
        action: TransferAction,
    ) -> Result<T, RpcError> {
        tokio::time::sleep(PACE).await;
        client
            .post(
                "/api/v1/transfers",
                &wire::TransferWire {
                    hello: Hello::default(),
                    version: TRANSFER_PROTOCOL_VERSION,
                    action,
                },
            )
            .await
    }
    async fn job_is_active(&self, id: &str, p: &Peer) -> Result<(), RpcError> {
        if self.0.stop.is_cancelled() || self.0.files.transfer_job(id).await?.state != "running" {
            return Err(err(
                "TRANSFER_PAUSED",
                "This local job was paused or cancelled.",
            ));
        }
        self.0
            .store
            .authorize_session(&p.id, p.revision, p.epoch)
            .await?;
        let endpoint = p
            .endpoint
            .as_deref()
            .ok_or_else(|| err("PEER_INITIATES", "The pinned endpoint was withdrawn."))?;
        self.check_route(endpoint::endpoint(endpoint)?).await?;
        Ok(())
    }
    pub(super) async fn kick_transfer_jobs(&self) -> Result<(), RpcError> {
        if self.0.stop.is_cancelled() {
            return Ok(());
        }
        let mut tasks = self.0.transfer_tasks.lock().await;
        tasks.retain(|_, t| !t.is_finished());
        if tasks.len() >= 2 {
            return Ok(());
        }
        for id in self.0.files.next_transfer_jobs().await? {
            if tasks.len() >= 2 {
                break;
            }
            let Ok(lane) = self.0.outbound_slots.clone().try_acquire_owned() else {
                break;
            };
            if tasks.contains_key(&id) || !self.0.files.claim_transfer_job(&id).await? {
                continue;
            }
            let key = id.clone();
            let service = self.clone();
            let task = tokio::spawn(async move {
                let _lane = lane;
                if let Err(e) = service.run_transfer_job(&id).await {
                    let attempts = service
                        .0
                        .files
                        .transfer_job(&id)
                        .await
                        .map(|j| j.attempts)
                        .unwrap_or(3);
                    let retry = attempts < 3
                        && (wire::transient(&e)
                            || matches!(
                                e.code.as_str(),
                                "RATE_LIMITED" | "DATA_BUSY" | "FILE_BUSY"
                            ));
                    let _ = service.0.files.transfer_job_failed(&id, &e, retry).await;
                }
            });
            tasks.insert(key, task);
        }
        Ok(())
    }
    async fn run_transfer_job(&self, id: &str) -> Result<(), RpcError> {
        let job = self.0.files.transfer_job(id).await?;
        let request = job.request;
        let download = request.direction == TransferDirection::Download;
        let is_pin = download && self.0.files.pin_requires_sync(id).await?;
        let (mut client, peer) = if is_pin {
            let (client, peer, _) = self.content_client(&request.peer_id, "sync.v1").await?;
            (client, peer)
        } else {
            self.transfer_client(&request.peer_id).await?
        };
        self.job_is_active(id, &peer).await?;
        let action = if job.from_offer {
            TransferAction::Status {
                transfer_id: id.into(),
                side: TransferSide::Source,
            }
        } else if is_pin {
            TransferAction::PrepareSync {
                transfer_id: id.into(),
                source: request.source,
            }
        } else {
            TransferAction::Prepare {
                transfer_id: id.into(),
                source: request.source,
            }
        };
        let source: TransferStatus = if download {
            self.remote_transfer(&mut client, action).await?
        } else {
            self.local_transfer(action).await?
        };
        if source.transfer_id != id || !matches!(source.state.as_str(), "ready" | "completed") {
            return Err(err("TRANSFER_NOT_READY","The immutable source is not ready. Failed snapshots require a newly reviewed source."));
        }
        let manifest = source.manifest.ok_or_else(|| {
            err(
                "INVALID_MANIFEST",
                "The peer did not provide an immutable source manifest.",
            )
        })?;
        manifest.validate()?;
        self.0
            .files
            .transfer_progress(id, "manifest", &manifest, 0)
            .await?;
        let action = TransferAction::OpenReceive {
            transfer_id: id.into(),
            destination: request.destination,
            manifest: manifest.clone(),
        };
        let mut receive: TransferStatus = if download {
            self.local_transfer(action).await?
        } else {
            self.remote_transfer(&mut client, action).await?
        };
        validate_receive(id, &manifest, &receive)?;
        if receive.state == "completed" {
            let receipt = receive.result.ok_or_else(|| {
                err(
                    "INVALID_RECEIPT",
                    "The receiver supplied no durable publication receipt.",
                )
            })?;
            self.0
                .files
                .transfer_completed(id, &manifest, receipt, download)
                .await?;
            self.finish_source(&mut client, id, &manifest, download)
                .await;
            return Ok(());
        }
        if receive.state == "awaiting_approval" {
            self.0.files.transfer_waiting(id).await?;
            return Ok(());
        }
        if receive.state != "receiving" {
            return Err(err(
                "RECEIVE_NOT_READY",
                "The receiver is paused, blocked or cancelled. Its owner must review the receive.",
            ));
        }
        // Every page is bounded; resending an identical page is idempotent.
        let mut offset = 0;
        loop {
            self.job_is_active(id, &peer).await?;
            let action = TransferAction::Manifest {
                transfer_id: id.into(),
                offset,
            };
            let page: ManifestPage = if download {
                self.remote_transfer(&mut client, action).await?
            } else {
                self.local_transfer(action).await?
            };
            if page.transfer_id != id
                || page.manifest_id != manifest.manifest_id
                || page.offset != offset
                || page.hashes.len() > TRANSFER_MANIFEST_PAGE
                || offset as usize + page.hashes.len() > manifest.chunk_count as usize
            {
                return Err(err(
                    "INVALID_MANIFEST",
                    "The immutable manifest page changed or exceeded its bounds.",
                ));
            }
            let end = offset + page.hashes.len() as u32;
            let expected_next = if end < manifest.chunk_count {
                Some(end)
            } else {
                None
            };
            if page.next_offset != expected_next
                || (expected_next.is_some() && page.hashes.len() != TRANSFER_MANIFEST_PAGE)
            {
                return Err(err(
                    "INVALID_MANIFEST",
                    "The manifest page sequence is inconsistent.",
                ));
            }
            for hash in &page.hashes {
                transfer_hash(hash)?;
            }
            let action = TransferAction::ManifestPage {
                transfer_id: id.into(),
                offset,
                hashes: page.hashes,
            };
            receive = if download {
                self.local_transfer(action).await?
            } else {
                self.remote_transfer(&mut client, action).await?
            };
            validate_receive(id, &manifest, &receive)?;
            if let Some(next) = page.next_offset {
                offset = next;
            } else {
                break;
            }
        }
        let mut verified = receive
            .verified_bytes
            .parse::<u64>()
            .map_err(|_| err("INVALID_HANDSHAKE", "The checkpoint byte count is invalid."))?;
        self.0
            .files
            .transfer_progress(id, "transferring", &manifest, verified)
            .await?;
        for index in receive.missing {
            self.job_is_active(id, &peer).await?;
            tokio::time::sleep(PACE).await;
            // One chunk per lane is admitted at a time, with backpressure from its
            // durable receiver ACK. Policy barriers cover the admitted local work.
            if download {
                let data = client.chunk(id, index, None).await?;
                self.job_is_active(id, &peer).await?;
                let guard = self.0.files.gate().read_owned().await;
                self.0
                    .store
                    .authorize_session(&peer.id, peer.revision, peer.epoch)
                    .await?;
                self.0
                    .files
                    .transfer_chunk(id.into(), index, Some(data), Access::Owner, guard)
                    .await?;
            } else {
                let guard = self.0.files.gate().read_owned().await;
                self.0
                    .store
                    .authorize_session(&peer.id, peer.revision, peer.epoch)
                    .await?;
                let data = self
                    .0
                    .files
                    .transfer_chunk(id.into(), index, None, Access::Owner, guard)
                    .await?;
                self.job_is_active(id, &peer).await?;
                let _send_policy = self.0.files.gate().read_owned().await;
                self.0
                    .store
                    .authorize_session(&peer.id, peer.revision, peer.epoch)
                    .await?;
                client.chunk(id, index, Some(data)).await?;
            }
            verified += manifest.chunk_length(index)? as u64;
            self.0
                .files
                .transfer_progress(id, "transferring", &manifest, verified)
                .await?;
        }
        self.job_is_active(id, &peer).await?;
        self.0
            .files
            .transfer_progress(id, "verifying", &manifest, verified)
            .await?;
        let action = TransferAction::Commit {
            transfer_id: id.into(),
        };
        let receipt: Value = if download {
            self.local_transfer(action).await?
        } else {
            self.remote_transfer(&mut client, action).await?
        };
        self.0
            .files
            .transfer_completed(id, &manifest, receipt, download)
            .await?;
        self.finish_source(&mut client, id, &manifest, download)
            .await;
        Ok(())
    }
    async fn finish_source(
        &self,
        client: &mut Client,
        id: &str,
        m: &TransferManifest,
        download: bool,
    ) {
        let action = TransferAction::Finish {
            transfer_id: id.into(),
            sha256: m.sha256.clone(),
        };
        let ack: Result<Value, RpcError> = if download {
            self.remote_transfer(client, action).await
        } else {
            self.local_transfer(action).await
        };
        if ack.is_ok() {
            let _ = self.0.files.transfer_acknowledged(id).await;
        }
    }
    pub(super) async fn poll_transfer_offers(&self) -> Result<Value, RpcError> {
        let mut task = self.0.offer_poll.lock().await;
        if task.as_ref().is_some_and(|t| !t.is_finished()) {
            return Ok(json!({"polling":true}));
        }
        let peers = self.0.store.peers().await?;
        let service = self.clone();
        *task = Some(tokio::spawn(async move {
            for peer in peers {
                if service.0.stop.is_cancelled() {
                    break;
                }
                if peer.state != "active" || peer.endpoint.is_none() {
                    continue;
                }
                if let Ok((mut client, _)) = service.transfer_client(&peer.id).await {
                    if let Ok(value) = service
                        .remote_transfer::<Value>(&mut client, TransferAction::Offers {})
                        .await
                    {
                        let _ = service.0.files.cache_transfer_offers(&peer.id, value).await;
                    }
                }
            }
        }));
        Ok(
            json!({"polling":true,"notice":"Offers are checked over saved pinned endpoints. Unreachable peers do not imply that no offers exist."}),
        )
    }
    pub(super) async fn stop_transfer_workers(&self) {
        if let Some(task) = self.0.offer_poll.lock().await.take() {
            let _ = task.await;
        }
        let tasks: Vec<_> = self
            .0
            .transfer_tasks
            .lock()
            .await
            .drain()
            .map(|(_, t)| t)
            .collect();
        for task in tasks {
            let _ = task.await;
        }
    }
}
fn chunk_address(path: &str) -> Result<(String, u32), RpcError> {
    let parts: Vec<_> = path.split('/').collect();
    if parts.len() != 7 || parts[..5] != ["", "api", "v1", "transfers", "chunks"] {
        return Err(err("NO_PEER_ROUTE", "This is not a bounded chunk address."));
    }
    file_id(parts[5])?;
    let index = parts[6]
        .parse::<u32>()
        .map_err(|_| err("INVALID_CHUNK", "A canonical chunk index is required."))?;
    if index as usize >= MAX_TRANSFER_CHUNKS || index.to_string() != parts[6] {
        return Err(err(
            "INVALID_CHUNK",
            "The chunk index is outside the explicit bound.",
        ));
    }
    Ok((parts[5].into(), index))
}
fn validate_receive(id: &str, m: &TransferManifest, s: &TransferStatus) -> Result<(), RpcError> {
    if s.transfer_id != id
        || s.manifest.as_ref() != Some(m)
        || s.missing.len() > m.chunk_count as usize
        || s.missing.windows(2).any(|a| a[0] >= a[1])
        || s.missing.iter().any(|i| *i >= m.chunk_count)
    {
        return Err(err(
            "INVALID_HANDSHAKE",
            "The receive checkpoint does not match this immutable manifest.",
        ));
    }
    let missing = s
        .missing
        .iter()
        .try_fold(0u64, |n, i| m.chunk_length(*i).map(|x| n + x as u64))?;
    if s.verified_bytes != (m.size_bytes()? - missing).to_string() {
        return Err(err(
            "INVALID_HANDSHAKE",
            "The peer checkpoint bitmap and byte count disagree.",
        ));
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn binary_routes_have_no_paths_queries_aliases_or_unbounded_indices() {
        let id = Uuid::new_v4().to_string();
        assert!(chunk_address(&format!("/api/v1/transfers/chunks/{id}/0")).is_ok());
        for v in ["../secret", "00", "-1", "256", "0/extra"] {
            assert!(chunk_address(&format!("/api/v1/transfers/chunks/{id}/{v}")).is_err());
        }
        assert!(chunk_address("/api/v1/transfers/chunks/%2e%2e/0").is_err());
    }
}
