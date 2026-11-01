//! Durable non-secret metadata. Private identity bytes are never stored here.
use chrono::Utc;
use narada_protocol::*;
use serde::{Deserialize, Serialize};
use sqlx::{
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
    Row, SqlitePool,
};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct AgentConfig {
    pub log_level: String,
    pub rpc_timeout_ms: u64,
    pub max_clients: usize,
}
impl Default for AgentConfig {
    fn default() -> Self {
        Self {
            log_level: "info".into(),
            rpc_timeout_ms: 5000,
            max_clients: 16,
        }
    }
}
impl AgentConfig {
    pub fn validate(&self) -> Result<(), RpcError> {
        if !["error", "warn", "info", "debug"].contains(&self.log_level.as_str())
            || !(500..=30_000).contains(&self.rpc_timeout_ms)
            || !(1..=64).contains(&self.max_clients)
        {
            return Err(RpcError::new(
                "INVALID_CONFIG",
                "Configuration limits are invalid. Check the non-secret configuration example.",
            ));
        }
        Ok(())
    }
    pub fn parse(text: &str) -> Result<Self, RpcError> {
        let config: Self = toml::from_str(text).map_err(|_| {
            RpcError::new(
                "INVALID_CONFIG",
                "The agent configuration could not be parsed.",
            )
        })?;
        config.validate()?;
        Ok(config)
    }
}

pub fn default_data_dir() -> Result<PathBuf, RpcError> {
    directories::ProjectDirs::from("com", "narada", "NARADA")
        .map(|p| p.data_local_dir().to_owned())
        .ok_or_else(|| {
            RpcError::new(
                "DATA_DIRECTORY_UNAVAILABLE",
                "A user-scoped application data directory is required.",
            )
        })
}

