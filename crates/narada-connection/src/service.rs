mod attachment_upload;
mod content;
mod transfers;
use crate::{
    discovery::Discovery,
    endpoint,
    identity::{self, err, Identity},
    store::{Peer, Store},
    tls::{AllowedPin, Pins},
    transport::{self, Client},
    wire::{self, Claim, ClaimReply, Health, Hello, PairCode, PairState, Proof},
};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use chrono::Utc;
use hyper::{body::Incoming, Request as HttpRequest};
use narada_core::Core;
use narada_protocol::*;
use std::{
    collections::HashMap,
    net::{Ipv4Addr, SocketAddrV4},
    path::Path,
    sync::{Arc, Mutex as SyncMutex},
    time::{Duration, Instant},
};
use tokio::{
    net::TcpListener,
    sync::{Mutex, Semaphore},
};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;
#[derive(Clone)]
pub struct Service(Arc<Inner>);
struct Inner {
    core: Core,
    files: narada_files::Files,
    store: Store,
    identity: Option<Identity>,
    identity_error: Option<RpcError>,
    pins: Pins,
    state: Mutex<State>,
    owner: Mutex<()>,
    network: Mutex<Option<Work>>,
    join: Mutex<Option<Work>>,
    workers: Mutex<HashMap<String, Work>>,
    sessions: Arc<SyncMutex<HashMap<String, SessionRegistration>>>,
    slots: Arc<Semaphore>,
    discovery: Discovery,
    stop: CancellationToken,
    monitor: Mutex<Option<tokio::task::JoinHandle<()>>>,
    transfer_tasks: Mutex<HashMap<String, tokio::task::JoinHandle<()>>>,
    offer_poll: Mutex<Option<tokio::task::JoinHandle<()>>>,
    data_slots: Arc<Semaphore>,
    outbound_slots: Arc<Semaphore>,
    content: Option<crate::content::Content>,
    content_error: Option<RpcError>,
    chat_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    blob_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    sync_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    content_clock: Mutex<(Instant, usize, Instant, usize)>,
}
struct State {
    config: ConnectionConfig,
    endpoint: Option<SocketAddrV4>,
    error: Option<RpcError>,
    interfaces: Vec<InterfaceView>,
    invitation: Option<Invitation>,
    join: Option<JoinStatus>,
    runtime: HashMap<String, PeerRuntime>,
}
#[derive(Default, Clone)]
struct PeerRuntime {
    state: String,
    error: Option<RpcError>,
    retry_at: Option<Instant>,
    last_seen: Option<Instant>,
}
struct Invitation {
    id: String,
    hash: String,
    expires_at: String,
    deadline: Instant,
    pending: Option<Pending>,
    cancel: CancellationToken,
    task: tokio::task::JoinHandle<()>,
}
struct Pending {
    attempt: String,
    cert: Vec<u8>,
    id: String,
    fingerprint: String,
    name: String,
    platform: String,
    proof: bool,
    approved: Option<PeerPermissions>,
}
struct Work {
    cancel: CancellationToken,
    task: tokio::task::JoinHandle<()>,
}
#[derive(Clone)]
pub(crate) struct SessionContext {
    pub fingerprint: Option<String>,
    pub cancel: CancellationToken,
    lease: Arc<SyncMutex<Option<(String, u32, u32)>>>,
    budget: Arc<SyncMutex<(Instant, usize)>>,
}
struct SessionRegistration {
    fingerprint: Option<String>,
    cancel: CancellationToken,
}
pub(crate) struct SessionGuard {
    id: String,
    sessions: Arc<SyncMutex<HashMap<String, SessionRegistration>>>,
}
impl Drop for SessionGuard {
    fn drop(&mut self) {
        if let Ok(mut s) = self.sessions.lock() {
            s.remove(&self.id);
        }
    }
}
impl Service {
    pub async fn open(core: Core, dir: &Path) -> Result<Self, RpcError> {
        let files = narada_files::Files::open(core.clone(), dir).await?;
        let store = Store::new(core.clone());
        let marker = store.identity_marker().await?;
        let existing_identity =
            marker.is_some() || dir.join(crate::identity::IDENTITY_FILE).exists();
        let identity = Identity::open(dir, marker.as_ref().map(|(d, f)| (d.as_str(), f.as_str())));
        let (identity, identity_error) = match identity {
            Ok(i) => {
                store.bind_identity(&i).await?;
                (Some(i), None)
            }
            Err(e) => (None, Some(e)),
        };
        let (content, content_error) = if let Some(id) = &identity {
            let opened = async {
                let expected = store.content_identity_marker().await?;
                if expected.is_none()
                    && existing_identity
                    && !["content.key", "content.db", "content.guard"]
                        .iter()
                        .all(|name| dir.join(name).is_file())
                {
                    // An unmarked old profile cannot prove that its private
                    // store never existed. Prefer explicit enrollment to reset.
                    return Err(err(
                        "CONTENT_STORE_LOCKED",
                        "This existing workspace has no complete private store or enrollment record. Preserve it; use an explicitly new profile if recovery is impossible.",
                    ));
                }
                let content = match expected.as_deref() {
                    Some(fingerprint) => {
                        crate::content::Content::open_bound(
                            dir,
                            &id.device_id,
                            id.protection.clone(),
                            fingerprint,
                        )
                        .await?
                    }
                    None if existing_identity => {
                        crate::content::Content::open_existing(
                            dir,
                            &id.device_id,
                            id.protection.clone(),
                        )
                        .await?
                    }
                    None => {
                        crate::content::Content::open(dir, &id.device_id, id.protection.clone())
                            .await?
                    }
                };
                let binding = async {
                    let fingerprint = content.identity_fingerprint().await?;
                    store.bind_content_identity(&fingerprint).await
                }
                .await;
                if let Err(error) = binding {
                    content.close().await;
                    return Err(error);
                }
                Ok(content)
            }
            .await;
            match opened {
                Ok(c) => (Some(c), None),
                Err(e) => (None, Some(e)),
            }
        } else {
            (None, identity_error.clone())
        };
        let (config, error) = match store.config().await {
            Ok(c) => (c, None),
            Err(e) => (ConnectionConfig::default(), Some(e)),
        };
        let interfaces = endpoint::interfaces().await.unwrap_or_default();
        let service = Self(Arc::new(Inner {
            core,
            files,
            store,
            identity,
            identity_error,
            pins: Default::default(),
            state: Mutex::new(State {
                config: config.clone(),
                endpoint: None,
                error,
                interfaces,
                invitation: None,
                join: None,
                runtime: HashMap::new(),
            }),
            owner: Mutex::new(()),
            network: Mutex::new(None),
            join: Mutex::new(None),
            workers: Mutex::new(HashMap::new()),
            sessions: Default::default(),
            slots: Arc::new(Semaphore::new(24)),
            discovery: Discovery::default(),
            stop: CancellationToken::new(),
            monitor: Mutex::new(None),
            transfer_tasks: Mutex::new(HashMap::new()),
            offer_poll: Mutex::new(None),
            data_slots: Arc::new(Semaphore::new(2)),
            outbound_slots: Arc::new(Semaphore::new(2)),
            content,
            content_error,
            chat_task: Mutex::new(None),
            blob_task: Mutex::new(None),
            sync_task: Mutex::new(None),
            content_clock: Mutex::new((
                Instant::now() - Duration::from_secs(4),
                0,
                Instant::now() - Duration::from_secs(31),
                0,
            )),
        }));
        if config.enabled {
            let _owner = service.0.owner.lock().await;
            if let Err(e) = service.enable(config, None).await {
                service.0.state.lock().await.error = Some(e);
            }
        }
        let weak = Arc::downgrade(&service.0);
        let cancel = service.0.stop.clone();
        let monitor = tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_millis(500));
            let mut profiles = Instant::now();
            loop {
                tokio::select! {_=cancel.cancelled()=>break,_=tick.tick()=>{let Some(inner)=weak.upgrade()else{break};let service=Service(inner);service.expire_invitation().await;let _=service.kick_transfer_jobs().await;let _=service.kick_content_work().await;
                 if profiles.elapsed()>=Duration::from_secs(5){profiles=Instant::now();let cfg=service.0.state.lock().await.config.clone();
                  let result=endpoint::interfaces().await;
                  let permitted=match result{Ok(list)=>{let allowed=list.iter().any(|i|i.ip==cfg.bind_ip&&i.eligible);service.0.state.lock().await.interfaces=list;allowed},Err(_)=>false};
                  if cfg.enabled&&!cfg.bind_ip.starts_with("127.")&&!permitted{let _owner=service.0.owner.lock().await;service.stop_network().await;service.0.state.lock().await.error=Some(err("PUBLIC_NETWORK_BLOCKED","The selected interface is absent, no longer private, or its OS profile cannot be verified. Peer listeners have been closed. Review the network policy before re-enabling."));}
                 }
                }}
            }
        });
        *service.0.monitor.lock().await = Some(monitor);
        Ok(service)
    }
    pub(crate) fn identity(&self) -> Result<Identity, RpcError> {
        self.0.identity.clone().ok_or_else(|| {
            self.0.identity_error.clone().unwrap_or_else(|| {
                err(
                    "KEYSTORE_UNAVAILABLE",
                    "The protected transport identity is not ready.",
                )
            })
        })
    }
    pub(crate) fn pins(&self) -> Pins {
        self.0.pins.clone()
    }
    pub(crate) fn slots(&self) -> Arc<Semaphore> {
        self.0.slots.clone()
    }
    pub(crate) fn session(
        &self,
        fp: Option<String>,
        cancel: CancellationToken,
    ) -> (SessionContext, SessionGuard) {
        let id = Uuid::new_v4().to_string();
        if let Ok(mut s) = self.0.sessions.lock() {
            // Check the pin while holding the session registry lock. Revocation removes
            // the pin before taking this same lock, so a finishing TLS handshake cannot
            // slip into the live registry after the revocation sweep.
            if let Some(fp) = &fp {
                let permitted = self
                    .0
                    .pins
                    .read()
                    .map(|pins| {
                        pins.get(fp)
                            .is_some_and(|p| p.expires.is_none_or(|until| Instant::now() < until))
                    })
                    .unwrap_or(false);
                if !permitted {
                    cancel.cancel();
                }
            }
            s.insert(
                id.clone(),
                SessionRegistration {
                    fingerprint: fp.clone(),
                    cancel: cancel.clone(),
                },
            );
        } else {
            cancel.cancel();
        }
        (
            SessionContext {
                fingerprint: fp,
                cancel,
                lease: Default::default(),
                budget: Arc::new(SyncMutex::new((Instant::now(), 0))),
            },
            SessionGuard {
                id,
                sessions: self.0.sessions.clone(),
            },
        )
    }
    fn close_sessions(&self, fp: Option<&str>) {
        if let Ok(s) = self.0.sessions.lock() {
            for r in s.values() {
                if fp.is_none() || r.fingerprint.as_deref() == fp {
                    r.cancel.cancel();
                }
            }
        }
    }
    async fn refresh_pins(&self) -> Result<(), RpcError> {
        let mut pins = HashMap::new();
        for p in self
            .0
            .store
            .peers()
            .await?
            .into_iter()
            .filter(|p| p.state == "active")
        {
            if identity::validate_certificate(&p.cert)? != p.id
                || identity::fingerprint(&p.cert) != p.fingerprint
            {
                return Err(err("TRUST_STORE_INVALID","A saved peer identity does not match its certificate. Connections have been blocked."));
            }
            pins.insert(p.fingerprint, AllowedPin { expires: None });
        }
        if let Some(i) = &self.0.state.lock().await.invitation {
            if Instant::now() < i.deadline {
                if let Some(p) = &i.pending {
                    pins.insert(
                        p.fingerprint.clone(),
                        AllowedPin {
                            expires: Some(i.deadline),
                        },
                    );
                }
            }
        }
        *self.0.pins.write().map_err(|_| {
            err(
                "TRUST_STORE_INVALID",
                "The in-memory trust store is unavailable.",
            )
        })? = pins;
        Ok(())
    }
    async fn enable(
        &self,
        mut c: ConnectionConfig,
        verified: Option<Vec<InterfaceView>>,
    ) -> Result<(), RpcError> {
        self.identity()?;
        let interfaces = match verified {
            Some(v) => v,
            None => endpoint::validate_config(&c).await?,
        };
        self.refresh_pins().await?;
        let ip = c
            .bind_ip
            .parse::<Ipv4Addr>()
            .map_err(|_| err("INVALID_INTERFACE", "Select a local interface."))?;
        let listener=TcpListener::bind((ip,c.api_port)).await.map_err(|_|err("PORT_UNAVAILABLE","The selected port could not be bound. Choose an unused port; no broader fallback interface was opened."))?;
        let port = listener
            .local_addr()
            .map_err(|_| err("PORT_UNAVAILABLE", "The listener address is unavailable."))?
            .port();
        c.api_port = port;
        self.0.store.save_config(&c).await?;
        let cancel = self.0.stop.child_token();
        let child = cancel.clone();
        let service = self.clone();
        let failure_cancel = cancel.clone();
        let task = tokio::spawn(async move {
            if let Err(e) = transport::serve(service.clone(), listener, false, child).await {
                failure_cancel.cancel();
                {
                    let mut s = service.0.state.lock().await;
                    s.endpoint = None;
                    s.error = Some(e);
                }
                service.close_sessions(None);
                service.0.discovery.stop().await;
                service.0.discovery.failed().await;
            }
        });
        *self.0.network.lock().await = Some(Work { cancel, task });
        {
            let mut s = self.0.state.lock().await;
            s.config = c.clone();
            s.endpoint = Some(SocketAddrV4::new(ip, port));
            s.error = None;
            s.interfaces = interfaces;
        }
        if c.discovery && self.0.discovery.start(ip, port).await.is_err() {
            self.0.discovery.failed().await;
        }
        for p in self
            .0
            .store
            .peers()
            .await?
            .into_iter()
            .filter(|p| p.direction == "outbound" && p.state != "revoked")
        {
            self.start_worker(p).await;
        }
        Ok(())
    }
    async fn network_child(&self) -> Result<CancellationToken, RpcError> {
        self.0
            .network
            .lock()
            .await
            .as_ref()
            .filter(|w| !w.cancel.is_cancelled())
            .map(|w| w.cancel.child_token())
            .ok_or_else(|| {
                err(
                    "NETWORK_DISABLED",
                    "The selected network runtime is not active.",
                )
            })
    }
    async fn stop_network(&self) {
        let network = self.0.network.lock().await.take();
        if let Some(w) = &network {
            w.cancel.cancel();
        }
        self.cancel_invitation(None).await;
        if let Some(w) = self.0.join.lock().await.take() {
            w.cancel.cancel();
            let _ = w.task.await;
        }
        for (_, w) in self.0.workers.lock().await.drain() {
            w.cancel.cancel();
            let _ = w.task.await;
        }
        self.close_sessions(None);
        if let Some(w) = network {
            let _ = w.task.await;
        }
        self.0.discovery.stop().await;
        let mut s = self.0.state.lock().await;
        s.endpoint = None;
        for r in s.runtime.values_mut() {
            r.state = "offline".into();
            r.retry_at = None;
        }
    }
    /// Internal policy gate for later content handlers. The caller must bind these values to its authenticated TLS lease.
    pub async fn authorize_peer_operation(
        &self,
        id: &str,
        revision: u32,
        epoch: u32,
        capability: Capability,
        root: Option<&str>,
    ) -> Result<(), RpcError> {
        self.0
            .store
            .authorize(id, revision, epoch, capability, root)
            .await
    }
    pub async fn shutdown(&self) {
        self.0.stop.cancel();
        if let Some(m) = self.0.monitor.lock().await.take() {
            let _ = m.await;
        }
        self.stop_transfer_workers().await;
        self.stop_content_workers().await;
        let _owner = self.0.owner.lock().await;
        self.stop_network().await;
        self.0.files.shutdown().await;
    }
    pub async fn dispatch(&self, request: &Request) -> Response {
        if let Err(e) = request.validate() {
            return Response::result(request.id.clone(), Err(e));
        }
        let result = match &request.command {
            Command::GetSnapshot => self.snapshot().await.and_then(transport::value),
            Command::RunDiagnostics => {
                let c = self.connections().await;
                match c {
                    Ok(c) => {
                        let checks=vec![Check{id:"transport_identity".into(),label:"Transport identity".into(),status:if c.identity.state=="ready"{if cfg!(windows){"passed"}else{"warning"}}else{"failed"}.into(),detail:if c.identity.state=="ready"{c.identity.protection.clone()}else{"The protected identity is unavailable. No plaintext fallback or replacement was created.".into()}},Check{id:"network".into(),label:"Peer network policy".into(),status:if c.error.is_some(){"failed"}else{"passed"}.into(),detail:if c.listening{"Opt-in selected-interface TLS 1.3 listener. Saved pins, mutual certificate proof and current authorization epochs are checked; file and transfer operations additionally require active root grants. Remote uploads require receiver approval; chat/attachment routes require separate consent and protected E2EE state."}else{"No peer listener is active. Connections are disabled or blocked by a local policy/identity failure."}.into()},Check{id:"content_boundary".into(),label:"Content access boundary".into(),status:"passed".into(),detail:"File/transfer, Olm chat, age attachment and Sync read routes have separate consent and bounded state. Native/security qualification is open. Arbitrary paths and remote owner controls remain unavailable.".into()}];
                        self.0
                            .core
                            .run_diagnostics_with(checks)
                            .await
                            .and_then(transport::value)
                    }
                    Err(e) => Err(e),
                }
            }
            Command::FileQuery(q) => {
                let guard = self.0.files.gate().read_owned().await;
                self.0
                    .files
                    .query(q.clone(), narada_files::Access::Owner, guard)
                    .await
            }
            Command::FileMutation(m) => {
                let guard = self.0.files.gate().read_owned().await;
                self.0
                    .files
                    .mutate(m.clone(), narada_files::Access::Owner, guard)
                    .await
            }
            Command::RemoteFileQuery(q) => {
                self.remote_file(&q.peer_id, wire::FileWireAction::Query(q.query.clone()))
                    .await
            }
            Command::RemoteFileMutation(m) => {
                self.remote_file(&m.peer_id, wire::FileWireAction::Mutate(m.mutation.clone()))
                    .await
            }
            Command::ContentStatus
            | Command::ListConversations
            | Command::ChatHistory(_)
            | Command::SendChat(_)
            | Command::SaveChatDraft(_)
            | Command::MarkChatRead(_)
            | Command::ChatControl(_)
            | Command::FetchAttachment(_)
            | Command::PauseAttachment(_)
            | Command::ExportAttachment(_)
            | Command::CleanupContent
            | Command::SyncQuery(_)
            | Command::ListPins
            | Command::CreatePin(_)
            | Command::PinControl(_)
            | Command::RefreshSync
            | Command::SupportBundle => self.content_owner(&request.command).await,
            Command::ListTransfers => self.0.files.transfers_owner().await,
            Command::StartTransfer(input) => self.start_transfer(input.clone()).await,
            Command::TransferControl(input) => self.0.files.control_transfer(input.clone()).await,
            Command::CreateTransferOffer(input) => self.create_transfer_offer(input.clone()).await,
            Command::PollTransferOffers => self.poll_transfer_offers().await,
            Command::AcceptTransferOffer(input) => {
                self.0.files.accept_transfer_offer(input.clone()).await
            }
            Command::CleanupTransfers => self.0.files.cleanup_transfers().await,
            Command::SaveSettings(_) | Command::ClearActivity | Command::Shutdown => {
                return self.0.core.dispatch(request).await
            }
            command => {
                let _owner = self.0.owner.lock().await;
                self.owner_command(command).await
            }
        };
        Response::result(request.id.clone(), result)
    }
    async fn owner_command(&self, command: &Command) -> Result<serde_json::Value, RpcError> {
        match command {
            Command::ConfigureConnections(c) => {
                let _policy = self.0.files.gate().write_owned().await;
                let _: Ipv4Addr = c
                    .bind_ip
                    .parse()
                    .map_err(|_| err("INVALID_INTERFACE", "An IPv4 interface is required."))?;
                let interfaces = if c.enabled {
                    self.identity()?;
                    Some(endpoint::validate_config(c).await?)
                } else {
                    None
                };
                self.stop_network().await;
                let mut disabled = c.clone();
                disabled.enabled = false;
                self.0.store.save_config(&disabled).await?;
                {
                    let mut s = self.0.state.lock().await;
                    s.config = disabled;
                    s.error = None;
                }
                if c.enabled {
                    if let Err(e) = self.enable(c.clone(), interfaces).await {
                        self.0.state.lock().await.error = Some(e.clone());
                        return Err(e);
                    }
                }
                self.0
                    .core
                    .record(
                        "connections",
                        "Connection policy updated",
                        if c.enabled {
                            "The owner enabled protected peer connections on a selected interface."
                        } else {
                            "The owner disabled peer connections and closed active sessions."
                        },
                    )
                    .await?;
                transport::value(self.connections().await?)
            }
            Command::StartPairing => self.start_invitation().await,
            Command::CancelPairing(r) => {
                wire::valid_uuid(&r.invitation_id)?;
                self.cancel_invitation(Some(&r.invitation_id)).await;
                Ok(serde_json::json!({"cancelled":true}))
            }
            Command::ApprovePairing(r) => {
                self.0.store.validate_grants(&r.permissions).await?;
                let mut s = self.0.state.lock().await;
                let inv = s.invitation.as_mut().ok_or_else(|| {
                    err("INVITATION_EXPIRED", "The invitation is no longer active.")
                })?;
                wire::live(inv.deadline)?;
                let pending = inv
                    .pending
                    .as_mut()
                    .filter(|p| p.attempt == r.attempt_id)
                    .ok_or_else(|| {
                        err(
                            "PAIRING_NOT_FOUND",
                            "This pending pairing attempt is no longer current.",
                        )
                    })?;
                if !pending.proof {
                    return Err(err("PROOF_REQUIRED","The peer must prove possession of its private key over mutual TLS before approval."));
                }
                pending.approved = Some(r.permissions.clone());
                Ok(serde_json::json!({"approved":true,"active":false}))
            }
            Command::RejectPairing(r) => {
                let id = {
                    let s = self.0.state.lock().await;
                    s.invitation
                        .as_ref()
                        .filter(|i| {
                            i.pending
                                .as_ref()
                                .is_some_and(|p| p.attempt == r.attempt_id)
                        })
                        .map(|i| i.id.clone())
                }
                .ok_or_else(|| {
                    err(
                        "PAIRING_NOT_FOUND",
                        "The pairing attempt is no longer current.",
                    )
                })?;
                self.cancel_invitation(Some(&id)).await;
                Ok(serde_json::json!({"rejected":true}))
            }
            Command::JoinPairing(r) => self.begin_join(r).await,
            Command::CancelJoin(r) => {
                let _policy = self.0.files.gate().write_owned().await;
                wire::valid_uuid(&r.attempt_id)?;
                let current = self
                    .0
                    .state
                    .lock()
                    .await
                    .join
                    .clone()
                    .filter(|j| j.attempt_id == r.attempt_id)
                    .ok_or_else(|| {
                        err(
                            "PAIRING_NOT_FOUND",
                            "The joining attempt is no longer current.",
                        )
                    })?;
                if current.state == "paired" {
                    return Err(err(
                        "PAIRING_FINISHED",
                        "The peer is already paired. Use Revoke to remove trust.",
                    ));
                }
                self.0.store.cancel_attempt(&r.attempt_id).await?;
                if let Some(w) = self.0.join.lock().await.take() {
                    w.cancel.cancel();
                    let _ = w.task.await;
                }
                for p in self.0.store.peers().await?.into_iter().filter(|p| {
                    p.attempt_id == r.attempt_id
                        && p.direction == "outbound"
                        && p.state != "revoked"
                }) {
                    self.0.store.revoke(&p.id, p.revision).await?;
                    self.close_sessions(Some(&p.fingerprint));
                }
                self.refresh_pins().await?;
                self.set_join(&r.attempt_id, "cancelled", None).await;
                Ok(serde_json::json!({"cancelled":true}))
            }
            Command::UpdatePeerPermissions(r) => {
                let _policy = self.0.files.gate().write_owned().await;
                self.0
                    .store
                    .update_permissions(&r.peer_id, r.expected_revision, &r.permissions)
                    .await?;
                let p = self.0.store.peer(&r.peer_id).await?;
                self.close_sessions(Some(&p.fingerprint));
                self.stop_worker(&p.id).await;
                if p.direction == "outbound" && self.0.state.lock().await.endpoint.is_some() {
                    self.start_worker(p).await;
                }
                self.0.core.record("permissions","Device permissions updated","The owner changed scoped permissions. Existing authorization epochs were invalidated.").await?;
                Ok(serde_json::json!({"updated":true}))
            }
            Command::RevokePeer(r) => {
                let _policy = self.0.files.gate().write_owned().await;
                self.0.store.revoke(&r.peer_id, r.expected_revision).await?;
                let invitation = {
                    let s = self.0.state.lock().await;
                    s.invitation
                        .as_ref()
                        .filter(|i| i.pending.as_ref().is_some_and(|p| p.id == r.peer_id))
                        .map(|i| i.id.clone())
                };
                if let Some(id) = invitation {
                    self.cancel_invitation(Some(&id)).await;
                }
                let p = self.0.store.peer(&r.peer_id).await?;
                self.refresh_pins().await?;
                self.close_sessions(Some(&p.fingerprint));
                self.stop_worker(&p.id).await;
                self.set_runtime(&p.id, "revoked", None, None).await;
                self.0.core.record("revocation","Device trust revoked","Revocation was committed before sessions were closed. Re-pairing requires a new invitation and owner approval.").await?;
                Ok(serde_json::json!({"revoked":true}))
            }
            Command::ReconnectPeer(r) => {
                let p = self.0.store.peer(&r.peer_id).await?;
                if p.state == "revoked" {
                    return Err(err(
                        "DEVICE_REVOKED",
                        "A revoked device requires a new approved pairing.",
                    ));
                }
                if p.direction != "outbound" {
                    return Err(err("PEER_INITIATES","This peer initiates its connection. Open its agent or re-pair; the owner will not probe unknown addresses."));
                }
                if self.0.state.lock().await.endpoint.is_none() {
                    return Err(err(
                        "NETWORK_DISABLED",
                        "Enable connections before reconnecting.",
                    ));
                }
                self.stop_worker(&p.id).await;
                self.start_worker(p).await;
                Ok(serde_json::json!({"reconnecting":true}))
            }
            Command::SetPeerEndpoint(r) => {
                let ep = endpoint::endpoint(&r.endpoint)?;
                let p = self.0.store.peer(&r.peer_id).await?;
                if p.state != "active" || p.direction != "outbound" {
                    return Err(err(
                        "INVALID_PEER_STATE",
                        "Manual endpoints apply to active outbound peers only.",
                    ));
                }
                self.stop_worker(&p.id).await;
                self.0.store.endpoint(&p.id, &ep.to_string()).await?;
                if self.0.state.lock().await.endpoint.is_some() {
                    self.start_worker(self.0.store.peer(&p.id).await?).await;
                }
                Ok(serde_json::json!({"updated":true,"pin_unchanged":true}))
            }
            Command::ListRoots => self.0.files.list_owner().await,
            Command::RegisterRoot(r) => self.0.files.register(r.clone()).await,
            Command::UpdateRootPolicy(r) => {
                let result = self.0.files.policy(r.clone()).await;
                self.close_sessions(None);
                result
            }
            Command::RemoveRoot(r) => {
                let result = self.0.files.remove(r.clone()).await;
                self.close_sessions(None);
                result
            }
            Command::ReindexRoot(r) => self.0.files.reindex(r.root_id.clone()).await,
            Command::DiscoverPeers => {
                let s = self.0.state.lock().await;
                if s.endpoint.is_none() {
                    return Err(err(
                        "NETWORK_DISABLED",
                        "Enable connections before discovering peers.",
                    ));
                }
                if !s.config.discovery {
                    return Err(err("DISCOVERY_DISABLED","Discovery is off. Enable it on an eligible LAN interface or use manual pairing."));
                }
                drop(s);
                transport::value(self.0.discovery.snapshot().await)
            }
            _ => Err(err(
                "COMMAND_UNAVAILABLE",
                "This owner command is unsupported.",
            )),
        }
    }
    pub async fn snapshot(&self) -> Result<Snapshot, RpcError> {
        let mut snap = self.0.core.snapshot().await?;
        let connections = self.connections().await?;
        snap.agent.peer_network_enabled = connections.listening;
        snap.paired_devices = connections
            .peers
            .iter()
            .filter(|p| p.state == "active")
            .count() as u32;
        snap.shared_roots = self.0.files.count().await?;
        snap.active_transfers = self.0.files.active_transfer_count().await?;
        snap.connections = Some(connections);
        bound_snapshot(snap)
    }
    async fn connections(&self) -> Result<ConnectionsSnapshot, RpcError> {
        let peers = self.0.store.peers().await?;
        let discovery = self.0.discovery.snapshot().await;
        let s = self.0.state.lock().await;
        let identity = if let Some(i) = &self.0.identity {
            i.view()
        } else {
            IdentityView {
                state: "blocked".into(),
                protection: if cfg!(windows) {
                    "Windows DPAPI · current user"
                } else {
                    "Unavailable · no plaintext fallback"
                }
                .into(),
                device_id: None,
                fingerprint: None,
                error: self.0.identity_error.clone(),
            }
        };
        let live_fingerprints: Vec<String> = self
            .0
            .sessions
            .lock()
            .map(|sessions| {
                sessions
                    .values()
                    .filter(|r| !r.cancel.is_cancelled())
                    .filter_map(|r| r.fingerprint.clone())
                    .collect()
            })
            .unwrap_or_default();
        let peers = peers
            .into_iter()
            .map(|p| {
                let r = s.runtime.get(&p.id);
                let online = (p.direction == "outbound"
                    || live_fingerprints.contains(&p.fingerprint))
                    && r.is_some_and(|r| {
                        r.state == "online"
                            && r.last_seen
                                .is_some_and(|t| t.elapsed() < Duration::from_secs(12))
                    })
                    && s.endpoint.is_some()
                    && p.state == "active";
                PeerView {
                    peer_id: p.id,
                    name: p.name,
                    platform: p.platform,
                    fingerprint: p.fingerprint,
                    state: p.state.clone(),
                    direction: p.direction,
                    endpoint: p.endpoint,
                    permissions: p.permissions,
                    remote_permissions: p.remote_permissions,
                    grant_revision: p.revision,
                    session_epoch: p.epoch,
                    paired_at: p.paired_at,
                    last_seen: p.last_seen,
                    connection_state: if p.state == "revoked" {
                        "revoked".into()
                    } else if online {
                        "online".into()
                    } else {
                        r.map(|r| {
                            if r.state == "online" {
                                "offline".into()
                            } else {
                                r.state.clone()
                            }
                        })
                        .unwrap_or_else(|| "offline".into())
                    },
                    last_error: r.and_then(|r| r.error.clone()),
                    retry_in_ms: r.and_then(|r| {
                        r.retry_at
                            .map(|t| t.saturating_duration_since(Instant::now()).as_millis() as u64)
                    }),
                }
            })
            .collect();
        let invitation = s
            .invitation
            .as_ref()
            .filter(|i| Instant::now() < i.deadline)
            .map(|i| InvitationStatus {
                invitation_id: i.id.clone(),
                expires_at: i.expires_at.clone(),
                state: if i.pending.is_some() {
                    "claimed"
                } else {
                    "waiting"
                }
                .into(),
                pending: i.pending.as_ref().map(|p| PendingView {
                    attempt_id: p.attempt.clone(),
                    name: p.name.clone(),
                    platform: p.platform.clone(),
                    fingerprint: p.fingerprint.clone(),
                    proof_verified: p.proof,
                    approved: p.approved.is_some(),
                }),
            });
        Ok(ConnectionsSnapshot {
            view_notice: None,
            identity,
            config: s.config.clone(),
            listening: s.endpoint.is_some(),
            endpoint: s.endpoint.map(|a| a.to_string()),
            error: s.error.clone(),
            interfaces: s.interfaces.clone(),
            peers,
            invitation,
            join: s.join.clone(),
            discovery,
            active_sessions: self
                .0
                .sessions
                .lock()
                .map(|s| {
                    s.values()
                        .filter(|r| r.fingerprint.is_some() && !r.cancel.is_cancelled())
                        .count()
                })
                .unwrap_or(0),
        })
    }
    async fn start_invitation(&self) -> Result<serde_json::Value, RpcError> {
        self.expire_invitation().await;
        let identity = self.identity()?;
        let (ip, api_port) = {
            let s = self.0.state.lock().await;
            if s.invitation.is_some() {
                return Err(err(
                    "INVITATION_ACTIVE",
                    "Cancel the current invitation before generating a replacement.",
                ));
            }
            let ep = s.endpoint.ok_or_else(|| {
                err(
                    "NETWORK_DISABLED",
                    "Enable protected connections before creating an invitation.",
                )
            })?;
            (*ep.ip(), ep.port())
        };
        let _ = api_port;
        let listener = TcpListener::bind((ip, 0)).await.map_err(|_| {
            err(
                "PORT_UNAVAILABLE",
                "The short-lived bootstrap listener could not be opened.",
            )
        })?;
        let port = listener
            .local_addr()
            .map_err(|_| err("PORT_UNAVAILABLE", "The bootstrap port is unavailable."))?
            .port();
        let token = hex::encode(identity::random::<32>()?);
        let id = Uuid::new_v4().to_string();
        let expiry =
            (Utc::now() + chrono::Duration::seconds(INVITATION_TTL_SECONDS as i64)).to_rfc3339();
        let code = PairCode {
            format: "narada-pairing".into(),
            version: PEER_PROTOCOL_VERSION,
            invitation_id: id.clone(),
            token,
            endpoint: format!("{ip}:{port}"),
            fingerprint: identity.fingerprint,
            device_id: identity.device_id,
            name: self.0.core.settings().await?.device_name,
            expires_at: expiry.clone(),
        };
        let cancel = self.network_child().await?;
        let child = cancel.clone();
        let service = self.clone();
        let task = tokio::spawn(async move {
            let _ = transport::serve(service, listener, true, child).await;
        });
        self.0.state.lock().await.invitation = Some(Invitation {
            id: id.clone(),
            hash: identity::fingerprint(code.token.as_bytes()),
            expires_at: expiry.clone(),
            deadline: Instant::now() + Duration::from_secs(INVITATION_TTL_SECONDS),
            pending: None,
            cancel,
            task,
        });
        Ok(
            serde_json::json!({"invitation_id":id,"code":serde_json::to_string(&code).map_err(|_|RpcError::storage())?,"expires_at":expiry}),
        )
    }
    async fn expire_invitation(&self) {
        let expired = {
            let s = self.0.state.lock().await;
            s.invitation
                .as_ref()
                .filter(|i| Instant::now() >= i.deadline)
                .map(|i| i.id.clone())
        };
        if let Some(id) = expired {
            self.cancel_invitation(Some(&id)).await;
        }
    }
    async fn cancel_invitation(&self, id: Option<&str>) {
        let inv = {
            let mut s = self.0.state.lock().await;
            if id.is_none()
                || s.invitation
                    .as_ref()
                    .is_some_and(|i| Some(i.id.as_str()) == id)
            {
                s.invitation.take()
            } else {
                None
            }
        };
        if let Some(i) = inv {
            i.cancel.cancel();
            i.task.abort();
            let _ = i.task.await;
            if let Some(p) = i.pending {
                self.close_sessions(Some(&p.fingerprint));
            }
            let _ = self.refresh_pins().await;
        }
    }
    pub(crate) async fn route(
        &self,
        request: HttpRequest<Incoming>,
        bootstrap: bool,
        context: &SessionContext,
    ) -> Result<serde_json::Value, RpcError> {
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
                    "The per-session request limit was reached.",
                ));
            }
            budget.1 += 1;
        }
        if context.cancel.is_cancelled() {
            return Err(err(
                "SESSION_INVALIDATED",
                "This connection has been closed by local policy.",
            ));
        }
        if request.method() != hyper::Method::POST
            || request.uri().query().is_some()
            || request.uri().scheme().is_some()
        {
            return Err(err(
                "NO_PEER_ROUTE",
                "This method or route is not part of the peer protocol.",
            ));
        }
        let path = request.uri().path().to_owned();
        if bootstrap {
            if path != wire::PAIR_PATH {
                return Err(err(
                    "NO_PEER_ROUTE",
                    "The bootstrap listener only accepts a one-time pairing claim.",
                ));
            }
            let claim: Claim = transport::body(request).await?;
            return self.claim(claim).await.and_then(transport::value);
        }
        let fp = context
            .fingerprint
            .as_deref()
            .ok_or_else(|| err("CERTIFICATE_REQUIRED", "Mutual TLS is required."))?;
        if path == "/api/v1/chat" || path == "/api/v1/sync" {
            return self.route_content(&path, request, fp, context).await;
        }
        if path == "/api/v1/attachment-upload" {
            return self.route_attachment_upload(request, fp, context).await;
        }
        if path == "/api/v1/transfers" {
            let message: wire::TransferWire = transport::body(request).await?;
            message.hello.validate()?;
            if message.version != TRANSFER_PROTOCOL_VERSION {
                return Err(err(
                    "PROTOCOL_INCOMPATIBLE",
                    "Unsupported transfer control version.",
                ));
            }
            let guard = self.0.files.gate().read_owned().await;
            if context.cancel.is_cancelled() {
                return Err(err(
                    "SESSION_INVALIDATED",
                    "This transfer session was invalidated.",
                ));
            }
            let p = self.file_peer(fp, context).await?;
            return self
                .0
                .files
                .transfer_action(
                    message.action,
                    narada_files::Access::Peer {
                        id: p.id,
                        grants: p.permissions,
                    },
                    guard,
                )
                .await;
        }
        if path == "/api/v1/files" {
            let message: wire::FileWire = transport::body(request).await?;
            message.hello.validate()?;
            if message.version != FILE_PROTOCOL_VERSION {
                return Err(err(
                    "PROTOCOL_INCOMPATIBLE",
                    "The file protocol version is unsupported. No downgrade was attempted.",
                ));
            }
            let guard = self.0.files.gate().read_owned().await;
            if context.cancel.is_cancelled() {
                return Err(err(
                    "SESSION_INVALIDATED",
                    "This session was invalidated by owner policy.",
                ));
            }
            let p = self.file_peer(fp, context).await?;
            let root = match &message.action {
                wire::FileWireAction::Query(q) => q.root(),
                wire::FileWireAction::Mutate(m) => Some(m.root_id.as_str()),
            };
            if let Some(root) = root {
                self.0
                    .store
                    .authorize(&p.id, p.revision, p.epoch, Capability::Browse, Some(root))
                    .await?;
            }
            let access = narada_files::Access::Peer {
                id: p.id,
                grants: p.permissions,
            };
            return match message.action {
                wire::FileWireAction::Query(q) => self.0.files.query(q, access, guard).await,
                wire::FileWireAction::Mutate(m) => self.0.files.mutate(m, access, guard).await,
            };
        }
        match path.as_str(){
   "/pair/v1/prove"|"/pair/v1/ack"=>{let proof:Proof=transport::body(request).await?;self.prove(fp,proof,path.ends_with("/ack")).await.and_then(transport::value)},
   "/api/v1/hello"|"/api/v1/health"|"/api/v1/capabilities"=>{
    let hello:Hello=transport::body(request).await?;hello.validate()?;
    let lease=context.lease.lock().map_err(|_|err("SESSION_INVALIDATED","The session lease is unavailable."))?.clone();
    let lease=if let Some(l)=lease{l}else{let p=self.0.store.by_fingerprint(fp).await?.filter(|p|p.state=="active").ok_or_else(||err("PAIRING_NOT_ACTIVE","Owner approval and the final mutual-TLS acknowledgement are required."))?;let lease=(p.id,p.revision,p.epoch);*context.lease.lock().map_err(|_|err("SESSION_INVALIDATED","The session lease is unavailable."))?=Some(lease.clone());lease};
    let p=self.0.store.authorize_session(&lease.0,lease.1,lease.2).await?;
    self.mark_online(&p.id).await?;transport::value(Health{hello:Hello::default(),device_id:self.identity()?.device_id,permissions:p.permissions,grant_revision:p.revision,session_epoch:p.epoch,operations:vec!["health".into(),"permissions".into(),"files.v1".into(),"transfers.v1".into(),"chat.olm.v1".into(),"attachments.age.v1".into(),"sync.v1".into()]})
   },
   _=>Err(err("NO_PEER_ROUTE","This is not an implemented peer route. Owner controls and unrestricted filesystem access are never forwarded."))
  }
    }
    async fn file_peer(&self, fp: &str, context: &SessionContext) -> Result<Peer, RpcError> {
        let existing = context
            .lease
            .lock()
            .map_err(|_| err("SESSION_INVALIDATED", "The session lease is unavailable."))?
            .clone();
        let lease =
            if let Some(lease) = existing {
                lease
            } else {
                let p = self
                    .0
                    .store
                    .by_fingerprint(fp)
                    .await?
                    .filter(|p| p.state == "active")
                    .ok_or_else(|| {
                        err(
                            "PAIRING_NOT_ACTIVE",
                            "Only an activated paired identity may access file metadata.",
                        )
                    })?;
                let lease = (p.id, p.revision, p.epoch);
                *context.lease.lock().map_err(|_| {
                    err("SESSION_INVALIDATED", "The session lease is unavailable.")
                })? = Some(lease.clone());
                lease
            };
        self.0
            .store
            .authorize_session(&lease.0, lease.1, lease.2)
            .await
    }
    async fn remote_file(
        &self,
        peer_id: &str,
        action: wire::FileWireAction,
    ) -> Result<serde_json::Value, RpcError> {
        let _policy = self.0.files.gate().read_owned().await;
        let p = self.0.store.peer(peer_id).await?;
        if p.state != "active" {
            return Err(err(
                "DEVICE_REVOKED",
                "Only an active paired device may be queried.",
            ));
        }
        let endpoint=p.endpoint.as_deref().ok_or_else(||err("PEER_INITIATES","This device connects to this desktop. Its owner has not supplied a pinned outbound endpoint."))?;
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
        let message = wire::FileWire {
            hello: Hello::default(),
            version: FILE_PROTOCOL_VERSION,
            action,
        };
        client.post("/api/v1/files", &message).await
    }
    async fn claim(&self, claim: Claim) -> Result<ClaimReply, RpcError> {
        claim.hello.validate()?;
        wire::valid_uuid(&claim.invitation_id)?;
        wire::valid_uuid(&claim.attempt_id)?;
        wire::validate_name(&claim.name)?;
        wire::validate_platform(&claim.platform)?;
        if claim.certificate.len() > 5500 {
            return Err(err(
                "INVALID_CERTIFICATE",
                "The submitted certificate is too large.",
            ));
        }
        let cert = B64.decode(&claim.certificate).map_err(|_| {
            err(
                "INVALID_CERTIFICATE",
                "The submitted certificate is invalid.",
            )
        })?;
        let id = identity::validate_certificate(&cert)?;
        let fp = identity::fingerprint(&cert);
        let local = self.identity()?;
        if id == local.device_id {
            return Err(err("SELF_PAIRING", "An identity cannot pair with itself."));
        }
        let peers = self.0.store.peers().await?;
        if peers.iter().any(|p| p.id == id && p.state != "revoked") {
            return Err(err("ALREADY_PAIRED","This identity already has trust or an unfinished durable pairing. Revoke it before creating a new pairing."));
        }
        if peers.iter().filter(|p| p.state != "revoked").count() >= MAX_PEERS {
            return Err(err(
                "PEER_LIMIT",
                "The owner must remove a device before pairing another.",
            ));
        }
        let mut s = self.0.state.lock().await;
        let api_port = s
            .endpoint
            .ok_or_else(|| err("NETWORK_DISABLED", "The peer listener is no longer active."))?
            .port();
        let inv = s
            .invitation
            .as_mut()
            .filter(|i| i.id == claim.invitation_id)
            .ok_or_else(|| {
                err(
                    "INVITATION_INVALID",
                    "The invitation is unknown or no longer active.",
                )
            })?;
        wire::live(inv.deadline)?;
        if !wire::token_matches(&inv.hash, &claim.token) {
            return Err(err(
                "INVITATION_INVALID",
                "The one-time invitation secret is invalid.",
            ));
        }
        if inv.pending.is_some() {
            return Err(err("INVITATION_USED","This invitation has already been claimed. Replaying it cannot create a new pairing."));
        }
        self.0
            .pins
            .write()
            .map_err(|_| {
                err(
                    "TRUST_STORE_INVALID",
                    "The pending pin could not be installed.",
                )
            })?
            .insert(
                fp.clone(),
                AllowedPin {
                    expires: Some(inv.deadline),
                },
            );
        inv.pending = Some(Pending {
            attempt: claim.attempt_id.clone(),
            cert,
            id,
            fingerprint: fp,
            name: claim.name.clone(),
            platform: claim.platform.clone(),
            proof: false,
            approved: None,
        });
        drop(s);
        Ok(ClaimReply {
            hello: Hello::default(),
            attempt_id: claim.attempt_id.clone(),
            api_port,
            name: self.0.core.settings().await?.device_name,
            platform: std::env::consts::OS.into(),
            certificate: B64.encode(local.certificate),
        })
    }
    async fn prove(&self, fp: &str, proof: Proof, ack: bool) -> Result<PairState, RpcError> {
        proof.hello.validate()?;
        wire::valid_uuid(&proof.invitation_id)?;
        wire::valid_uuid(&proof.attempt_id)?;
        let _owner = if ack {
            Some(self.0.owner.lock().await)
        } else {
            None
        };
        // Idempotent final acknowledgement recovers a reply lost after the durable host commit.
        if let Some(p) = self.0.store.by_fingerprint(fp).await? {
            if p.state == "active"
                && p.direction == "inbound"
                && p.attempt_id == proof.attempt_id
                && p.invitation_id == proof.invitation_id
            {
                return Ok(PairState {
                    hello: Hello::default(),
                    state: "active".into(),
                    permissions: p.permissions,
                });
            }
        }
        let mut s = self.0.state.lock().await;
        let inv = s
            .invitation
            .as_mut()
            .filter(|i| i.id == proof.invitation_id)
            .ok_or_else(|| {
                err(
                    "INVITATION_EXPIRED",
                    "The pairing invitation is no longer active.",
                )
            })?;
        wire::live(inv.deadline)?;
        let pending = inv
            .pending
            .as_mut()
            .filter(|p| p.fingerprint == fp && p.attempt == proof.attempt_id)
            .ok_or_else(|| {
                err(
                    "PAIRING_IDENTITY_MISMATCH",
                    "This mutual-TLS identity does not own the pairing attempt.",
                )
            })?;
        if !ack {
            pending.proof = true;
            return Ok(PairState {
                hello: Hello::default(),
                state: if pending.approved.is_some() {
                    "approved"
                } else {
                    "awaiting_owner"
                }
                .into(),
                permissions: pending.approved.clone().unwrap_or_default(),
            });
        }
        if !pending.proof {
            return Err(err(
                "PROOF_REQUIRED",
                "The private-key proof step is incomplete.",
            ));
        }
        let permissions = pending.approved.clone().ok_or_else(|| {
            err(
                "OWNER_APPROVAL_REQUIRED",
                "Explicit local owner approval is required before activation.",
            )
        })?;
        let p = Peer {
            id: pending.id.clone(),
            name: pending.name.clone(),
            platform: pending.platform.clone(),
            cert: pending.cert.clone(),
            fingerprint: pending.fingerprint.clone(),
            state: "active".into(),
            direction: "inbound".into(),
            endpoint: None,
            permissions: permissions.clone(),
            remote_permissions: PeerPermissions::default(),
            revision: 1,
            epoch: 1,
            attempt_id: pending.attempt.clone(),
            invitation_id: inv.id.clone(),
            paired_at: Utc::now().to_rfc3339(),
            last_seen: None,
        };
        self.0.store.put_peer(&p).await?;
        self.0
            .pins
            .write()
            .map_err(|_| {
                err(
                    "TRUST_STORE_INVALID",
                    "The durable pin could not be installed.",
                )
            })?
            .insert(fp.into(), AllowedPin { expires: None });
        if let Some(inv) = s.invitation.take() {
            inv.cancel.cancel();
            inv.task.abort();
        }
        drop(s);
        let _=self.0.core.record("pairing","Device trust established","An authenticated private-key proof, local owner approval and final acknowledgement completed. No content permissions were inferred.").await;
        Ok(PairState {
            hello: Hello::default(),
            state: "active".into(),
            permissions,
        })
    }
    async fn begin_join(&self, request: &JoinPairing) -> Result<serde_json::Value, RpcError> {
        let local = self.identity()?;
        let code = PairCode::parse(&request.code)?;
        {
            let s = self.0.state.lock().await;
            if s.endpoint.is_none() {
                return Err(err(
                    "NETWORK_DISABLED",
                    "Enable connections before joining another desktop agent.",
                ));
            }
            if s.join.as_ref().is_some_and(|j| {
                matches!(
                    j.state.as_str(),
                    "connecting" | "proving" | "awaiting_approval" | "finalizing"
                )
            }) {
                return Err(err(
                    "JOIN_IN_PROGRESS",
                    "Cancel the current joining attempt first.",
                ));
            }
        }
        if code.device_id == local.device_id {
            return Err(err(
                "SELF_PAIRING",
                "This code belongs to this workspace. Open a separate desktop or reference agent.",
            ));
        }
        if self
            .0
            .store
            .peers()
            .await?
            .iter()
            .any(|p| p.id == code.device_id && p.state != "revoked")
        {
            return Err(err("ALREADY_PAIRED","This pinned device already has local trust or an unfinished pairing. Reconnect it, or explicitly revoke it before re-pairing."));
        }
        let address = endpoint::endpoint(
            request
                .endpoint_override
                .as_deref()
                .unwrap_or(&code.endpoint),
        )?;
        self.check_route(address).await?;
        if let Some(w) = self.0.join.lock().await.take() {
            w.cancel.cancel();
            let _ = w.task.await;
        }
        let attempt = Uuid::new_v4().to_string();
        self.0.state.lock().await.join = Some(JoinStatus {
            attempt_id: attempt.clone(),
            state: "connecting".into(),
            host_fingerprint: code.fingerprint.clone(),
            local_fingerprint: local.fingerprint,
            error: None,
        });
        let service = self.clone();
        let child_attempt = attempt.clone();
        let cancel = self.network_child().await?;
        let child = cancel.clone();
        let ttl = chrono::DateTime::parse_from_rfc3339(&code.expires_at)
            .map_err(|_| err("INVALID_PAIRING_CODE", "The invitation expiry is invalid."))?
            .signed_duration_since(Utc::now())
            .num_milliseconds()
            .clamp(1, 120_000) as u64;
        let task = tokio::spawn(async move {
            let result = tokio::select! {_=child.cancelled()=>Err(err("PAIRING_CANCELLED","The local owner stopped this joining attempt.")),r=tokio::time::timeout(Duration::from_millis(ttl),service.join_flow(code,address,&child_attempt))=>r.unwrap_or_else(|_|Err(err("INVITATION_EXPIRED","The pairing deadline elapsed. Both devices must use a new invitation.")))};
            if let Err(e) = result {
                service
                    .set_join(
                        &child_attempt,
                        if e.code == "PAIRING_CANCELLED" {
                            "cancelled"
                        } else {
                            "failed"
                        },
                        Some(e),
                    )
                    .await;
            }
        });
        *self.0.join.lock().await = Some(Work { cancel, task });
        Ok(serde_json::json!({"attempt_id":attempt}))
    }
    async fn join_flow(
        &self,
        code: PairCode,
        address: SocketAddrV4,
        attempt: &str,
    ) -> Result<(), RpcError> {
        let identity = self.identity()?;
        let local_ip = self.check_route(address).await?;
        let mut bootstrap =
            Client::connect(address, local_ip, None, &code.fingerprint, &code.device_id).await?;
        let cert = bootstrap.certificate.clone();
        let claim = Claim {
            hello: Hello::default(),
            invitation_id: code.invitation_id.clone(),
            attempt_id: attempt.into(),
            token: code.token.clone(),
            certificate: B64.encode(&identity.certificate),
            name: self.0.core.settings().await?.device_name,
            platform: std::env::consts::OS.into(),
        };
        let claimed: ClaimReply = bootstrap.post(wire::PAIR_PATH, &claim).await?;
        drop(claim);
        drop(bootstrap);
        claimed.hello.validate()?;
        wire::validate_name(&claimed.name)?;
        wire::validate_platform(&claimed.platform)?;
        if claimed.attempt_id != attempt
            || claimed.api_port == 0
            || B64.decode(&claimed.certificate).map_err(|_| {
                err(
                    "INVALID_CERTIFICATE",
                    "The claimed host certificate is malformed.",
                )
            })? != cert
        {
            return Err(err(
                "PAIRING_IDENTITY_MISMATCH",
                "The bootstrap result does not match the pinned TLS identity and attempt.",
            ));
        }
        let api = SocketAddrV4::new(*address.ip(), claimed.api_port);
        self.set_join(attempt, "proving", None).await;
        let mut client = Client::connect(
            api,
            local_ip,
            Some(&identity),
            &code.fingerprint,
            &code.device_id,
        )
        .await?;
        let proof = Proof {
            hello: Hello::default(),
            invitation_id: code.invitation_id.clone(),
            attempt_id: attempt.into(),
        };
        let mut interrupted = 0;
        let approved = loop {
            let state: PairState = match client.post("/pair/v1/prove", &proof).await {
                Ok(state) => {
                    interrupted = 0;
                    state
                }
                Err(e) if wire::transient(&e) && interrupted < 5 => {
                    interrupted += 1;
                    tokio::time::sleep(Duration::from_millis(250)).await;
                    client = Client::connect(
                        api,
                        local_ip,
                        Some(&identity),
                        &code.fingerprint,
                        &code.device_id,
                    )
                    .await?;
                    continue;
                }
                Err(e) => return Err(e),
            };
            state.hello.validate()?;
            state.permissions.validate()?;
            match state.state.as_str() {
                "approved" => break state.permissions,
                "awaiting_owner" => {
                    self.set_join(attempt, "awaiting_approval", None).await;
                    tokio::time::sleep(Duration::from_millis(650)).await;
                }
                _ => {
                    return Err(err(
                        "PAIRING_STATE_CHANGED",
                        "The host returned an unexpected pairing state.",
                    ))
                }
            }
        };
        self.set_join(attempt, "finalizing", None).await;
        let peer = Peer {
            id: code.device_id.clone(),
            name: claimed.name,
            platform: claimed.platform,
            cert,
            fingerprint: code.fingerprint.clone(),
            state: "pending".into(),
            direction: "outbound".into(),
            endpoint: Some(api.to_string()),
            permissions: PeerPermissions::default(),
            remote_permissions: approved,
            revision: 1,
            epoch: 1,
            attempt_id: attempt.into(),
            invitation_id: code.invitation_id.clone(),
            paired_at: Utc::now().to_rfc3339(),
            last_seen: None,
        };
        self.0.store.put_peer(&peer).await?;
        let active: PairState = client.post("/pair/v1/ack", &proof).await?;
        active.hello.validate()?;
        active.permissions.validate()?;
        if active.state != "active" {
            return Err(err(
                "PAIRING_STATE_CHANGED",
                "The final acknowledgement did not activate this identity.",
            ));
        }
        self.0
            .store
            .activate_outbound(&peer.id, attempt, &active.permissions)
            .await?;
        self.refresh_pins().await?;
        let hello: Health = client.post("/api/v1/hello", &Hello::default()).await?;
        self.check_health(&peer.id, &hello)?;
        self.mark_online(&peer.id).await?;
        self.set_join(attempt, "paired", None).await;
        let _=self.0.core.record("pairing","Device pairing completed","The pinned host confirmed a durable approved trust record over mutual TLS. Content access remains separately authorized.").await;
        drop(client);
        self.start_worker(self.0.store.peer(&peer.id).await?).await;
        Ok(())
    }
    async fn check_route(&self, address: SocketAddrV4) -> Result<Ipv4Addr, RpcError> {
        let s = self.0.state.lock().await;
        let local = s.endpoint.ok_or_else(|| {
            err(
                "NETWORK_DISABLED",
                "The selected connection interface is not active.",
            )
        })?;
        if local.ip().is_loopback() != address.ip().is_loopback() {
            return Err(err("INTERFACE_ROUTE_MISMATCH","Loopback test workspaces cannot route to a LAN peer, and a LAN interface cannot target local services."));
        }
        Ok(*local.ip())
    }
    async fn set_join(&self, attempt: &str, state: &str, error: Option<RpcError>) {
        let mut s = self.0.state.lock().await;
        if let Some(j) = s.join.as_mut().filter(|j| j.attempt_id == attempt) {
            j.state = state.into();
            j.error = error;
        }
    }
    async fn set_runtime(
        &self,
        id: &str,
        state: &str,
        error: Option<RpcError>,
        retry: Option<Duration>,
    ) {
        let mut s = self.0.state.lock().await;
        let r = s.runtime.entry(id.into()).or_default();
        r.state = state.into();
        r.error = error;
        r.retry_at = retry.map(|d| Instant::now() + d);
        if state != "online" {
            r.last_seen = None;
        }
    }
    async fn mark_online(&self, id: &str) -> Result<(), RpcError> {
        let should_write = {
            let mut s = self.0.state.lock().await;
            let r = s.runtime.entry(id.into()).or_default();
            let write = r
                .last_seen
                .is_none_or(|i| i.elapsed() >= Duration::from_secs(4));
            r.state = "online".into();
            r.error = None;
            r.retry_at = None;
            r.last_seen = Some(Instant::now());
            write
        };
        if should_write {
            self.0.store.touch(id).await?;
        }
        Ok(())
    }
    async fn stop_worker(&self, id: &str) {
        if let Some(w) = self.0.workers.lock().await.remove(id) {
            w.cancel.cancel();
            let _ = w.task.await;
        }
    }
    async fn start_worker(&self, peer: Peer) {
        self.stop_worker(&peer.id).await;
        let id = peer.id.clone();
        let Ok(cancel) = self.network_child().await else {
            return;
        };
        let child = cancel.clone();
        let service = self.clone();
        let task = tokio::spawn(async move {
            tokio::select! {_=child.cancelled()=>{},_=service.worker(peer)=>{}}
        });
        self.0
            .workers
            .lock()
            .await
            .insert(id, Work { cancel, task });
    }
    async fn worker(&self, mut peer: Peer) {
        let mut failures = 0u32;
        loop {
            self.set_runtime(&peer.id, "connecting", None, None).await;
            let result: Result<(), RpcError> = async {
                let identity = self.identity()?;
                let address = endpoint::endpoint(peer.endpoint.as_deref().ok_or_else(|| {
                    err(
                        "ENDPOINT_REQUIRED",
                        "Enter this pinned peer's current endpoint.",
                    )
                })?)?;
                let ip = self.check_route(address).await?;
                let mut client =
                    Client::connect(address, ip, Some(&identity), &peer.fingerprint, &peer.id)
                        .await?;
                if peer.state == "pending" {
                    let proof = Proof {
                        hello: Hello::default(),
                        attempt_id: peer.attempt_id.clone(),
                        invitation_id: peer.invitation_id.clone(),
                    };
                    let active: PairState = client.post("/pair/v1/ack", &proof).await?;
                    active.hello.validate()?;
                    active.permissions.validate()?;
                    if active.state != "active" {
                        return Err(err(
                            "PAIRING_STATE_CHANGED",
                            "The saved pairing needs a fresh approved invitation.",
                        ));
                    }
                    self.0
                        .store
                        .activate_outbound(&peer.id, &peer.attempt_id, &active.permissions)
                        .await?;
                    self.refresh_pins().await?;
                    peer = self.0.store.peer(&peer.id).await?;
                    self.set_join(&peer.attempt_id, "paired", None).await;
                }
                loop {
                    self.0
                        .store
                        .authorize_session(&peer.id, peer.revision, peer.epoch)
                        .await?;
                    let health: Health = client.post("/api/v1/health", &Hello::default()).await?;
                    self.check_health(&peer.id, &health)?;
                    self.0
                        .store
                        .remote_permissions(&peer.id, &health.permissions)
                        .await?;
                    self.mark_online(&peer.id).await?;
                    self.set_join(&peer.attempt_id, "paired", None).await;
                    failures = 0;
                    tokio::time::sleep(Duration::from_secs(5)).await;
                }
            }
            .await;
            let error = match result {
                Err(e) => e,
                Ok(()) => return,
            };
            failures += 1;
            if !wire::transient(&error) {
                self.set_runtime(&peer.id, "blocked", Some(error), None)
                    .await;
                return;
            }
            if failures >= 6 {
                self.set_runtime(&peer.id,"retry_stopped",Some(err("RETRY_LIMIT","Automatic retries stopped after six failures. Check the peer agent, selected interface, firewall and endpoint, then retry manually.")),None).await;
                return;
            }
            let max_ms = (500u64.saturating_mul(1u64 << failures.min(6))).min(30_000);
            let random = identity::random::<8>()
                .map(u64::from_le_bytes)
                .unwrap_or(max_ms);
            let delay = Duration::from_millis(250 + random % max_ms);
            self.set_runtime(&peer.id, "retrying", Some(error), Some(delay))
                .await;
            tokio::time::sleep(delay).await;
        }
    }
    fn check_health(&self, id: &str, h: &Health) -> Result<(), RpcError> {
        h.hello.validate()?;
        h.permissions.validate()?;
        if h.device_id != id
            || h.grant_revision == 0
            || h.session_epoch == 0
            || !wire::valid_health_operations(&h.operations)
        {
            return Err(err("INVALID_HANDSHAKE","The peer identity, authorization revision or advertised operations changed unexpectedly."));
        }
        Ok(())
    }
}

