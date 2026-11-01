//! One-request-per-connection local RPC, bounded frames, same-user OS credentials.
use narada_protocol::{Command, Request, Response, RpcError, MAX_FRAME_BYTES};
use std::{
    future::Future,
    io,
    path::{Path, PathBuf},
    pin::Pin,
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader},
    sync::{watch, Semaphore},
    task::JoinSet,
};

pub type Handler =
    Arc<dyn Fn(Request) -> Pin<Box<dyn Future<Output = Response> + Send>> + Send + Sync>;
#[cfg(windows)]
mod windows;

async fn read_frame<S: AsyncRead + Unpin>(stream: &mut S) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::with_capacity(1024);
    let mut limited = BufReader::new(stream).take((MAX_FRAME_BYTES + 1) as u64);
    limited.read_until(b'\n', &mut bytes).await?;
    if bytes.len() > MAX_FRAME_BYTES || bytes.last() != Some(&b'\n') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid or oversized local RPC frame",
        ));
    }
    bytes.pop();
    Ok(bytes)
}
async fn write_frame<S: AsyncWrite + Unpin>(stream: &mut S, response: &Response) -> io::Result<()> {
    let mut bytes = serde_json::to_vec(response)?;
    if bytes.len() + 1 > MAX_FRAME_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Response limit exceeded",
        ));
    }
    bytes.push(b'\n');
    stream.write_all(&bytes).await?;
    stream.flush().await
}
async fn connection<S: AsyncRead + AsyncWrite + Unpin>(
    mut stream: S,
    handler: Handler,
    timeout: Duration,
) -> io::Result<()> {
    tokio::time::timeout(timeout, async {
        let response = match read_frame(&mut stream).await {
            Ok(bytes) => match serde_json::from_slice::<Request>(&bytes) {
                Ok(request) => handler(request).await,
                Err(_) => Response::result(
                    String::new(),
                    Err(RpcError::new(
                        "INVALID_REQUEST",
                        "The local request is invalid.",
                    )),
                ),
            },
            Err(_) => Response::result(
                String::new(),
                Err(RpcError::new(
                    "FRAME_REJECTED",
                    "The local request exceeds the frame limit or is incomplete.",
                )),
            ),
        };
        write_frame(&mut stream, &response).await
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Local RPC timed out"))?
}

#[cfg(unix)]
pub async fn serve(
    data_dir: PathBuf,
    handler: Handler,
    mut stop: watch::Receiver<bool>,
    max_clients: usize,
    timeout: Duration,
) -> io::Result<()> {
    use std::os::unix::fs::{FileTypeExt, PermissionsExt};
    let path = data_dir.join("agent.sock");
    if let Ok(metadata) = tokio::fs::symlink_metadata(&path).await {
        if !metadata.file_type().is_socket() {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "Unexpected IPC endpoint type",
            ));
        }
        // The agent must hold its exclusive data-directory lock before calling serve.
        tokio::fs::remove_file(&path).await?;
    }
    let listener = tokio::net::UnixListener::bind(&path)?;
    tokio::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).await?;
    let permits = Arc::new(Semaphore::new(max_clients));
    let mut tasks = JoinSet::new();
    loop {
        tokio::select! {
            _=stop.changed()=>break,
            accepted=listener.accept()=>{
                let (stream,_)=accepted?;
                if !stream.peer_cred().map(|c| c.uid()==unsafe {libc::geteuid()}).unwrap_or(false) {continue;}
                let Ok(permit)=permits.clone().try_acquire_owned() else {continue};
                let handler=handler.clone();
                tasks.spawn(async move {let _permit=permit;let _=connection(stream,handler,timeout).await;});
            },
            _=tasks.join_next(),if !tasks.is_empty()=>{},
        }
    }
    let _ = tokio::time::timeout(timeout, async {
        while tasks.join_next().await.is_some() {}
    })
    .await;
    tasks.abort_all();
    drop(listener);
    let _ = tokio::fs::remove_file(path).await;
    Ok(())
}

#[cfg(windows)]
pub async fn serve(
    data_dir: PathBuf,
    handler: Handler,
    mut stop: watch::Receiver<bool>,
    max_clients: usize,
    timeout: Duration,
) -> io::Result<()> {
    let name = windows::pipe_name(&data_dir)?;
    let mut server = windows::create_server(&name, true)?;
    let permits = Arc::new(Semaphore::new(max_clients));
    let mut tasks = JoinSet::new();
    loop {
        tokio::select! {
            _=stop.changed()=>break,
            connected=server.connect()=>{
                connected?;
                let next=windows::create_server(&name,false)?;
                let stream=std::mem::replace(&mut server,next);
                if !windows::verify_client(&stream).unwrap_or(false) {continue;}
                let Ok(permit)=permits.clone().try_acquire_owned() else {continue};
                let handler=handler.clone();
                tasks.spawn(async move {let _permit=permit;let _=connection(stream,handler,timeout).await;});
            },
            _=tasks.join_next(),if !tasks.is_empty()=>{},
        }
    }
    let _ = tokio::time::timeout(timeout, async {
        while tasks.join_next().await.is_some() {}
    })
    .await;
    tasks.abort_all();
    Ok(())
}