#[derive(Clone)]
pub struct Core {
    pool: SqlitePool,
    db_path: PathBuf,
    started: Arc<Instant>,
    started_at: String,
}
impl Core {
    pub async fn open(data_dir: &Path) -> Result<Self, RpcError> {
        tokio::fs::create_dir_all(data_dir)
            .await
            .map_err(|_| RpcError::storage())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            tokio::fs::set_permissions(data_dir, std::fs::Permissions::from_mode(0o700))
                .await
                .map_err(|_| RpcError::storage())?;
        }
        let db_path = data_dir.join("narada.db");
        let opts = SqliteConnectOptions::new()
            .filename(&db_path)
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Full)
            .foreign_keys(true)
            .busy_timeout(Duration::from_secs(5));
        let pool = SqlitePoolOptions::new()
            .max_connections(4)
            .connect_with(opts)
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::migrate!("./migrations")
            .run(&pool)
            .await
            .map_err(|_| {
                RpcError::new(
                    "MIGRATION_FAILED",
                    "Metadata migration failed. Existing data has not been reset.",
                )
            })?;
        let now = Utc::now().to_rfc3339();
        let defaults =
            serde_json::to_string(&Settings::default()).map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT OR IGNORE INTO settings (id, document, updated_at) VALUES (1, ?, ?)")
            .bind(defaults)
            .bind(&now)
            .execute(&pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let core = Self {
            pool,
            db_path,
            started: Arc::new(Instant::now()),
            started_at: now,
        };
        // Fail closed on invalid existing preferences; don't replace with defaults.
        core.settings().await?.validated()?;
        core.record(
            "agent",
            "Local agent started",
            "Metadata storage is ready. Connection policy is managed separately.",
        )
        .await?;
        Ok(core)
    }
    pub async fn settings(&self) -> Result<Settings, RpcError> {
        let text: String = sqlx::query_scalar("SELECT document FROM settings WHERE id=1")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        serde_json::from_str(&text).map_err(|_| {
            RpcError::new(
                "INVALID_STORED_SETTINGS",
                "Stored preferences are invalid. They have not been overwritten.",
            )
        })
    }
    pub async fn save_settings(&self, settings: Settings) -> Result<Settings, RpcError> {
        let settings = settings.validated()?;
        let json = serde_json::to_string(&settings).map_err(|_| RpcError::storage())?;
        let now = Utc::now().to_rfc3339();
        let mut tx = self.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query("UPDATE settings SET document=?, updated_at=? WHERE id=1")
            .bind(json)
            .bind(&now)
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT INTO activity (kind,title,detail,created_at) VALUES ('settings','Workspace preferences saved','Your local preferences were committed to storage.',?)")
            .bind(&now).execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY id DESC LIMIT 200)").execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(settings)
    }
    pub async fn record(&self, kind: &str, title: &str, detail: &str) -> Result<(), RpcError> {
        let mut tx = self.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT INTO activity (kind,title,detail,created_at) VALUES (?,?,?,?)")
            .bind(kind)
            .bind(title)
            .bind(detail)
            .bind(Utc::now().to_rfc3339())
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY id DESC LIMIT 200)").execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn activity(&self) -> Result<Vec<Activity>, RpcError> {
        let rows = sqlx::query(
            "SELECT id,kind,title,detail,created_at FROM activity ORDER BY id DESC LIMIT 30",
        )
        .fetch_all(&self.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        Ok(rows
            .into_iter()
            .map(|r| Activity {
                id: r.get("id"),
                kind: r.get("kind"),
                title: r.get("title"),
                detail: r.get("detail"),
                created_at: r.get("created_at"),
            })
            .collect())
    }
    pub async fn clear_activity(&self) -> Result<(), RpcError> {
        let mut tx = self.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM activity")
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT INTO activity(kind,title,detail,created_at) VALUES ('maintenance','Activity history cleared','Local preferences and diagnostics were preserved.',?)").bind(Utc::now().to_rfc3339()).execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn run_diagnostics(&self) -> Result<Diagnostics, RpcError> {
        self.run_diagnostics_with(Vec::new()).await
    }
    pub async fn run_diagnostics_with(&self, extra: Vec<Check>) -> Result<Diagnostics, RpcError> {
        let start = Instant::now();
        let integrity: String = sqlx::query_scalar("PRAGMA quick_check")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let mode: String = sqlx::query_scalar("PRAGMA journal_mode")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let fk: i64 = sqlx::query_scalar("PRAGMA foreign_keys")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let sync: i64 = sqlx::query_scalar("PRAGMA synchronous")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let settings_ok = self.settings().await?.validated().is_ok();
        let check = |id: &str, label: &str, ok: bool, detail: &str| Check {
            id: id.into(),
            label: label.into(),
            status: if ok { "passed" } else { "failed" }.into(),
            detail: detail.into(),
        };
        let mut result = Diagnostics { checked_at: Utc::now().to_rfc3339(), duration_ms: start.elapsed().as_millis(), checks: vec![
            check("database", "Database integrity", integrity=="ok", "SQLite quick_check on the actual metadata database."),
            check("durability", "Durable metadata writes", mode=="wal" && fk==1 && sync==2, "WAL mode, foreign keys, and synchronous=FULL."),
            check("preferences", "Workspace preferences", settings_ok, "Stored preferences conform to the validated schema."),

            Check {id:"secrets".into(),label:"Sensitive storage".into(),status:"not_applicable".into(),detail:"Chat keys and message history are not implemented. Transport identity protection is reported separately.".into()},
        ]};
        result.checks.extend(extra);
        let mut tx = self.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT INTO diagnostics(id,document,checked_at) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET document=excluded.document,checked_at=excluded.checked_at")
            .bind(serde_json::to_string(&result).map_err(|_| RpcError::storage())?).bind(&result.checked_at).execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT INTO activity(kind,title,detail,created_at) VALUES('diagnostics','Workspace health checked','Database, durability and preference checks completed.',?)").bind(&result.checked_at).execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY id DESC LIMIT 200)").execute(&mut *tx).await.map_err(|_| RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(result)
    }
    pub async fn snapshot(&self) -> Result<Snapshot, RpcError> {
        let schema_version: i64 = sqlx::query_scalar(
            "SELECT COALESCE(MAX(version),0) FROM _sqlx_migrations WHERE success=1",
        )
        .fetch_one(&self.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        let journal_mode: String = sqlx::query_scalar("PRAGMA journal_mode")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let last: Option<String> =
            sqlx::query_scalar("SELECT document FROM diagnostics WHERE id=1")
                .fetch_optional(&self.pool)
                .await
                .map_err(|_| RpcError::storage())?;
        let last_diagnostics = last
            .map(|s| serde_json::from_str::<Diagnostics>(&s).map_err(|_| RpcError::storage()))
            .transpose()?;
        let mut database_bytes = 0;
        for path in [
            self.db_path.clone(),
            self.db_path.with_extension("db-wal"),
            self.db_path.with_extension("db-shm"),
        ] {
            if let Ok(meta) = tokio::fs::metadata(path).await {
                database_bytes += meta.len();
            }
        }
        Ok(Snapshot {
            settings: self.settings().await?,
            agent: AgentStatus {
                version: APP_VERSION.into(),
                platform: std::env::consts::OS.into(),
                started_at: self.started_at.clone(),
                uptime_seconds: self.started.elapsed().as_secs(),
                ipc_transport: if cfg!(windows) {
                    "Windows Named Pipe"
                } else {
                    "Unix domain socket (development)"
                }
                .into(),
                peer_network_enabled: false,
            },
            storage: StorageStatus {
                schema_version,
                database_bytes,
                journal_mode,
                scope: "Operational metadata; no private keys or content".into(),
            },
            activity: self.activity().await?,
            last_diagnostics,
            paired_devices: 0,
            shared_roots: 0,
            active_transfers: 0,
            connections: None,
        })
    }
    pub async fn dispatch(&self, request: &Request) -> Response {
        let valid = request.validate();
        let id = if uuid_check(&request.id) {
            request.id.clone()
        } else {
            String::new()
        };
        let result = match valid {
            Err(error) => Err(error),
            Ok(()) => match &request.command {
                Command::GetSnapshot => self.snapshot().await.and_then(to_json),
                Command::SaveSettings(settings) => {
                    self.save_settings(settings.clone()).await.and_then(to_json)
                }
                Command::RunDiagnostics => self.run_diagnostics().await.and_then(to_json),
                Command::ClearActivity => self
                    .clear_activity()
                    .await
                    .map(|_| serde_json::json!({"cleared":true})),
                Command::Shutdown => Ok(serde_json::json!({"stopping":true})),
                _ => Err(RpcError::new(
                    "COMMAND_UNAVAILABLE",
                    "This command requires the connection service.",
                )),
            },
        };
        Response::result(id, result)
    }
    pub fn metadata_pool(&self) -> SqlitePool {
        self.pool.clone()
    }
    pub async fn close(&self) {
        self.pool.close().await;
    }
}
fn uuid_check(id: &str) -> bool {
    id.len() == 36 && id.chars().all(|c| c.is_ascii_hexdigit() || c == '-')
}
fn to_json<T: Serialize>(v: T) -> Result<serde_json::Value, RpcError> {
    serde_json::to_value(v).map_err(|_| {
        RpcError::new(
            "SERIALIZATION_FAILED",
            "The local result could not be encoded.",
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn config_limits_are_validated() {
        assert!(AgentConfig::default().validate().is_ok());
        assert!(AgentConfig::parse("max_clients = 999").is_err());
        assert!(AgentConfig::parse("enable_lan = true").is_err());
        assert!(AgentConfig::parse("log_level = 'trace'").is_err());
    }
    #[tokio::test]
    async fn migrates_and_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        assert_eq!(core.snapshot().await.unwrap().storage.schema_version, 6);
        core.close().await;
        let core = Core::open(dir.path()).await.unwrap();
        assert_eq!(core.snapshot().await.unwrap().storage.schema_version, 6);
    }
    #[tokio::test]
    async fn preferences_survive_restart() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        let s = Settings {
            device_name: "Studio".into(),
            setup_completed: true,
            ..Default::default()
        };
        core.save_settings(s.clone()).await.unwrap();
        core.close().await;
        let reopened = Core::open(dir.path()).await.unwrap();
        assert_eq!(reopened.settings().await.unwrap(), s);
    }
    #[tokio::test]
    async fn invalid_update_does_not_mutate() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        let before = core.settings().await.unwrap();
        let count = core.activity().await.unwrap().len();
        assert!(core
            .save_settings(Settings {
                device_name: "".into(),
                ..Default::default()
            })
            .await
            .is_err());
        assert_eq!(before, core.settings().await.unwrap());
        assert_eq!(count, core.activity().await.unwrap().len());
    }
    #[tokio::test]
    async fn diagnostics_are_real_and_persisted() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        let result = core.run_diagnostics().await.unwrap();
        assert!(!result.checks.iter().any(|c| c.status == "failed"));
        assert_eq!(
            core.snapshot()
                .await
                .unwrap()
                .last_diagnostics
                .unwrap()
                .checked_at,
            result.checked_at
        );
    }
    #[tokio::test]
    async fn clearing_activity_preserves_settings() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        core.clear_activity().await.unwrap();
        assert_eq!(core.activity().await.unwrap().len(), 1);
        assert_eq!(core.settings().await.unwrap(), Settings::default());
    }
    #[tokio::test]
    async fn diagnostics_do_not_export_paths_or_names() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        let json = serde_json::to_string(&core.run_diagnostics().await.unwrap()).unwrap();
        assert!(!json.contains(dir.path().to_str().unwrap()));
        assert!(!json.contains("device_name"));
    }
    #[tokio::test]
    async fn wrong_protocol_cannot_mutate() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        let mut req = Request::new(Command::SaveSettings(Settings {
            device_name: "changed".into(),
            ..Default::default()
        }));
        req.version = 99;
        assert!(!core.dispatch(&req).await.ok);
        assert_eq!(core.settings().await.unwrap().device_name, "My workspace");
    }
}
