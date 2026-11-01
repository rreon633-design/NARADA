use super::*;
use crate::content::{Bundle, ChatAction, Content, PullReply, Receipt};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::{json, Value};
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct ChatWire {
    pub hello: Hello,
    pub version: u16,
    pub action: ChatAction,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct SyncWire {
    pub hello: Hello,
    pub version: u16,
    pub request: SyncRequest,
}
const PACE: Duration = Duration::from_millis(180);
impl Service {
    pub(super) fn content(&self) -> Result<Content, RpcError> {
        self.0.content.clone().ok_or_else(|| {
            self.0.content_error.clone().unwrap_or_else(|| {
                err(
                    "CONTENT_STORE_LOCKED",
                    "The protected content store is unavailable; no plaintext fallback exists.",
                )
            })
        })
    }
    pub(super) async fn content_owner(&self, command: &Command) -> Result<Value, RpcError> {
        match command {
            Command::ContentStatus => {
                let state = match self.content() {
                    Ok(c) => c.status().await,
                    Err(e) => Err(e),
                };
                Ok(state.unwrap_or_else(|e| json!({"ready":false,"error":e})))
            }
            Command::ListConversations => self.content()?.conversations().await,
            Command::ChatHistory(q) => self.content()?.history(q).await,
            Command::SaveChatDraft(q) => self.content()?.save_draft(q.clone()).await,
            Command::MarkChatRead(q) => self.content()?.mark_read(&q.peer_id).await,
            Command::ChatControl(q) => self.content()?.control(q.clone()).await,
            Command::SendChat(q) => {
                q.validate()?;
                let guard = self.0.files.gate().read_owned().await;
                let p = self.0.store.peer(&q.peer_id).await?;
                self.0
                    .store
                    .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
                    .await?;
                if q.attachment.is_some() {
                    self.0
                        .store
                        .authorize(&p.id, p.revision, p.epoch, Capability::Attachment, None)
                        .await?;
                }
                self.content()?
                    .queue(q.clone(), &self.0.files, &guard)
                    .await
            }
            Command::FetchAttachment(q) => {
                let c = self.content()?;
                let peer = c.attachment_peer(&q.message_id).await?;
                let _guard = self.0.files.gate().read_owned().await;
                let p = self.0.store.peer(&peer).await?;
                self.0
                    .store
                    .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
                    .await?;
                self.0
                    .store
                    .authorize(&p.id, p.revision, p.epoch, Capability::Attachment, None)
                    .await?;
                c.begin_fetch(&q.message_id).await
            }
            Command::PauseAttachment(q) => self.content()?.pause_attachment(&q.message_id).await,
            Command::ExportAttachment(q) => self.content()?.export(q.clone(), &self.0.files).await,
            Command::CleanupContent => self.content()?.cleanup().await,
            Command::SyncQuery(q) => self
                .0
                .files
                .sync_query(
                    q.clone(),
                    narada_files::Access::Owner,
                    self.0.files.gate().read_owned().await,
                    "owner",
                )
                .await
                .and_then(transport::value),
            Command::ListPins => self.0.files.pins_owner().await,
            Command::CreatePin(q) => {
                file_id(&q.pin_id)?;
                q.source.validate()?;
                q.destination.validate()?;
                let (mut client, _, health) = self.content_client(&q.peer_id, "sync.v1").await?;
                let grants = health
                    .permissions
                    .roots
                    .iter()
                    .find(|g| g.root_id == q.source.root_id)
                    .ok_or_else(|| {
                        err(
                            "ACCESS_DENIED",
                            "The remote owner has not granted this root.",
                        )
                    })?;
                if ![Capability::Browse, Capability::Read, Capability::SyncRead]
                    .iter()
                    .all(|c| grants.capabilities.contains(c))
                {
                    return Err(err(
                        "ACCESS_DENIED",
                        "Pins require remote Browse, Read and Sync read grants.",
                    ));
                }
                tokio::time::sleep(PACE).await;
                let detail: Value = client
                    .post(
                        "/api/v1/files",
                        &wire::FileWire {
                            hello: Hello::default(),
                            version: FILE_PROTOCOL_VERSION,
                            action: wire::FileWireAction::Query(FileQuery::Details {
                                root_id: q.source.root_id.clone(),
                                object_id: q.source.object_id.clone(),
                            }),
                        },
                    )
                    .await?;
                if detail.get("kind").and_then(Value::as_str) != Some("file")
                    || detail.get("version").and_then(Value::as_str)
                        != Some(q.source.expected_version.as_str())
                {
                    return Err(err(
                        "STALE_VERSION",
                        "Refresh the selected remote file before pinning.",
                    ));
                }
                let size = detail
                    .get("size")
                    .and_then(Value::as_str)
                    .ok_or_else(|| err("INVALID_SYNC_PAGE", "Missing remote file size."))?
                    .parse::<u64>()
                    .map_err(|_| err("INVALID_SYNC_PAGE", "Invalid remote size."))?;
                self.0.files.create_pin(q.clone(), size).await
            }
            Command::PinControl(q) => {
                let value = self.0.files.control_pin(q.clone()).await?;
                self.0.content_clock.lock().await.2 = Instant::now() - Duration::from_secs(31);
                Ok(value)
            }
            Command::RefreshSync => {
                self.0.content_clock.lock().await.2 = Instant::now() - Duration::from_secs(31);
                Ok(
                    json!({"requested":true,"note":"Metadata cursors and content completion are separate."}),
                )
            }
            Command::SupportBundle => {
                let s = self.0.core.snapshot().await?;
                let ready = match self.content() {
                    Ok(c) => c
                        .status()
                        .await
                        .map(|v| v.get("ready") == Some(&json!(true)))
                        .unwrap_or(false),
                    Err(_) => false,
                };
                Ok(
                    json!({"application":"NARADA","version":APP_VERSION,"platform":s.agent.platform,"schema_version":s.storage.schema_version,"peer_network_enabled":self.0.state.lock().await.config.enabled,"protected_content_ready":ready,"development_vault":cfg!(all(not(windows),feature="dev-vault")),"release_qualified":false,"native_runtime_qualified":false,"independent_security_review":"not_completed","redaction":"No peer identities, names, endpoints, filenames, paths, message content, ciphertext, attachment keys, sessions, tokens or receipts included."}),
                )
            }
            _ => Err(err(
                "UNKNOWN_COMMAND",
                "This is not a content/offline owner command.",
            )),
        }
    }
    pub(super) async fn route_content(
        &self,
        path: &str,
        request: HttpRequest<Incoming>,
        fp: &str,
        context: &SessionContext,
    ) -> Result<Value, RpcError> {
        let guard = self.0.files.gate().read_owned().await;
        if context.cancel.is_cancelled() {
            return Err(err(
                "SESSION_INVALIDATED",
                "The content session was invalidated.",
            ));
        }
        let p = self.file_peer(fp, context).await?;
        if path == "/api/v1/sync" {
            let message: SyncWire = transport::body(request).await?;
            message.hello.validate()?;
            if message.version != 1 {
                return Err(err("PROTOCOL_INCOMPATIBLE", "Unsupported sync protocol."));
            }
            let scope = format!("{}:{}", p.revision, p.epoch);
            return self
                .0
                .files
                .sync_query(
                    message.request,
                    narada_files::Access::Peer {
                        id: p.id,
                        grants: p.permissions,
                    },
                    guard,
                    &scope,
                )
                .await
                .and_then(transport::value);
        }
        let message: ChatWire = transport::body(request).await?;
        message.hello.validate()?;
        if message.version != CONTENT_PROTOCOL_VERSION {
            return Err(err(
                "PROTOCOL_INCOMPATIBLE",
                "Unsupported E2EE content protocol.",
            ));
        }
        self.0
            .store
            .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
            .await?;
        let c = self.content()?;
        match message.action {
            ChatAction::Bundle {} => c.bundle(&p.id).await.and_then(transport::value),
            ChatAction::Push { bundle, envelope } => c
                .receive(&p.id, &bundle, envelope, p.permissions.attachment)
                .await
                .and_then(transport::value),
            ChatAction::Pull { bundle } => {
                let envelope = c.pending(&p.id, &bundle, p.permissions.attachment).await?;
                let bundle = c.bundle(&p.id).await?;
                transport::value(PullReply { bundle, envelope })
            }
            ChatAction::Ack { receipt } => {
                c.acknowledge(&p.id, receipt).await?;
                Ok(json!({"acknowledged":true}))
            }
        }
    }
    pub(crate) async fn route_blob_chunk(
        &self,
        request: HttpRequest<Incoming>,
        bootstrap: bool,
        context: &SessionContext,
    ) -> Result<Vec<u8>, RpcError> {
        if bootstrap
            || context.cancel.is_cancelled()
            || request.method() != hyper::Method::GET
            || request.uri().query().is_some()
            || request.uri().scheme().is_some()
        {
            return Err(err(
                "NO_PEER_ROUTE",
                "Ciphertext chunks require an activated peer GET route.",
            ));
        }
        {
            let mut budget = context
                .budget
                .lock()
                .map_err(|_| err("RATE_LIMITED", "Session rate state is unavailable."))?;
            if budget.0.elapsed() > Duration::from_secs(1) {
                *budget = (Instant::now(), 0);
            }
            if budget.1 >= 12 {
                return Err(err(
                    "RATE_LIMITED",
                    "This content session exceeded its request budget.",
                ));
            }
            budget.1 += 1;
        }
        let path = request
            .uri()
            .path()
            .strip_prefix("/api/v1/blobs/chunks/")
            .ok_or_else(|| err("INVALID_CHUNK", "Invalid ciphertext chunk route."))?;
        let (id, part) = path
            .split_once('/')
            .ok_or_else(|| err("INVALID_CHUNK", "Missing chunk index."))?;
        file_id(id)?;
        let index = part
            .parse::<u32>()
            .map_err(|_| err("INVALID_CHUNK", "Invalid chunk index."))?;
        if part != index.to_string() || index as usize >= MAX_TRANSFER_CHUNKS {
            return Err(err("INVALID_CHUNK", "Non-canonical chunk index."));
        }
        let id = id.to_owned();
        let _slot = self
            .0
            .data_slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| err("DATA_BUSY", "The shared binary data lanes are full."))?;
        let _guard = self.0.files.gate().read_owned().await;
        let fp = context
            .fingerprint
            .as_deref()
            .ok_or_else(|| err("CERTIFICATE_REQUIRED", "Mutual TLS is required."))?;
        let p = self.file_peer(fp, context).await?;
        self.0
            .store
            .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
            .await?;
        self.0
            .store
            .authorize(&p.id, p.revision, p.epoch, Capability::Attachment, None)
            .await?;
        transport::binary_body(request, false).await?;
        self.content()?.blob_chunk(&p.id, &id, index).await
    }
    pub(super) async fn content_client(
        &self,
        peer: &str,
        operation: &str,
    ) -> Result<(Client, Peer, Health), RpcError> {
        let p = self.0.store.peer(peer).await?;
        self.0
            .store
            .authorize_session(&p.id, p.revision, p.epoch)
            .await?;
        let address = endpoint::endpoint(p.endpoint.as_deref().ok_or_else(|| {
            err(
                "PEER_INITIATES",
                "This peer must poll over its saved outbound connection.",
            )
        })?)?;
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
        h.permissions.validate()?;
        if h.device_id != p.id
            || h.grant_revision == 0
            || h.session_epoch == 0
            || !wire::valid_health_operations(&h.operations)
            || !h.operations.iter().any(|s| s == operation)
        {
            return Err(err("CAPABILITIES_INCOMPATIBLE","The pinned peer does not advertise this exact content protocol; no downgrade occurred."));
        }
        self.0
            .store
            .remote_permissions(&p.id, &h.permissions)
            .await?;
        Ok((client, p, h))
    }
    async fn chat_post<T: DeserializeOwned>(
        &self,
        client: &mut Client,
        action: ChatAction,
    ) -> Result<T, RpcError> {
        tokio::time::sleep(PACE).await;
        client
            .post(
                "/api/v1/chat",
                &ChatWire {
                    hello: Hello::default(),
                    version: CONTENT_PROTOCOL_VERSION,
                    action,
                },
            )
            .await
    }
    async fn chat_exchange(&self, peer: &str) -> Result<(), RpcError> {
        let c = self.content()?;
        let (mut client, p, h) = self.content_client(peer, "chat.olm.v1").await?;
        if !h.permissions.chat {
            return Err(err(
                "ACCESS_DENIED",
                "The remote owner has not enabled chat consent.",
            ));
        }
        let own = c.bundle(peer).await?;
        let remote: Bundle = self.chat_post(&mut client, ChatAction::Bundle {}).await?;
        {
            let _guard = self.0.files.gate().read_owned().await;
            self.0
                .store
                .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
                .await?;
            if let Some(envelope) = c
                .pending(
                    peer,
                    &remote,
                    p.permissions.attachment && h.permissions.attachment,
                )
                .await?
            {
                let receipt: Receipt = self
                    .chat_post(
                        &mut client,
                        ChatAction::Push {
                            bundle: own.clone(),
                            envelope,
                        },
                    )
                    .await?;
                c.acknowledge(peer, receipt).await?;
            }
        }
        let reply: PullReply = self
            .chat_post(&mut client, ChatAction::Pull { bundle: own })
            .await?;
        if let Some(envelope) = reply.envelope {
            let _guard = self.0.files.gate().read_owned().await;
            self.0
                .store
                .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
                .await?;
            let receipt = c
                .receive(peer, &reply.bundle, envelope, p.permissions.attachment)
                .await?;
            let _: Value = self
                .chat_post(&mut client, ChatAction::Ack { receipt })
                .await?;
        }
        Ok(())
    }
    pub(super) async fn kick_content_work(&self) -> Result<(), RpcError> {
        self.0.files.reconcile_pins().await?;
        if self.0.stop.is_cancelled() || !self.0.state.lock().await.config.enabled {
            return Ok(());
        }
        let mut clock = self.0.content_clock.lock().await;
        if self.0.content.is_some() && clock.0.elapsed() >= Duration::from_secs(3) {
            let mut task = self.0.chat_task.lock().await;
            if task.as_ref().is_none_or(|t| t.is_finished()) {
                if let Some(old) = task.take() {
                    let _ = old.await;
                }
                let peers = self
                    .0
                    .store
                    .peers()
                    .await?
                    .into_iter()
                    .filter(|p| p.state == "active" && p.endpoint.is_some() && p.permissions.chat)
                    .collect::<Vec<_>>();
                let mut selected = Vec::new();
                for i in 0..peers.len().min(2) {
                    selected.push(peers[(clock.1 + i) % peers.len()].id.clone());
                }
                clock.1 = clock.1.wrapping_add(2);
                let service = self.clone();
                *task = Some(tokio::spawn(async move {
                    for peer in selected {
                        if service.0.stop.is_cancelled() {
                            break;
                        }
                        if let Err(e) = service.chat_exchange(&peer).await {
                            if let Ok(c) = service.content() {
                                let _ = c.error(&peer, &e.code).await;
                            }
                        }
                    }
                }));
                clock.0 = Instant::now();
            }
        }
        if self.0.content.is_some() {
            let mut task = self.0.blob_task.lock().await;
            if task.as_ref().is_none_or(|t| t.is_finished()) {
                if let Some(old) = task.take() {
                    let _ = old.await;
                }
                let c = self.content()?;
                if let (Some((id, peer, missing)), Ok(lane)) = (
                    c.pending_blobs().await?.into_iter().next(),
                    self.0.outbound_slots.clone().try_acquire_owned(),
                ) {
                    let service = self.clone();
                    *task = Some(tokio::spawn(async move {
                        let _lane = lane;
                        if let Err(e) = service.fetch_blob(&id, &peer, missing).await {
                            let _ = c.blob_error(&id, &e.code).await;
                        }
                    }));
                }
            }
        }
        if clock.2.elapsed() >= Duration::from_secs(30) {
            let mut task = self.0.sync_task.lock().await;
            if task.as_ref().is_none_or(|t| t.is_finished()) {
                if let Some(old) = task.take() {
                    let _ = old.await;
                }
                let pins = self.0.files.pin_work().await?;
                let scopes = pins
                    .into_iter()
                    .filter(|p| p.state != "paused")
                    .map(|p| (p.peer_id, p.source.root_id))
                    .collect::<std::collections::BTreeSet<_>>()
                    .into_iter()
                    .collect::<Vec<_>>();
                if !scopes.is_empty() {
                    let selected = scopes[clock.3 % scopes.len()].clone();
                    clock.3 = clock.3.wrapping_add(1);
                    let service = self.clone();
                    *task = Some(tokio::spawn(async move {
                        let (peer, root) = selected;
                        if let Err(e) = service.sync_root(&peer, &root).await {
                            if let Ok(pins) = service.0.files.pin_work().await {
                                for p in pins {
                                    if p.peer_id == peer && p.source.root_id == root {
                                        let _ = service.0.files.pin_error(&p.pin_id, &e.code).await;
                                    }
                                }
                            }
                        }
                    }));
                }
                clock.2 = Instant::now();
            }
        }
        Ok(())
    }
    async fn fetch_blob(&self, id: &str, peer: &str, missing: Vec<u32>) -> Result<(), RpcError> {
        let c = self.content()?;
        let (mut client, p, h) = self.content_client(peer, "attachments.age.v1").await?;
        if !h.permissions.chat || !h.permissions.attachment {
            return Err(err(
                "ACCESS_DENIED",
                "The source owner withdrew attachment consent.",
            ));
        }
        for index in missing {
            if self.0.stop.is_cancelled() {
                return Err(err("BLOB_PAUSED", "Agent shutdown paused the attachment."));
            }
            tokio::time::sleep(PACE).await;
            let _guard = self.0.files.gate().read_owned().await;
            self.0
                .store
                .authorize(&p.id, p.revision, p.epoch, Capability::Chat, None)
                .await?;
            self.0
                .store
                .authorize(&p.id, p.revision, p.epoch, Capability::Attachment, None)
                .await?;
            let data = client.blob_chunk(id, index).await?;
            c.accept_chunk(id, index, data).await?;
        }
        let _guard = self.0.files.gate().read_owned().await;
        self.0
            .store
            .authorize(&p.id, p.revision, p.epoch, Capability::Attachment, None)
            .await?;
        c.finish_fetch(id).await
    }
    async fn sync_root(&self, peer: &str, root: &str) -> Result<(), RpcError> {
        let (mut client, p, h) = self.content_client(peer, "sync.v1").await?;
        let grants = h
            .permissions
            .roots
            .iter()
            .find(|g| g.root_id == root)
            .ok_or_else(|| err("ACCESS_DENIED", "The source root is no longer shared."))?;
        if ![Capability::Browse, Capability::Read, Capability::SyncRead]
            .iter()
            .all(|c| grants.capabilities.contains(c))
        {
            return Err(err("ACCESS_DENIED", "Sync read consent was withdrawn."));
        }
        let old = self.0.files.sync_cursor(peer, root).await?;
        let mut snapshot = old.is_none();
        let mut cursor = old;
        let mut rows = Vec::new();
        let mut events = Vec::new();
        let mut last_sequence = 0;
        for page_index in 0..400 {
            if page_index > 0 && page_index % 180 == 0 {
                client = self.content_client(peer, "sync.v1").await?.0;
            }
            if self.0.stop.is_cancelled() {
                return Err(err(
                    "SYNC_PAUSED",
                    "Sync stopped before committing its cursor.",
                ));
            }
            let _guard = self.0.files.gate().read_owned().await;
            self.0
                .store
                .authorize_session(&p.id, p.revision, p.epoch)
                .await?;
            tokio::time::sleep(PACE).await;
            let request = if snapshot {
                SyncRequest::Snapshot {
                    root_id: root.into(),
                    cursor: cursor.clone(),
                }
            } else {
                SyncRequest::Changes {
                    root_id: root.into(),
                    cursor: cursor
                        .clone()
                        .ok_or_else(|| err("CURSOR_EXPIRED", "No journal cursor is available."))?,
                }
            };
            let response: Result<SyncPage, RpcError> = client
                .post(
                    "/api/v1/sync",
                    &SyncWire {
                        hello: Hello::default(),
                        version: 1,
                        request,
                    },
                )
                .await;
            let page = match response {
                Err(e) if !snapshot && e.code == "CURSOR_EXPIRED" => {
                    snapshot = true;
                    cursor = None;
                    events.clear();
                    continue;
                }
                other => other?,
            };
            if page.root_id != root
                || page.mode != (if snapshot { "snapshot" } else { "changes" })
                || page.entries.len() > 16
                || page.events.len() > 16
            {
                return Err(err(
                    "INVALID_SYNC_PAGE",
                    "A remote metadata page violated the requested scope.",
                ));
            }
            if snapshot {
                rows.extend(page.entries);
                if rows.len() > 5000
                    || serde_json::to_vec(&rows)
                        .map_err(|_| RpcError::storage())?
                        .len()
                        > 2 * 1024 * 1024
                {
                    return Err(err(
                        "SYNC_SNAPSHOT_LIMIT",
                        "The remote snapshot exceeded the cache bound.",
                    ));
                }
            } else {
                for e in &page.events {
                    if e.sequence <= last_sequence {
                        return Err(err(
                            "INVALID_SYNC_PAGE",
                            "Journal sequence did not advance.",
                        ));
                    }
                    last_sequence = e.sequence;
                }
                events.extend(page.events);
                if events.len() > 2048 {
                    return Err(err(
                        "CURSOR_EXPIRED",
                        "The remote journal exceeds one bounded reconciliation.",
                    ));
                }
            }
            if let Some(next) = page.next_cursor {
                cursor = Some(next);
                continue;
            }
            let committed = page.changes_cursor.ok_or_else(|| {
                err(
                    "INVALID_SYNC_PAGE",
                    "A completed metadata fence needs a cursor.",
                )
            })?;
            drop(_guard);
            self.0
                .files
                .apply_sync(
                    peer,
                    root,
                    if snapshot { Some(rows) } else { None },
                    events,
                    &committed,
                )
                .await?;
            for pin in self.0.files.pin_work().await? {
                if pin.peer_id == peer
                    && pin.source.root_id == root
                    && pin.state == "refresh_needed"
                {
                    if let Err(e) = self.0.files.refresh_pin(&pin.pin_id).await {
                        self.0.files.pin_error(&pin.pin_id, &e.code).await?;
                    }
                }
            }
            return Ok(());
        }
        Err(err("SYNC_SNAPSHOT_LIMIT","Reconciliation exceeded the bounded page count; the previous cache/copy remains intact."))
    }
    pub(super) async fn stop_content_workers(&self) {
        for slot in [&self.0.chat_task, &self.0.blob_task, &self.0.sync_task] {
            if let Some(mut task) = slot.lock().await.take() {
                if tokio::time::timeout(Duration::from_secs(7), &mut task)
                    .await
                    .is_err()
                {
                    task.abort();
                    let _ = task.await;
                }
            }
        }
        if let Some(c) = &self.0.content {
            c.close().await;
        }
    }
}
