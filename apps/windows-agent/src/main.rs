use clap::Parser;
use fs2::FileExt;
use narada_core::{AgentConfig, Core};
use narada_protocol::{Command, Request, RpcError};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::sync::watch;

#[derive(Parser)]
#[command(
    name = "NARADA agent",
    version,
    about = "Non-elevated NARADA desktop agent. Peer networking is opt-in; file, E2EE chat, attachment and sync routes require explicit scoped consent."
)]
struct Args {
    #[arg(long)]
    data_dir: Option<PathBuf>,
    #[arg(long)]
    config: Option<PathBuf>,
    #[arg(long)]
    check_config: bool,
    #[arg(long)]
    status: bool,
    #[cfg(feature = "dev-preview")]
    #[arg(long)]
    preview_port: Option<u16>,
}
#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("{}: {}", error.code, error.message);
        std::process::exit(1);
    }
}
async fn run() -> Result<(), RpcError> {
    let args = Args::parse();
    let data_dir = args.data_dir.unwrap_or(narada_core::default_data_dir()?);
    if args.status {
        let value = narada_ipc::call(&data_dir, Command::GetSnapshot).await?;
        println!(
            "{}",
            serde_json::to_string_pretty(&value).map_err(|_| RpcError::storage())?
        );
        return Ok(());
    }
    let config = match args.config {
        Some(path) => {
            AgentConfig::parse(&tokio::fs::read_to_string(path).await.map_err(|_| {
                RpcError::new(
                    "CONFIG_UNAVAILABLE",
                    "The requested configuration could not be read.",
                )
            })?)?
        }
        None => AgentConfig::default(),
    };
    config.validate()?;
    if args.check_config {
        println!("Configuration is valid. Peer networking is separately controlled by local-owner policy.");
        return Ok(());
    }
    tracing_subscriber::fmt()
        .with_env_filter(config.log_level.clone())
        .with_target(false)
        .init();
    tokio::fs::create_dir_all(&data_dir)
        .await
        .map_err(|_| RpcError::storage())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        tokio::fs::set_permissions(&data_dir, std::fs::Permissions::from_mode(0o700))
            .await
            .map_err(|_| RpcError::storage())?;
    }
    let lock = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(data_dir.join("agent.lock"))
        .map_err(|_| RpcError::storage())?;
    lock.try_lock_exclusive().map_err(|_| {
        RpcError::new(
            "ALREADY_RUNNING",
            "An agent already owns this workspace. It has not been replaced.",
        )
    })?;
    let core = Core::open(&data_dir).await?;
    let service = narada_connection::Service::open(core.clone(), &data_dir).await?;
    let (stop_tx, stop_rx) = watch::channel(false);
    let handler: narada_ipc::Handler = {
        let service = service.clone();
        let stop = stop_tx.clone();
        Arc::new(move |req: Request| {
            let service = service.clone();
            let stop = stop.clone();
            Box::pin(async move {
                let shutdown = req.validate().is_ok() && matches!(req.command, Command::Shutdown);
                let response = service.dispatch(&req).await;
                if shutdown {
                    let _ = stop.send(true);
                }
                response
            })
        })
    };
    let ipc = tokio::spawn(narada_ipc::serve(
        data_dir,
        handler,
        stop_rx.clone(),
        config.max_clients,
        Duration::from_millis(config.rpc_timeout_ms),
    ));
    #[cfg(feature = "dev-preview")]
    let mut preview = if let Some(port) = args.preview_port {
        let token = std::env::var("NARADA_DEV_TOKEN").map_err(|_| {
            RpcError::new(
                "PREVIEW_TOKEN_REQUIRED",
                "The development bridge requires a server-side token.",
            )
        })?;
        if token.len() < 32 {
            return Err(RpcError::new(
                "PREVIEW_TOKEN_REQUIRED",
                "The development token is too short.",
            ));
        }
        Some(tokio::spawn(preview::serve(
            service.clone(),
            port,
            token,
            stop_rx.clone(),
        )))
    } else {
        None
    };
    #[cfg(not(feature = "dev-preview"))]
    let mut preview: Option<tokio::task::JoinHandle<std::io::Result<()>>> = None;
    tracing::info!(
        version = narada_protocol::APP_VERSION,
        "Local agent ready; protected connection policy loaded"
    );
    let mut stop_listener = stop_rx;
    let mut ipc = ipc;
    let outcome = tokio::select! {
        signal = termination_signal() => signal.map_err(|_| RpcError::new("LIFECYCLE_ERROR", "The local shutdown signal could not be registered.")),
        _ = stop_listener.changed() => Ok(()),
        result = &mut ipc => {
            if matches!(result, Ok(Ok(()))) { Ok(()) }
            else { Err(RpcError::new("IPC_START_FAILED", "The local IPC endpoint failed. No fallback network endpoint was enabled.")) }
        },
        _ = monitor_preview(&mut preview) => {
            if *stop_listener.borrow() { Ok(()) }
            else { Err(RpcError::new("PREVIEW_START_FAILED", "The development bridge stopped or its port could not be bound. The agent has stopped safely.")) }
        }
    };
    let _ = stop_tx.send(true);
    if !ipc.is_finished() {
        let _ = ipc.await;
    }
    if let Some(preview) = preview {
        if !preview.is_finished() {
            let _ = preview.await;
        }
    }
    service.shutdown().await;
    core.close().await;
    drop(lock);
    tracing::info!("Local agent stopped cleanly");
    outcome
}
async fn monitor_preview(task: &mut Option<tokio::task::JoinHandle<std::io::Result<()>>>) {
    if let Some(task) = task {
        let _ = task.await;
    } else {
        std::future::pending::<()>().await;
    }
}
async fn termination_signal() -> std::io::Result<()> {
    #[cfg(unix)]
    {
        let mut signal = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
        tokio::select! { result = tokio::signal::ctrl_c() => result, _ = signal.recv() => Ok(()) }
    }
    #[cfg(not(unix))]
    {
        tokio::signal::ctrl_c().await
    }
}

