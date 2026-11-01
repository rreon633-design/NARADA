use crate::{
    identity::{err, fingerprint, Identity},
    service::Service,
    tls,
    wire::{self, WireResponse},
};
use bytes::Bytes;
use http_body_util::{BodyExt, Full, Limited};
use hyper::{body::Incoming, service::service_fn, Request, Response};
use hyper_util::rt::TokioIo;
use narada_protocol::RpcError;
use serde::{de::DeserializeOwned, Serialize};
use std::{
    convert::Infallible,
    net::{Ipv4Addr, SocketAddrV4},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{
    net::{TcpListener, TcpSocket},
    task::JoinSet,
};
use tokio_rustls::{TlsAcceptor, TlsConnector};
use tokio_util::sync::CancellationToken;

pub type Body = Full<Bytes>;
pub fn response(result: Result<serde_json::Value, RpcError>) -> Response<Body> {
    let (status, payload) = match result {
        Ok(data) => (
            200,
            WireResponse {
                ok: true,
                data: Some(data),
                error: None,
            },
        ),
        Err(e) => {
            let status = match e.code.as_str() {
                "NO_PEER_ROUTE" => 404,
                "PROTOCOL_INCOMPATIBLE" | "CAPABILITIES_INCOMPATIBLE" => 409,
                "BODY_TOO_LARGE" => 413,
                "RATE_LIMITED" => 429,
                _ => 403,
            };
            (
                status,
                WireResponse {
                    ok: false,
                    data: None,
                    error: Some(e),
                },
            )
        }
    };
    let data=serde_json::to_vec(&payload).unwrap_or_else(|_|b"{\"ok\":false,\"data\":null,\"error\":{\"code\":\"ENCODING_FAILED\",\"message\":\"Response encoding failed.\"}}".to_vec());
    Response::builder()
        .status(status)
        .header("content-type", "application/json")
        .header("cache-control", "no-store")
        .header("x-content-type-options", "nosniff")
        .body(Full::new(Bytes::from(data)))
        .expect("constant response headers")
}
pub async fn body<T: DeserializeOwned>(request: Request<Incoming>) -> Result<T, RpcError> {
    if request
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .is_none_or(|v| v != "application/json")
    {
        return Err(err("INVALID_HANDSHAKE", "JSON content is required."));
    }
    if request.headers().contains_key("content-encoding") {
        return Err(err(
            "INVALID_HANDSHAKE",
            "Compressed peer requests are not accepted.",
        ));
    }
    let b = tokio::time::timeout(
        Duration::from_secs(3),
        Limited::new(request.into_body(), wire::MAX_BODY).collect(),
    )
    .await
    .map_err(|_| err("PEER_TIMEOUT", "The request body deadline elapsed."))?
    .map_err(|_| {
        err(
            "BODY_TOO_LARGE",
            "The peer request exceeded the permitted size.",
        )
    })?
    .to_bytes();
    serde_json::from_slice(&b).map_err(|_| {
        err(
            "INVALID_HANDSHAKE",
            "The peer request does not match the strict protocol schema.",
        )
    })
}
pub async fn serve(
    service: Service,
    listener: TcpListener,
    bootstrap: bool,
    cancel: CancellationToken,
) -> Result<(), RpcError> {
    let identity = service.identity()?;
    let config = tls::server(
        &identity,
        if bootstrap {
            None
        } else {
            Some(service.pins())
        },
    )?;
    let acceptor = TlsAcceptor::from(config);
    let mut tasks = JoinSet::new();
    let mut window = Instant::now();
    let mut accepted = 0;
    loop {
        tokio::select! {
         _=cancel.cancelled()=>break,
         Some(_)=tasks.join_next(),if !tasks.is_empty()=>{},
         incoming=listener.accept()=>{
          let (socket,address)=incoming.map_err(|_|err("LISTENER_FAILED","The selected peer listener stopped. Disable and re-enable connections to retry."))?;
          if window.elapsed()>Duration::from_secs(1){window=Instant::now();accepted=0;}
          if accepted>=12{drop(socket);continue;}accepted+=1;
          if !address.ip().is_loopback()&&!matches!(address.ip(),std::net::IpAddr::V4(v) if v.is_private()){drop(socket);continue;}
          let Ok(permit)=service.slots().try_acquire_owned()else{drop(socket);continue;};
          let acceptor=acceptor.clone();let service=service.clone();let cancel=cancel.clone();
          tasks.spawn(async move{
           let _permit=permit;
           let tls=match tokio::time::timeout(Duration::from_secs(5),acceptor.accept(socket)).await{Ok(Ok(s))=>s,_=>return};
           let fp=if bootstrap{None}else{tls.get_ref().1.peer_certificates().and_then(|c|c.first()).map(|c|fingerprint(c))};
           if !bootstrap&&fp.is_none(){return;}
           let (context,guard)=service.session(fp,cancel.child_token());let request_context=context.clone();let request_service=service.clone();
           let handler=service_fn(move|request:Request<Incoming>|{let service=request_service.clone();let ctx=request_context.clone();async move{
            if request.uri().path().starts_with("/api/v1/attachment-upload/chunks/") { let result=service.route_attachment_upload_chunk(request,bootstrap,&ctx).await;Ok::<_,Infallible>(response(result)) } else if request.uri().path().starts_with("/api/v1/blobs/chunks/") { let result=service.route_blob_chunk(request,bootstrap,&ctx).await;Ok::<_,Infallible>(binary_response(result)) } else if request.uri().path().starts_with("/api/v1/transfers/chunks/") { let result=service.route_transfer_chunk(request,bootstrap,&ctx).await;Ok::<_,Infallible>(binary_response(result)) } else { let result=service.route(request,bootstrap,&ctx).await;Ok::<_,Infallible>(response(result)) }
           }});
           let mut builder=hyper::server::conn::http1::Builder::new();builder.timer(hyper_util::rt::TokioTimer::new()).header_read_timeout(Duration::from_secs(5)).max_buf_size(16_384).keep_alive(true);
           let connection=builder.serve_connection(TokioIo::new(tls),handler);
           tokio::select!{_=context.cancel.cancelled()=>{},_=tokio::time::timeout(Duration::from_secs(60),connection)=>{}}
           drop(guard);
          });
         }
        }
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    Ok(())
}

pub struct Client {
    sender: hyper::client::conn::http1::SendRequest<Body>,
    driver: tokio::task::JoinHandle<()>,
    authority: String,
    pub certificate: Vec<u8>,
    failure: Arc<Mutex<Option<RpcError>>>,
    confirmed: bool,
}
impl Drop for Client {
    fn drop(&mut self) {
        self.driver.abort();
    }
}
impl Client {
    pub async fn connect(
        address: SocketAddrV4,
        local_ip: Ipv4Addr,
        identity: Option<&Identity>,
        pin: &str,
        id: &str,
    ) -> Result<Self, RpcError> {
        let config = tls::client(identity, pin, id)?;
        let tcp = TcpSocket::new_v4().map_err(|_| {
            err(
                "PEER_UNREACHABLE",
                "A local IPv4 socket could not be opened.",
            )
        })?;
        tcp.bind(SocketAddrV4::new(local_ip, 0).into())
            .map_err(|_| {
                err(
                    "INTERFACE_ROUTE_MISMATCH",
                    "The selected local interface is no longer available.",
                )
            })?;
        let socket=tokio::time::timeout(Duration::from_secs(4),tcp.connect(address.into())).await.map_err(|_|err("PEER_TIMEOUT","No connection before the deadline. Check the selected network, routing and firewall."))?.map_err(|_|err("PEER_UNREACHABLE","The pinned endpoint could not be reached. Check that its agent is running, then review routing or firewall settings."))?;
        socket.set_nodelay(true).map_err(|_| {
            err(
                "CONNECTION_INTERRUPTED",
                "The local socket could not be configured.",
            )
        })?;
        let tls=tokio::time::timeout(Duration::from_secs(5),TlsConnector::from(config).connect(tls::server_name(),socket)).await.map_err(|_|err("PEER_TIMEOUT","TLS negotiation timed out. Check the endpoint and firewall."))?.map_err(|_|err("IDENTITY_REJECTED","The pinned identity or client certificate was rejected. Verify both devices, clocks and revocation state. No automatic re-pairing or downgrade was attempted."))?;
        let certificate = tls
            .get_ref()
            .1
            .peer_certificates()
            .and_then(|c| c.first())
            .ok_or_else(|| {
                err(
                    "IDENTITY_REJECTED",
                    "The endpoint supplied no transport certificate.",
                )
            })?
            .to_vec();
        let (sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
            .await
            .map_err(|_| {
                err(
                    "CONNECTION_INTERRUPTED",
                    "The authenticated transport closed during negotiation.",
                )
            })?;
        let failure = Arc::new(Mutex::new(None));
        let record = failure.clone();
        let driver = tokio::spawn(async move {
            if let Err(e) = connection.await {
                if let Ok(mut f) = record.lock() {
                    *f = Some(classify_transport_error(&e));
                }
            }
        });
        Ok(Self {
            sender,
            driver,
            authority: address.to_string(),
            certificate,
            failure,
            confirmed: false,
        })
    }
    pub async fn post<T: Serialize, R: DeserializeOwned>(
        &mut self,
        path: &str,
        value: &T,
    ) -> Result<R, RpcError> {
        let bytes = serde_json::to_vec(value).map_err(|_| {
            err(
                "INVALID_HANDSHAKE",
                "The local handshake could not be encoded.",
            )
        })?;
        if bytes.len() > wire::MAX_BODY {
            return Err(err(
                "BODY_TOO_LARGE",
                "The peer message exceeds the protocol limit.",
            ));
        }
        let request = Request::builder()
            .method("POST")
            .uri(path)
            .header("host", &self.authority)
            .header("content-type", "application/json")
            .body(Full::new(Bytes::from(bytes)))
            .map_err(|_| err("INVALID_HANDSHAKE", "The request envelope is invalid."))?;
        let response=tokio::time::timeout(Duration::from_secs(5),self.sender.send_request(request)).await.map_err(|_|err("PEER_TIMEOUT","The authenticated peer did not respond before the deadline."))?.map_err(|e|{let error=self.failure.lock().ok().and_then(|f|f.clone()).unwrap_or_else(||classify_transport_error(&e));if !self.confirmed&&error.code=="CONNECTION_INTERRUPTED"{err("PEER_AUTH_UNCONFIRMED","The pinned endpoint closed before an authenticated protocol response. It may have rejected this client identity. Check both devices and retry manually.")}else{error}})?;
        if response.status().is_redirection() {
            return Err(err(
                "REDIRECT_REJECTED",
                "Peer redirects are never followed.",
            ));
        }
        let data = tokio::time::timeout(
            Duration::from_secs(3),
            Limited::new(
                response.into_body(),
                if matches!(path, "/api/v1/files" | "/api/v1/sync") {
                    narada_protocol::MAX_FILE_RESPONSE + 1024
                } else {
                    wire::MAX_BODY
                },
            )
            .collect(),
        )
        .await
        .map_err(|_| err("PEER_TIMEOUT", "The peer response exceeded its deadline."))?
        .map_err(|_| {
            err(
                "BODY_TOO_LARGE",
                "The peer response exceeds the protocol limit.",
            )
        })?
        .to_bytes();
        let payload: WireResponse<R> = serde_json::from_slice(&data).map_err(|_| {
            err(
                "INVALID_HANDSHAKE",
                "The peer response is malformed or incompatible.",
            )
        })?;
        if payload.ok {
            self.confirmed = true;
            if payload.error.is_some() {
                return Err(err(
                    "INVALID_HANDSHAKE",
                    "The peer response is inconsistent.",
                ));
            }
            payload
                .data
                .ok_or_else(|| err("INVALID_HANDSHAKE", "The peer response contains no result."))
        } else {
            Err(payload.error.map(sanitize_peer_error).unwrap_or_else(|| {
                err(
                    "INVALID_HANDSHAKE",
                    "The peer supplied no structured error.",
                )
            }))
        }
    }
}
pub fn value<T: Serialize>(t: T) -> Result<serde_json::Value, RpcError> {
    serde_json::to_value(t).map_err(|_| RpcError::storage())
}

fn classify_transport_error(error: &(dyn std::error::Error + 'static)) -> RpcError {
    let mut current = Some(error);
    while let Some(e) = current {
        if e.downcast_ref::<rustls::Error>().is_some() {
            return err("IDENTITY_REJECTED","The mutual-TLS peer rejected this identity or protocol. Check revocation and both device identities; no automatic re-pairing was attempted.");
        }
        current = e.source();
    }
    err("CONNECTION_INTERRUPTED","The authenticated transport closed. A bounded reconnect may be attempted; no identity pin will be changed.")
}

fn sanitize_peer_error(error: RpcError) -> RpcError {
    let message=match error.code.as_str(){
 "INVITATION_INVALID"=>"The invitation is unknown or its secret is invalid. Ask the owner for a new code.",
 "INVITATION_USED"=>"This one-time invitation has already been claimed. Ask the owner for a new code.",
 "INVITATION_EXPIRED"=>"This invitation expired or the owner restarted. Begin with a fresh invitation.",
 "OWNER_APPROVAL_REQUIRED"=>"The owner has not approved this pairing attempt.",
 "PROOF_REQUIRED"=>"Private-key proof is required before activation.",
 "PAIRING_NOT_ACTIVE"=>"This peer has not completed approved activation.",
 "PAIRING_IDENTITY_MISMATCH"=>"The peer identity does not match the pairing attempt.",
 "ALREADY_PAIRED"=>"This identity already has saved trust. Reconnect it or explicitly revoke and re-pair.",
 "PROTOCOL_INCOMPATIBLE"|"CAPABILITIES_INCOMPATIBLE"=>"The peer protocol or mandatory capabilities are incompatible. No downgrade was attempted.",
 "DEVICE_REVOKED"|"SESSION_INVALIDATED"=>"The peer rejected the current authorization state. Review trust on both devices.",
 "PEER_LIMIT"=>"The peer's trusted-device limit has been reached.",
 "ACCESS_DENIED"|"PERMISSION_DENIED"=>"The owner has not granted this operation for this root, or the current exclusion policy forbids it.",
 "ROOT_UNAVAILABLE"|"OBJECT_UNAVAILABLE"=>"The authorized root or object is no longer available. Refresh the file workspace.",
 "STALE_VERSION"=>"The file changed. Refresh its metadata before trying a new operation.",
 "CURSOR_EXPIRED"=>"The stable metadata cursor expired or its scope changed; request a fresh snapshot.",
 "CURSOR_STALE"=>"The index changed or this cursor expired. Restart from the first page.",
 "NAME_COLLISION"=>"That destination already exists. No file was overwritten.",
 "PREVIEW_UNSUPPORTED"=>"Only bounded PNG/JPEG pixel previews are supported.",
 "PREVIEW_LIMIT"=>"This image exceeds the bounded preview limits or cannot be safely decoded.",
 "INDEX_UNAVAILABLE"|"INDEX_LIMIT"=>"The owner must refresh or narrow this root before its index can be used.",
 "FILE_BUSY"=>"The peer's bounded file worker queue is full. Retry after current work completes.",
 "IDEMPOTENCY_CONFLICT"=>"That operation identifier was already used with different arguments.",
 "DIRECTORY_NOT_EMPTY"=>"Only files and empty folders may be mutated in this phase.",
 "TRASH_QUOTA"=>"The managed-trash quota is full. Restore retained objects; no automatic purge occurred.",
 "OPERATION_INDETERMINATE"|"OPERATION_NOT_APPLIED"=>"This operation has no confirmed result. Refresh and ask the owner to inspect recovery; no replacement mutation is automatic.",
 "TRANSFER_NOT_FOUND"|"TRANSFER_NOT_READY"|"RECEIVE_NOT_READY"|"TRANSFER_EXPIRED"|"TRANSFER_CANCELLED"=>"The bounded transfer is unavailable, unapproved, paused, expired or finished. Review its queue state before retrying.",
 "INVALID_MANIFEST"|"INVALID_CHUNK"|"HASH_MISMATCH"|"INVALID_RECEIPT"=>"Transfer integrity validation failed. No replacement content or success was inferred.",
 "TRANSFER_LIMIT"|"TRANSFER_QUOTA"|"STORAGE_FULL"=>"The peer's explicit file, queue or staging-space bound was reached. No destination was overwritten.",
 "STAGING_CHANGED"|"STAGING_UNTRUSTED"|"DESTINATION_CHANGED"|"SOURCE_CHANGED"=>"The approved filesystem object changed. The transfer stopped without adopting another object.",
 "COMMIT_OUTCOME_UNKNOWN"|"COMMIT_NOT_APPLIED"=>"Publication has no confirmed result. Inspect the receiver; do not assume delivery or repeat with a new identity.",
 "TRANSFER_INCOMPLETE"=>"The receiver still has missing or uncertain chunks. Resume the same manifest.",
 "DATA_BUSY"=>"The bounded binary-data lanes are occupied. Retry after current data work completes.",
 "CHAT_KEY_CHANGED"=>"The paired device changed its E2EE identity. No session was silently reset.",
 "CONTENT_STORE_LOCKED"=>"The peer's protected content store is locked. No plaintext fallback was used.",
 "CHAT_DECRYPT_FAILED"|"CHAT_SESSION_FAILED"|"CHAT_SESSION_MISSING"|"CHAT_KEY_INVALID"=>"The authenticated peer rejected this E2EE session or message. Its private state was preserved.",
 "MESSAGE_ID_CONFLICT"|"BLOB_ID_CONFLICT"=>"This identity is already bound to different content.",
 "CONTENT_QUOTA"|"SYNC_BUSY"|"SYNC_SNAPSHOT_LIMIT"=>"The peer's bounded content or metadata capacity is full.",
 "BLOB_UNAVAILABLE"|"BLOB_EXPIRED"=>"The ciphertext attachment is unavailable or expired.",
 "INVALID_SYNC_PAGE"=>"The metadata response did not satisfy the sync contract.",
 "INVALID_CERTIFICATE"=>"The peer rejected the transport certificate. Check the certificate and device clocks.",
 _=>return err("PEER_REJECTED","The authenticated peer rejected this request. Its untrusted error text was not displayed."),
 };
    err(&error.code, message)
}

fn binary_response(result: Result<Vec<u8>, RpcError>) -> Response<Body> {
    match result {
        Err(e) => response(Err(e)),
        Ok(data) => {
            let hash = fingerprint(&data);
            Response::builder()
                .status(200)
                .header("content-type", "application/octet-stream")
                .header("content-length", data.len())
                .header("x-narada-sha256", hash)
                .header("cache-control", "no-store")
                .header("x-content-type-options", "nosniff")
                .body(Full::new(Bytes::from(data)))
                .expect("constant binary headers")
        }
    }
}
pub async fn binary_body(request: Request<Incoming>, upload: bool) -> Result<Vec<u8>, RpcError> {
    let headers = request.headers();
    if headers.contains_key("content-encoding")
        || headers.contains_key("transfer-encoding")
        || headers
            .get("x-narada-transfer-version")
            .and_then(|v| v.to_str().ok())
            != Some("1")
    {
        return Err(err(
            "INVALID_CHUNK",
            "Only uncompressed, length-delimited version 1 chunks are accepted.",
        ));
    }
    let expected = headers
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok())
        .ok_or_else(|| {
            err(
                "INVALID_CHUNK",
                "An exact chunk content length is required.",
            )
        })?;
    let hash = headers
        .get("x-narada-sha256")
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    if (upload
        && (headers.get("content-type").and_then(|v| v.to_str().ok())
            != Some("application/octet-stream")
            || expected > narada_protocol::TRANSFER_CHUNK_BYTES))
        || (!upload && expected != 0)
    {
        return Err(err(
            "INVALID_CHUNK",
            "The binary request dimensions are invalid.",
        ));
    }
    if upload {
        narada_protocol::transfer_hash(hash.as_deref().unwrap_or(""))?;
    }
    let data = tokio::time::timeout(
        Duration::from_secs(3),
        Limited::new(request.into_body(), expected).collect(),
    )
    .await
    .map_err(|_| err("PEER_TIMEOUT", "The chunk body deadline elapsed."))?
    .map_err(|_| {
        err(
            "BODY_TOO_LARGE",
            "The binary chunk exceeded its declared bound.",
        )
    })?
    .to_bytes();
    if data.len() != expected || upload && hash.as_deref() != Some(fingerprint(&data).as_str()) {
        return Err(err(
            "HASH_MISMATCH",
            "The binary chunk length or digest failed validation.",
        ));
    }
    Ok(data.to_vec())
}
impl Client {
    pub async fn chunk(
        &mut self,
        id: &str,
        index: u32,
        upload: Option<Vec<u8>>,
    ) -> Result<Vec<u8>, RpcError> {
        self.binary_chunk("/api/v1/transfers/chunks", id, index, upload)
            .await
    }
    pub async fn blob_chunk(&mut self, id: &str, index: u32) -> Result<Vec<u8>, RpcError> {
        self.binary_chunk("/api/v1/blobs/chunks", id, index, None)
            .await
    }
    async fn binary_chunk(
        &mut self,
        prefix: &str,
        id: &str,
        index: u32,
        upload: Option<Vec<u8>>,
    ) -> Result<Vec<u8>, RpcError> {
        narada_protocol::file_id(id)?;
        if index as usize >= narada_protocol::MAX_TRANSFER_CHUNKS {
            return Err(err(
                "INVALID_CHUNK",
                "Chunk index exceeds the explicit file bound.",
            ));
        }
        let sending = upload.is_some();
        let data = upload.unwrap_or_default();
        if data.len() > narada_protocol::TRANSFER_CHUNK_BYTES {
            return Err(err("INVALID_CHUNK", "The chunk is too large."));
        }
        let request = Request::builder()
            .method(if sending { "PUT" } else { "GET" })
            .uri(format!("{prefix}/{id}/{index}"))
            .header("host", &self.authority)
            .header("content-type", "application/octet-stream")
            .header("content-length", data.len())
            .header("x-narada-transfer-version", "1")
            .header("x-narada-sha256", fingerprint(&data))
            .body(Full::new(Bytes::from(data)))
            .map_err(|_| err("INVALID_CHUNK", "The binary envelope is invalid."))?;
        let response =
            tokio::time::timeout(Duration::from_secs(5), self.sender.send_request(request))
                .await
                .map_err(|_| {
                    err(
                        "PEER_TIMEOUT",
                        "The chunk acknowledgement deadline elapsed.",
                    )
                })?
                .map_err(|e| classify_transport_error(&e))?;
        if response.status().is_redirection() {
            return Err(err(
                "REDIRECT_REJECTED",
                "Transfer redirects are never followed.",
            ));
        }
        let binary = response.status() == hyper::StatusCode::OK
            && response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                == Some("application/octet-stream");
        let hash = response
            .headers()
            .get("x-narada-sha256")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let compressed = response.headers().contains_key("content-encoding");
        let max = if binary {
            narada_protocol::TRANSFER_CHUNK_BYTES
        } else {
            wire::MAX_BODY
        };
        let data = tokio::time::timeout(
            Duration::from_secs(3),
            Limited::new(response.into_body(), max).collect(),
        )
        .await
        .map_err(|_| err("PEER_TIMEOUT", "The binary response deadline elapsed."))?
        .map_err(|_| {
            err(
                "BODY_TOO_LARGE",
                "The peer exceeded the binary response bound.",
            )
        })?
        .to_bytes();
        if !binary {
            let payload: WireResponse<serde_json::Value> = serde_json::from_slice(&data)
                .map_err(|_| err("INVALID_HANDSHAKE", "The chunk error envelope is invalid."))?;
            return Err(payload.error.map(sanitize_peer_error).unwrap_or_else(|| {
                err(
                    "INVALID_HANDSHAKE",
                    "The binary result was not an authenticated chunk acknowledgement.",
                )
            }));
        }
        if compressed
            || hash.as_deref() != Some(fingerprint(&data).as_str())
            || (sending && !data.is_empty())
        {
            return Err(err(
                "HASH_MISMATCH",
                "The binary response digest or acknowledgement is invalid.",
            ));
        }
        self.confirmed = true;
        Ok(data.to_vec())
    }
}