pub async fn call(data_dir: &Path, command: Command) -> Result<serde_json::Value, RpcError> {
    let request = Request::new(command);
    let action = async {
        #[cfg(unix)]
        let mut stream = {
            let stream = tokio::net::UnixStream::connect(data_dir.join("agent.sock")).await?;
            if stream.peer_cred()?.uid() != unsafe { libc::geteuid() } {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "IPC owner mismatch",
                ));
            }
            stream
        };
        #[cfg(windows)]
        let mut stream = { windows::connect(data_dir).await? };
        let mut bytes = serde_json::to_vec(&request)?;
        bytes.push(b'\n');
        if bytes.len() > MAX_FRAME_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Request limit exceeded",
            ));
        }
        stream.write_all(&bytes).await?;
        let bytes = read_frame(&mut stream).await?;
        let response: Response = serde_json::from_slice(&bytes)?;
        if response.id != request.id || response.version != narada_protocol::PROTOCOL_VERSION {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Response correlation mismatch",
            ));
        }
        Ok::<_, io::Error>(response)
    };
    let response = tokio::time::timeout(Duration::from_secs(10), action)
        .await
        .map_err(|_| RpcError::new("AGENT_TIMEOUT", "The local agent did not respond in time."))?
        .map_err(|_| {
            RpcError::new(
                "AGENT_UNAVAILABLE",
                "The local agent is unavailable. Start it and try again.",
            )
        })?;
    if response.ok {
        response.data.ok_or_else(|| {
            RpcError::new(
                "INVALID_RESPONSE",
                "The agent returned an invalid response.",
            )
        })
    } else {
        Err(response.error.unwrap_or_else(|| {
            RpcError::new("INVALID_RESPONSE", "The agent returned an invalid error.")
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn frames_require_termination() {
        let mut input = &b"{}"[..];
        assert!(read_frame(&mut input).await.is_err());
    }
    #[tokio::test]
    async fn oversized_frames_are_rejected() {
        let data = vec![b'x'; MAX_FRAME_BYTES + 2];
        let mut input = data.as_slice();
        assert!(read_frame(&mut input).await.is_err());
    }
    #[tokio::test]
    async fn valid_frame_is_decoded() {
        let mut input = &b"{\"ok\":true}\n"[..];
        assert_eq!(read_frame(&mut input).await.unwrap(), b"{\"ok\":true}");
    }
    #[tokio::test]
    async fn invalid_command_receives_error() {
        let (mut client, server) = tokio::io::duplex(1024);
        let handler: Handler =
            Arc::new(|_| Box::pin(async { panic!("invalid command must not reach handler") }));
        let task = tokio::spawn(connection(server, handler, Duration::from_secs(2)));
        client
            .write_all(b"{\"command\":\"shell\"}\n")
            .await
            .unwrap();
        let response: Response =
            serde_json::from_slice(&read_frame(&mut client).await.unwrap()).unwrap();
        assert!(!response.ok);
        task.await.unwrap().unwrap();
    }
    #[cfg(unix)]
    #[tokio::test]
    async fn unix_rpc_round_trip_and_cleanup() {
        let dir = tempfile::tempdir().unwrap();
        let (tx, rx) = watch::channel(false);
        let handler: Handler = Arc::new(|req| {
            Box::pin(async move { Response::result(req.id, Ok(serde_json::json!({"alive":true}))) })
        });
        let path = dir.path().to_owned();
        let task = tokio::spawn(serve(path.clone(), handler, rx, 2, Duration::from_secs(2)));
        for _ in 0..50 {
            if path.join("agent.sock").exists() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert_eq!(
            call(&path, Command::GetSnapshot).await.unwrap()["alive"],
            true
        );
        tx.send(true).unwrap();
        task.await.unwrap().unwrap();
        assert!(!path.join("agent.sock").exists());
    }
    #[tokio::test]
    async fn incomplete_clients_are_timed_out() {
        let (_client, server) = tokio::io::duplex(1024);
        let handler: Handler =
            Arc::new(|_| Box::pin(async { panic!("Incomplete frame reached handler") }));
        let error = connection(server, handler, Duration::from_millis(20))
            .await
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    }
    #[cfg(unix)]
    #[tokio::test]
    async fn response_correlation_mismatch_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let listener = tokio::net::UnixListener::bind(dir.path().join("agent.sock")).unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let _ = read_frame(&mut stream).await.unwrap();
            write_frame(
                &mut stream,
                &Response::result("not-the-request".into(), Ok(serde_json::json!({}))),
            )
            .await
            .unwrap();
        });
        assert!(call(dir.path(), Command::GetSnapshot).await.is_err());
        server.await.unwrap();
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn windows_owner_round_trip_and_workspace_isolation() {
        let first = tempfile::tempdir().unwrap();
        let second = tempfile::tempdir().unwrap();
        let (stop, receiver) = watch::channel(false);
        let handler: Handler = Arc::new(|req| {
            Box::pin(
                async move { Response::result(req.id, Ok(serde_json::json!({"alive": true}))) },
            )
        });
        let a = tokio::spawn(serve(
            first.path().to_owned(),
            handler.clone(),
            receiver.clone(),
            2,
            Duration::from_secs(2),
        ));
        let b = tokio::spawn(serve(
            second.path().to_owned(),
            handler,
            receiver,
            2,
            Duration::from_secs(2),
        ));
        assert_ne!(
            windows::pipe_name(first.path()).unwrap(),
            windows::pipe_name(second.path()).unwrap()
        );
        assert_eq!(
            call(first.path(), Command::GetSnapshot).await.unwrap()["alive"],
            true
        );
        assert_eq!(
            call(second.path(), Command::GetSnapshot).await.unwrap()["alive"],
            true
        );
        stop.send(true).unwrap();
        a.await.unwrap().unwrap();
        b.await.unwrap().unwrap();
    }
}