#[cfg(feature = "dev-preview")]
mod preview {
    use super::*;
    use axum::{
        extract::{DefaultBodyLimit, State},
        http::{HeaderMap, StatusCode},
        response::IntoResponse,
        routing::{get, post},
        Json, Router,
    };
    use subtle::ConstantTimeEq;
    #[derive(Clone)]
    struct Preview {
        service: narada_connection::Service,
        token: Arc<String>,
    }
    pub async fn serve(
        service: narada_connection::Service,
        port: u16,
        token: String,
        mut stop: watch::Receiver<bool>,
    ) -> std::io::Result<()> {
        let app = Router::new()
            .route("/health", get(|| async { "narada-development-bridge" }))
            .route("/rpc", post(rpc))
            .layer(DefaultBodyLimit::max(narada_protocol::MAX_FRAME_BYTES))
            .with_state(Preview {
                service,
                token: Arc::new(token),
            });
        let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port)).await?;
        tracing::info!(port, "Development-only loopback bridge ready");
        axum::serve(listener, app)
            .with_graceful_shutdown(async move {
                let _ = stop.changed().await;
            })
            .await
    }
    async fn rpc(
        State(state): State<Preview>,
        headers: HeaderMap,
        Json(request): Json<Request>,
    ) -> impl IntoResponse {
        let supplied = headers
            .get("x-narada-dev-token")
            .and_then(|h| h.to_str().ok())
            .unwrap_or("");
        if !bool::from(supplied.as_bytes().ct_eq(state.token.as_bytes())) {
            return (
                StatusCode::UNAUTHORIZED,
                Json(
                    serde_json::json!({"error":{"code":"UNAUTHORIZED","message":"Development bridge authentication required."}}),
                ),
            );
        }
        // Browser preview never has agent lifecycle authority.
        if matches!(request.command, Command::Shutdown) {
            return (
                StatusCode::FORBIDDEN,
                Json(
                    serde_json::json!({"error":{"code":"NATIVE_ONLY","message":"Agent shutdown is a native-only action."}}),
                ),
            );
        }
        (
            StatusCode::OK,
            Json(serde_json::to_value(state.service.dispatch(&request).await).unwrap_or_default()),
        )
    }
}
