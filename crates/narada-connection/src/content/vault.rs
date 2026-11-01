//! One encrypted transactional state document: ratchets, inbox, outbox and keys
//! commit together. SQLite/WAL receive ciphertext only. A write-ahead guard makes
//! partial/DB-only rollback fail closed; complete-profile rollback is NOT solved.
use super::*;
use narada_files::safe_fs::{self, SafeRoot};
use ring::aead;
use sqlx::{
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
    Row, SqlitePool,
};
use std::{
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
};
use tokio::sync::Mutex;
use zeroize::Zeroizing;
const KEY_FILE: &str = "content.key";
const GUARD_FILE: &str = "content.guard";
const DB_FILE: &str = "content.db";
const STATE_LIMIT: usize = 4 * 1024 * 1024;
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct WrappedKey {
    format: String,
    device: String,
    key: String,
}
pub(super) struct Vault {
    pool: SqlitePool,
    root: SafeRoot,
    key: Zeroizing<Vec<u8>>,
    device: String,
    guard_identity: String,
    state: Mutex<Live>,
}
struct Live {
    sequence: u64,
    doc: Document,
    failed: bool,
}
impl Vault {
    pub async fn open(
        dir: &Path,
        device: &str,
        allow_initialize: bool,
    ) -> Result<Self, RpcError> {
        let root = SafeRoot::open(dir, None)?;
        let protector = crate::identity::Protector::open()?;
        let db_exists = dir.join(DB_FILE).exists();
        let guard_exists = dir.join(GUARD_FILE).exists();
        let key_exists = dir.join(KEY_FILE).exists();
        if !allow_initialize && (!key_exists || !db_exists || !guard_exists) {
            return Err(locked());
        }
        if (key_exists && !db_exists) || (db_exists && !guard_exists) {
            return Err(locked());
        }
        let key = match root.open_relative(KEY_FILE, false) {
            Ok(mut f) => {
                let info = safe_fs::info(&f.file)?;
                if info.size > 32768 {
                    return Err(locked());
                }
                let mut b = Vec::new();
                f.file.read_to_end(&mut b).map_err(|_| locked())?;
                let plain = Zeroizing::new(protector.unseal(&b).map_err(|_| locked())?);
                let mut k: WrappedKey = serde_json::from_slice(&plain).map_err(|_| locked())?;
                if k.format != "NARADA-CONTENT-KEY-1" || k.device != device {
                    return Err(locked());
                }
                let raw = Zeroizing::new(hex::decode(&k.key).map_err(|_| locked())?);
                use zeroize::Zeroize;
                k.key.zeroize();
                if raw.len() != 32 {
                    return Err(locked());
                }
                raw
            }
            Err(e)
                if e.code == "OBJECT_UNAVAILABLE"
                    && allow_initialize
                    && !db_exists
                    && !guard_exists =>
            {
                let raw = Zeroizing::new(crate::identity::random::<32>()?.to_vec());
                let k = WrappedKey {
                    format: "NARADA-CONTENT-KEY-1".into(),
                    device: device.into(),
                    key: hex::encode(&raw),
                };
                let plain = Zeroizing::new(serde_json::to_vec(&k).map_err(|_| locked())?);
                let blob = protector.seal(&plain)?;
                let mut f = root.open_owned_write(KEY_FILE, true, None)?;
                f.file
                    .write_all(&blob)
                    .and_then(|_| f.file.sync_all())
                    .map_err(|_| locked())?;
                sync_directory(&root)?;
                raw
            }
            Err(_) => return Err(locked()),
        };
        if guard_exists && !db_exists {
            return Err(locked());
        }
        if db_exists {
            root.info(DB_FILE)?;
        }
        let options = SqliteConnectOptions::new()
            .filename(dir.join(DB_FILE))
            .create_if_missing(allow_initialize && !guard_exists)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Full)
            .foreign_keys(true)
            .pragma("temp_store", "MEMORY")
            .pragma("secure_delete", "ON")
            .busy_timeout(std::time::Duration::from_secs(3));
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect_with(options)
            .await
            .map_err(|_| locked())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(dir.join(DB_FILE), std::fs::Permissions::from_mode(0o600))
                .map_err(|_| locked())?;
        }
        sqlx::query("CREATE TABLE IF NOT EXISTS protected_state(id INTEGER PRIMARY KEY CHECK(id=1),sequence INTEGER NOT NULL,sealed BLOB NOT NULL)").execute(&pool).await.map_err(|_|locked())?;
        let row = sqlx::query("SELECT sequence,sealed FROM protected_state WHERE id=1")
            .fetch_optional(&pool)
            .await
            .map_err(|_| locked())?;
        let (sequence, doc, guard_identity) = if let Some(row) = row {
            if !guard_exists {
                return Err(locked());
            }
            let sequence = row.get::<i64, _>("sequence");
            if sequence < 1 {
                return Err(locked());
            }
            let blob: Vec<u8> = row.get("sealed");
            if blob.len() > STATE_LIMIT + 64 {
                return Err(locked());
            }
            let mut f = root.open_relative(GUARD_FILE, false)?;
            let identity = safe_fs::info(&f.file)?.identity;
            let mut b = Vec::new();
            Read::by_ref(&mut f.file)
                .take(81)
                .read_to_end(&mut b)
                .map_err(|_| locked())?;
            if b != guard_bytes(sequence as u64, &blob) {
                return Err(locked());
            }
            let doc = open_document(&key, device, sequence as u64, blob)?;
            (sequence as u64, doc, identity)
        } else {
            if guard_exists || !allow_initialize {
                return Err(locked());
            }
            let doc = Document::new(device)?;
            let blob = seal_document(&key, device, 1, &doc)?;
            let mut f = root.open_owned_write(GUARD_FILE, true, None)?;
            f.file
                .write_all(&guard_bytes(1, &blob))
                .and_then(|_| f.file.sync_all())
                .map_err(|_| locked())?;
            let identity = safe_fs::info(&f.file)?.identity;
            sync_directory(&root)?;
            sqlx::query("INSERT INTO protected_state(id,sequence,sealed) VALUES(1,1,?)")
                .bind(&blob)
                .execute(&pool)
                .await
                .map_err(|_| locked())?;
            (1, doc, identity)
        };
        if doc.device != device || doc.version != 1 {
            return Err(locked());
        }
        Ok(Self {
            pool,
            root,
            key,
            device: device.into(),
            guard_identity,
            state: Mutex::new(Live {
                sequence,
                doc,
                failed: false,
            }),
        })
    }
    pub async fn read<T>(
        &self,
        f: impl FnOnce(&Document) -> Result<T, RpcError>,
    ) -> Result<T, RpcError> {
        let s = self.state.lock().await;
        if s.failed {
            return Err(locked());
        }
        f(&s.doc)
    }
    pub async fn update<T>(
        &self,
        f: impl FnOnce(&mut Document) -> Result<T, RpcError>,
    ) -> Result<T, RpcError> {
        let mut s = self.state.lock().await;
        if s.failed {
            return Err(locked());
        }
        let mut next = s.doc.clone();
        let result = f(&mut next)?;
        let seq = s
            .sequence
            .checked_add(1)
            .filter(|v| *v < i64::MAX as u64)
            .ok_or_else(locked)?;
        let blob = seal_document(&self.key, &self.device, seq, &next)?;
        // A failed guard/DB write permanently locks this instance; never transmit a
        // result from a transaction whose durable state is not confirmed.
        s.failed = true;
        let commit = async {
            let mut tx = self
                .pool
                .begin_with("BEGIN IMMEDIATE")
                .await
                .map_err(|_| locked())?;
            let mut f =
                self.root
                    .open_owned_write(GUARD_FILE, false, Some(&self.guard_identity))?;
            f.file
                .seek(SeekFrom::Start(0))
                .and_then(|_| f.file.write_all(&guard_bytes(seq, &blob)))
                .and_then(|_| f.file.set_len(72))
                .and_then(|_| f.file.sync_all())
                .map_err(|_| locked())?;
            let changed = sqlx::query(
                "UPDATE protected_state SET sequence=?,sealed=? WHERE id=1 AND sequence=?",
            )
            .bind(seq as i64)
            .bind(blob)
            .bind(s.sequence as i64)
            .execute(&mut *tx)
            .await
            .map_err(|_| locked())?
            .rows_affected();
            if changed != 1 {
                return Err(locked());
            }
            tx.commit().await.map_err(|_| locked())?;
            Ok::<(), RpcError>(())
        }
        .await;
        if let Err(e) = commit {
            s.failed = true;
            return Err(e);
        }
        s.doc = next;
        s.sequence = seq;
        s.failed = false;
        Ok(result)
    }
    pub async fn close(&self) {
        self.pool.close().await;
    }
}
fn guard_bytes(sequence: u64, blob: &[u8]) -> Vec<u8> {
    let mut out = sequence.to_be_bytes().to_vec();
    out.extend(crate::identity::fingerprint(blob).as_bytes());
    out
}
fn sync_directory(root: &SafeRoot) -> Result<(), RpcError> {
    #[cfg(unix)]
    root.file.sync_all().map_err(|_| locked())?;
    #[cfg(not(unix))]
    let _ = root;
    Ok(())
}
fn aad(device: &str, seq: u64) -> Vec<u8> {
    let mut v = b"NARADA-SECURE-STATE-1\0".to_vec();
    v.extend(device.as_bytes());
    v.extend(seq.to_be_bytes());
    v
}
fn seal_document(key: &[u8], device: &str, seq: u64, doc: &Document) -> Result<Vec<u8>, RpcError> {
    let mut bytes = serde_json::to_vec(doc).map_err(|_| locked())?;
    if bytes.len() > STATE_LIMIT {
        return Err(err(
            "CONTENT_QUOTA",
            "The protected-state quota is full. No data was discarded.",
        ));
    }
    let nonce = crate::identity::random::<12>()?;
    let cipher = aead::LessSafeKey::new(
        aead::UnboundKey::new(&aead::AES_256_GCM, key).map_err(|_| locked())?,
    );
    cipher
        .seal_in_place_append_tag(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(aad(device, seq)),
            &mut bytes,
        )
        .map_err(|_| locked())?;
    let mut result = nonce.to_vec();
    result.extend(bytes);
    Ok(result)
}
fn open_document(key: &[u8], device: &str, seq: u64, blob: Vec<u8>) -> Result<Document, RpcError> {
    if blob.len() < 28 {
        return Err(locked());
    }
    let nonce: [u8; 12] = blob[..12].try_into().map_err(|_| locked())?;
    let mut bytes = Zeroizing::new(blob[12..].to_vec());
    let cipher = aead::LessSafeKey::new(
        aead::UnboundKey::new(&aead::AES_256_GCM, key).map_err(|_| locked())?,
    );
    let plain = cipher
        .open_in_place(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(aad(device, seq)),
            &mut bytes,
        )
        .map_err(|_| locked())?;
    serde_json::from_slice(plain).map_err(|_| locked())
}
pub(super) fn locked() -> RpcError {
    err("CONTENT_STORE_LOCKED","The protected content store or its durable guard cannot be verified. Existing keys and messages were not reset. Restore matching original state; complete-profile rollback is unsupported.")
}