/// Keep every active peer and its full grant set. Trim only overview material if
/// necessary; never solve a framing limit by silently granting or hiding active trust.
fn bound_snapshot(mut snapshot: Snapshot) -> Result<Snapshot, RpcError> {
    let limit = MAX_FRAME_BYTES - 1024;
    if serde_json::to_vec(&snapshot)
        .map_err(|_| RpcError::storage())?
        .len()
        <= limit
    {
        return Ok(snapshot);
    }
    snapshot.activity.truncate(5);
    if let Some(c) = snapshot.connections.as_mut() {
        c.discovery.candidates.truncate(8);
        c.interfaces.sort_by_key(|i| {
            if i.ip == c.config.bind_ip {
                0
            } else if i.eligible {
                1
            } else {
                2
            }
        });
        c.interfaces.truncate(8);
        let mut revoked = 0;
        c.peers.retain(|p| {
            if p.state == "revoked" {
                revoked += 1;
                revoked <= 4
            } else {
                true
            }
        });
        c.view_notice=Some("This overview was shortened to fit the local IPC frame. Active devices and all their grants are intact; only recent activity, revoked history, discovery and interface hints were shortened.".into());
    }
    if serde_json::to_vec(&snapshot)
        .map_err(|_| RpcError::storage())?
        .len()
        > limit
    {
        return Err(err("SNAPSHOT_LIMIT","The metadata overview exceeds its safe frame budget. No trust or grants were reset. Inspect the local store before proceeding."));
    }
    Ok(snapshot)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn oversized_overviews_are_compacted_without_resetting_metadata() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        for _ in 0..30 {
            core.record("fixture", &"x".repeat(2000), &"y".repeat(2000))
                .await
                .unwrap();
        }
        let original = core.snapshot().await.unwrap();
        assert!(serde_json::to_vec(&original).unwrap().len() > MAX_FRAME_BYTES);
        let small = bound_snapshot(original).unwrap();
        assert_eq!(small.activity.len(), 5);
        assert!(serde_json::to_vec(&small).unwrap().len() < MAX_FRAME_BYTES);
        assert_eq!(core.activity().await.unwrap().len(), 30);
    }
}