#[cfg(all(test, any(windows, feature = "dev-vault")))]
mod tests {
    use super::*;
    #[tokio::test]
    async fn cancellation_during_commit_admission_locks_live_ratchet_state() {
        let base = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .join("test-fixtures");
        std::fs::create_dir_all(&base).unwrap();
        let temp = tempfile::Builder::new()
            .prefix("vault-cancel-")
            .tempdir_in(base)
            .unwrap();
        let device = "a".repeat(64);
        let vault = Arc::new(Vault::open(temp.path(), &device, true).await.unwrap());
        let blocker = vault.pool.begin_with("BEGIN IMMEDIATE").await.unwrap();
        let (started, wait) = tokio::sync::oneshot::channel();
        let v = vault.clone();
        let task = tokio::spawn(async move {
            v.update(|d| {
                let _ = started.send(());
                d.drafts.insert("b".repeat(64), "Cancelled fixture".into());
                Ok(())
            })
            .await
        });
        wait.await.unwrap();
        task.abort();
        let _ = task.await;
        blocker.rollback().await.unwrap();
        assert_eq!(
            vault.read(|_| Ok(())).await.unwrap_err().code,
            "CONTENT_STORE_LOCKED"
        );
        vault.close().await;
        drop(vault);
        let reopened = Vault::open(temp.path(), &device, true).await.unwrap();
        assert!(reopened.read(|d| Ok(d.drafts.is_empty())).await.unwrap());
        reopened.close().await;
        drop(reopened);
        temp.close().unwrap();
    }
}
