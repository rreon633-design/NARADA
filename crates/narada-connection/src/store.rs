use crate::identity::{err, Identity};
use chrono::Utc;
use narada_core::Core;
use narada_protocol::*;
use sqlx::{Row, SqlitePool};

#[derive(Clone)]
pub struct Store {
    pub pool: SqlitePool,
}
#[derive(Clone)]
pub struct Peer {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub cert: Vec<u8>,
    pub fingerprint: String,
    pub state: String,
    pub direction: String,
    pub endpoint: Option<String>,
    pub permissions: PeerPermissions,
    pub remote_permissions: PeerPermissions,
    pub revision: u32,
    pub epoch: u32,
    pub attempt_id: String,
    pub invitation_id: String,
    pub paired_at: String,
    pub last_seen: Option<String>,
}
fn decode(r: sqlx::sqlite::SqliteRow) -> Result<Peer, RpcError> {
    let permissions: PeerPermissions = serde_json::from_str(
        r.try_get::<&str, _>("permissions")
            .map_err(|_| RpcError::storage())?,
    )
    .map_err(|_| RpcError::storage())?;
    permissions.validate()?;
    let remote_permissions: PeerPermissions = serde_json::from_str(
        r.try_get::<&str, _>("remote_permissions")
            .map_err(|_| RpcError::storage())?,
    )
    .map_err(|_| RpcError::storage())?;
    remote_permissions.validate()?;
    Ok(Peer {
        id: r.get("peer_id"),
        name: r.get("name"),
        platform: r.get("platform"),
        cert: r.get("certificate"),
        fingerprint: r.get("fingerprint"),
        state: r.get("state"),
        direction: r.get("direction"),
        endpoint: r.get("endpoint"),
        permissions,
        remote_permissions,
        revision: r.get::<i64, _>("grant_revision") as u32,
        epoch: r.get::<i64, _>("session_epoch") as u32,
        attempt_id: r.get("attempt_id"),
        invitation_id: r.get("invitation_id"),
        paired_at: r.get("paired_at"),
        last_seen: r.get("last_seen"),
    })
}
impl Store {
    pub fn new(core: Core) -> Self {
        Self {
            pool: core.metadata_pool(),
        }
    }
    pub async fn identity_marker(&self) -> Result<Option<(String, String)>, RpcError> {
        let r = sqlx::query("SELECT device_id,fingerprint FROM local_identity WHERE id=1")
            .fetch_optional(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        Ok(r.map(|r| (r.get("device_id"), r.get("fingerprint"))))
    }
    pub async fn bind_identity(&self, id: &Identity) -> Result<(), RpcError> {
        sqlx::query("INSERT OR IGNORE INTO local_identity(id,device_id,fingerprint,created_at) VALUES(1,?,?,?)").bind(&id.device_id).bind(&id.fingerprint).bind(Utc::now().to_rfc3339()).execute(&self.pool).await.map_err(|_|RpcError::storage())?;
        if self.identity_marker().await? != Some((id.device_id.clone(), id.fingerprint.clone())) {
            return Err(err(
                "IDENTITY_CHANGED",
                "Identity metadata does not match the protected key.",
            ));
        }
        Ok(())
    }
    fn invalid_config() -> RpcError {
        err(
            "INVALID_CONNECTION_CONFIG",
            "Saved connection policy is invalid. It has not been reset.",
        )
    }
    async fn saved_config(&self) -> Result<(ConnectionConfig, Option<String>), RpcError> {
        let text: String = sqlx::query_scalar("SELECT document FROM connection_config WHERE id=1")
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        let mut document: serde_json::Value =
            serde_json::from_str(&text).map_err(|_| Self::invalid_config())?;
        // A local PUBLIC fingerprint, not message/session/key material. This
        // internal field is never an owner-writable ConnectionConfig field.
        let marker = document
            .as_object_mut()
            .ok_or_else(Self::invalid_config)?
            .remove("content_identity");
        let marker = match marker {
            None | Some(serde_json::Value::Null) => None,
            Some(serde_json::Value::String(value))
                if narada_protocol::transfer_hash(&value).is_ok() =>
            {
                Some(value)
            }
            _ => {
                return Err(err(
                    "CONTENT_STORE_LOCKED",
                    "The private-content identity marker is invalid; it was not reset.",
                ));
            }
        };
        let config = serde_json::from_value(document).map_err(|_| Self::invalid_config())?;
        Ok((config, marker))
    }
    pub async fn config(&self) -> Result<ConnectionConfig, RpcError> {
        self.saved_config().await.map(|(config, _)| config)
    }
    pub async fn content_identity_marker(&self) -> Result<Option<String>, RpcError> {
        self.saved_config().await.map(|(_, marker)| marker)
    }
    pub async fn bind_content_identity(&self, fingerprint: &str) -> Result<(), RpcError> {
        narada_protocol::transfer_hash(fingerprint)?;
        let changed = sqlx::query(
            "UPDATE connection_config SET document=json_set(document,'$.content_identity',?) WHERE id=1 AND (json_extract(document,'$.content_identity') IS NULL OR json_extract(document,'$.content_identity')=?)",
        )
        .bind(fingerprint)
        .bind(fingerprint)
        .execute(&self.pool)
        .await
        .map_err(|_| RpcError::storage())?
        .rows_affected();
        if changed != 1 {
            return Err(err(
                "CONTENT_STORE_LOCKED",
                "The private-content identity does not match this workspace; it was not replaced.",
            ));
        }
        Ok(())
    }
    pub async fn save_config(&self, c: &ConnectionConfig) -> Result<(), RpcError> {
        // Preserve the independently recorded identity even when network
        // settings are changed. An owner DTO cannot clear/replace this field.
        sqlx::query("UPDATE connection_config SET document=json_set(?,'$.content_identity',json_extract(document,'$.content_identity')) WHERE id=1")
            .bind(serde_json::to_string(c).map_err(|_| RpcError::storage())?)
            .execute(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn peers(&self) -> Result<Vec<Peer>, RpcError> {
        sqlx::query("SELECT p.* FROM trusted_peers p LEFT JOIN revoked_certificates r ON r.fingerprint=p.fingerprint ORDER BY CASE WHEN p.state='revoked' THEN 1 ELSE 0 END, COALESCE(r.revoked_at,p.paired_at) DESC LIMIT 32").fetch_all(&self.pool).await.map_err(|_|RpcError::storage())?.into_iter().map(decode).collect()
    }
    pub async fn peer(&self, id: &str) -> Result<Peer, RpcError> {
        validate_id(id)?;
        let r = sqlx::query("SELECT * FROM trusted_peers WHERE peer_id=?")
            .bind(id)
            .fetch_optional(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?
            .ok_or_else(|| {
                err(
                    "PEER_NOT_FOUND",
                    "This peer is not in the local trust store.",
                )
            })?;
        decode(r)
    }
    pub async fn by_fingerprint(&self, fp: &str) -> Result<Option<Peer>, RpcError> {
        sqlx::query("SELECT * FROM trusted_peers WHERE fingerprint=?")
            .bind(fp)
            .fetch_optional(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?
            .map(decode)
            .transpose()
    }
    pub async fn validate_grants(&self, p: &PeerPermissions) -> Result<(), RpcError> {
        p.validate()?;
        for root in &p.roots {
            let found: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM authorization_scopes WHERE root_id=? AND active=1)",
            )
            .bind(&root.root_id)
            .fetch_one(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
            if !found {
                return Err(err("ROOT_NOT_AVAILABLE","Only locally registered, active roots can receive grants. File roots arrive in Phase 3."));
            }
        }
        Ok(())
    }
    pub async fn put_peer(&self, p: &Peer) -> Result<(), RpcError> {
        self.validate_grants(&p.permissions).await?;
        let mut tx = self
            .pool
            .begin_with("BEGIN IMMEDIATE")
            .await
            .map_err(|_| RpcError::storage())?;
        let count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM trusted_peers WHERE state!='revoked' AND peer_id!=?",
        )
        .bind(&p.id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| RpcError::storage())?;
        if count >= MAX_PEERS as i64 {
            return Err(err(
                "PEER_LIMIT",
                "The local trusted-device limit has been reached.",
            ));
        }
        let cancelled: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM cancelled_pairing_attempts WHERE attempt_id=?)",
        )
        .bind(&p.attempt_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| RpcError::storage())?;
        if cancelled {
            return Err(err(
                "PAIRING_CANCELLED",
                "The owner cancelled this attempt. Late completion is blocked.",
            ));
        }
        sqlx::query("INSERT INTO trusted_peers(peer_id,name,platform,certificate,fingerprint,state,direction,endpoint,permissions,remote_permissions,grant_revision,session_epoch,attempt_id,invitation_id,paired_at,last_seen) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(peer_id) DO UPDATE SET name=excluded.name,platform=excluded.platform,certificate=excluded.certificate,fingerprint=excluded.fingerprint,state=excluded.state,direction=excluded.direction,endpoint=excluded.endpoint,permissions=excluded.permissions,remote_permissions=excluded.remote_permissions,grant_revision=trusted_peers.grant_revision+1,session_epoch=trusted_peers.session_epoch+1,attempt_id=excluded.attempt_id,invitation_id=excluded.invitation_id,paired_at=excluded.paired_at,last_seen=NULL")
   .bind(&p.id).bind(&p.name).bind(&p.platform).bind(&p.cert).bind(&p.fingerprint).bind(&p.state).bind(&p.direction).bind(&p.endpoint).bind(serde_json::to_string(&p.permissions).map_err(|_|RpcError::storage())?).bind(serde_json::to_string(&p.remote_permissions).map_err(|_|RpcError::storage())?).bind(i64::from(p.revision)).bind(i64::from(p.epoch)).bind(&p.attempt_id).bind(&p.invitation_id).bind(&p.paired_at).bind(&p.last_seen).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        sqlx::query("DELETE FROM revoked_certificates WHERE fingerprint=?")
            .bind(&p.fingerprint)
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn activate_outbound(
        &self,
        id: &str,
        attempt: &str,
        permissions: &PeerPermissions,
    ) -> Result<(), RpcError> {
        let result=sqlx::query("UPDATE trusted_peers SET state='active',remote_permissions=? WHERE peer_id=? AND attempt_id=? AND direction='outbound' AND state='pending' AND NOT EXISTS(SELECT 1 FROM cancelled_pairing_attempts c WHERE c.attempt_id=trusted_peers.attempt_id)").bind(serde_json::to_string(permissions).map_err(|_|RpcError::storage())?).bind(id).bind(attempt).execute(&self.pool).await.map_err(|_|RpcError::storage())?;
        if result.rows_affected() != 1 {
            return Err(err(
                "PAIRING_STATE_CHANGED",
                "The joining attempt is no longer pending.",
            ));
        }
        Ok(())
    }
    pub async fn cancel_attempt(&self, attempt: &str) -> Result<(), RpcError> {
        let mut tx = self.pool.begin().await.map_err(|_| RpcError::storage())?;
        sqlx::query("INSERT OR REPLACE INTO cancelled_pairing_attempts VALUES(?,?)")
            .bind(attempt)
            .bind(Utc::now().to_rfc3339())
            .execute(&mut *tx)
            .await
            .map_err(|_| RpcError::storage())?;
        sqlx::query("UPDATE trusted_peers SET state='revoked',grant_revision=grant_revision+1,session_epoch=session_epoch+1 WHERE attempt_id=? AND direction='outbound' AND state!='revoked'").bind(attempt).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        sqlx::query("DELETE FROM cancelled_pairing_attempts WHERE attempt_id NOT IN (SELECT attempt_id FROM cancelled_pairing_attempts ORDER BY cancelled_at DESC LIMIT 128)").execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn remote_permissions(
        &self,
        id: &str,
        permissions: &PeerPermissions,
    ) -> Result<(), RpcError> {
        permissions.validate()?;
        sqlx::query(
            "UPDATE trusted_peers SET remote_permissions=? WHERE peer_id=? AND state='active'",
        )
        .bind(serde_json::to_string(permissions).map_err(|_| RpcError::storage())?)
        .bind(id)
        .execute(&self.pool)
        .await
        .map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn touch(&self, id: &str) -> Result<(), RpcError> {
        sqlx::query("UPDATE trusted_peers SET last_seen=? WHERE peer_id=? AND state='active'")
            .bind(Utc::now().to_rfc3339())
            .bind(id)
            .execute(&self.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn endpoint(&self, id: &str, endpoint: &str) -> Result<(), RpcError> {
        sqlx::query("UPDATE trusted_peers SET endpoint=? WHERE peer_id=? AND direction='outbound' AND state='active'").bind(endpoint).bind(id).execute(&self.pool).await.map_err(|_|RpcError::storage())?;
        Ok(())
    }
    pub async fn revoke(&self, id: &str, revision: u32) -> Result<(), RpcError> {
        let p = self.peer(id).await?;
        if p.revision != revision {
            return Err(err(
                "REVISION_CONFLICT",
                "Permissions changed. Refresh before revoking this device.",
            ));
        }
        let mut tx = self.pool.begin().await.map_err(|_| RpcError::storage())?;
        let n=sqlx::query("UPDATE trusted_peers SET state='revoked',permissions=?,remote_permissions=?,grant_revision=grant_revision+1,session_epoch=session_epoch+1,last_seen=NULL WHERE peer_id=? AND grant_revision=?").bind(serde_json::to_string(&PeerPermissions::default()).map_err(|_|RpcError::storage())?).bind(serde_json::to_string(&PeerPermissions::default()).map_err(|_|RpcError::storage())?).bind(id).bind(i64::from(revision)).execute(&mut *tx).await.map_err(|_|RpcError::storage())?.rows_affected();
        if n != 1 {
            return Err(err("REVISION_CONFLICT", "The device state changed."));
        }
        sqlx::query(
            "INSERT OR REPLACE INTO revoked_certificates(fingerprint,revoked_at) VALUES(?,?)",
        )
        .bind(&p.fingerprint)
        .bind(Utc::now().to_rfc3339())
        .execute(&mut *tx)
        .await
        .map_err(|_| RpcError::storage())?;
        sqlx::query("DELETE FROM revoked_certificates WHERE fingerprint NOT IN (SELECT fingerprint FROM revoked_certificates ORDER BY revoked_at DESC LIMIT 128)").execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(())
    }
    pub async fn update_permissions(
        &self,
        id: &str,
        revision: u32,
        perms: &PeerPermissions,
    ) -> Result<(), RpcError> {
        self.validate_grants(perms).await?;
        let n=sqlx::query("UPDATE trusted_peers SET permissions=?,grant_revision=grant_revision+1,session_epoch=session_epoch+1 WHERE peer_id=? AND grant_revision=? AND state='active'").bind(serde_json::to_string(perms).map_err(|_|RpcError::storage())?).bind(id).bind(i64::from(revision)).execute(&self.pool).await.map_err(|_|RpcError::storage())?.rows_affected();
        if n != 1 {
            return Err(err(
                "REVISION_CONFLICT",
                "The peer is revoked or its permissions changed. Refresh before saving.",
            ));
        }
        Ok(())
    }
    pub async fn authorize_session(
        &self,
        id: &str,
        revision: u32,
        epoch: u32,
    ) -> Result<Peer, RpcError> {
        let p = self.peer(id).await?;
        if p.state != "active" {
            return Err(err("DEVICE_REVOKED", "This device is not active."));
        }
        if p.revision != revision || p.epoch != epoch {
            return Err(err(
                "SESSION_INVALIDATED",
                "The connection's authorization epoch is no longer current.",
            ));
        }
        Ok(p)
    }
    pub async fn authorize(
        &self,
        id: &str,
        revision: u32,
        epoch: u32,
        cap: Capability,
        root: Option<&str>,
    ) -> Result<(), RpcError> {
        let p = self.authorize_session(id, revision, epoch).await?;
        let allow = match cap {
            Capability::Chat => root.is_none() && p.permissions.chat,
            Capability::Attachment => root.is_none() && p.permissions.attachment,
            _ => {
                if let Some(root) = root {
                    let active:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM authorization_scopes WHERE root_id=? AND active=1)").bind(root).fetch_one(&self.pool).await.map_err(|_|RpcError::storage())?;
                    active
                        && p.permissions
                            .roots
                            .iter()
                            .any(|r| r.root_id == root && r.capabilities.contains(&cap))
                } else {
                    false
                }
            }
        };
        if allow {
            Ok(())
        } else {
            Err(err(
                "PERMISSION_DENIED",
                "This device does not have that capability for this scope.",
            ))
        }
    }
}
pub fn validate_id(id: &str) -> Result<(), RpcError> {
    if id.len() != 64
        || !id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        Err(err(
            "INVALID_PEER",
            "A valid pinned device identifier is required.",
        ))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    async fn fixture() -> (tempfile::TempDir, Store, Peer) {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::open(dir.path()).await.unwrap();
        let store = Store::new(core);
        let p = peer();
        store.put_peer(&p).await.unwrap();
        (dir, store, p)
    }
    fn peer() -> Peer {
        let id = Identity::generate("fixture".into()).unwrap();
        Peer {
            id: id.device_id,
            name: "Disposable reference".into(),
            platform: "linux".into(),
            cert: id.certificate,
            fingerprint: id.fingerprint,
            state: "active".into(),
            direction: "inbound".into(),
            endpoint: None,
            permissions: PeerPermissions::default(),
            remote_permissions: PeerPermissions::default(),
            revision: 1,
            epoch: 1,
            attempt_id: uuid::Uuid::new_v4().to_string(),
            invitation_id: uuid::Uuid::new_v4().to_string(),
            paired_at: Utc::now().to_rfc3339(),
            last_seen: None,
        }
    }
    #[tokio::test]
    async fn content_identity_marker_is_sticky_and_not_a_config_input() {
        // Post-pass regression source; not part of the recorded 133 Rust passes.
        let (_dir, store, _peer) = fixture().await;
        assert!(store.content_identity_marker().await.unwrap().is_none());
        let fingerprint = "a".repeat(64);
        store.bind_content_identity(&fingerprint).await.unwrap();
        let mut config = store.config().await.unwrap();
        config.api_port = 12345;
        store.save_config(&config).await.unwrap();
        assert_eq!(store.config().await.unwrap().api_port, 12345);
        assert_eq!(
            store.content_identity_marker().await.unwrap(),
            Some(fingerprint.clone())
        );
        assert_eq!(
            store.bind_content_identity(&"b".repeat(64)).await.unwrap_err().code,
            "CONTENT_STORE_LOCKED"
        );
        assert_eq!(
            store.content_identity_marker().await.unwrap(),
            Some(fingerprint)
        );
        let mut wire = serde_json::to_value(&config).unwrap();
        wire["content_identity"] = serde_json::Value::Null;
        assert!(serde_json::from_value::<ConnectionConfig>(wire).is_err());
        store.pool.close().await;
    }
    #[tokio::test]
    async fn paired_identity_has_no_implicit_content_access() {
        let (_d, s, p) = fixture().await;
        for c in [
            Capability::Browse,
            Capability::Read,
            Capability::Create,
            Capability::Modify,
            Capability::Trash,
            Capability::SyncRead,
            Capability::Chat,
            Capability::Attachment,
        ] {
            assert_eq!(
                s.authorize(&p.id, 1, 1, c, None).await.unwrap_err().code,
                "PERMISSION_DENIED"
            );
        }
    }
    #[tokio::test]
    async fn registered_root_grants_are_per_device_and_per_capability() {
        let (_d, s, p) = fixture().await;
        let root = uuid::Uuid::new_v4().to_string();
        let other = uuid::Uuid::new_v4().to_string();
        sqlx::query("INSERT INTO authorization_scopes(root_id) VALUES(?)")
            .bind(&root)
            .execute(&s.pool)
            .await
            .unwrap();
        let grants = PeerPermissions {
            roots: vec![RootGrant {
                root_id: root.clone(),
                capabilities: vec![Capability::Read],
            }],
            ..Default::default()
        };
        s.update_permissions(&p.id, 1, &grants).await.unwrap();
        assert!(s
            .authorize(&p.id, 2, 2, Capability::Read, Some(&root))
            .await
            .is_ok());
        assert!(s
            .authorize(&p.id, 2, 2, Capability::Modify, Some(&root))
            .await
            .is_err());
        assert!(s
            .authorize(&p.id, 2, 2, Capability::Read, Some(&other))
            .await
            .is_err());
        let b = peer();
        s.put_peer(&b).await.unwrap();
        assert!(s
            .authorize(&b.id, 1, 1, Capability::Read, Some(&root))
            .await
            .is_err());
    }
    #[tokio::test]
    async fn grant_changes_invalidate_prior_session_epochs() {
        let (_d, s, p) = fixture().await;
        s.update_permissions(
            &p.id,
            1,
            &PeerPermissions {
                chat: true,
                ..Default::default()
            },
        )
        .await
        .unwrap();
        assert_eq!(
            s.authorize_session(&p.id, 1, 1).await.err().unwrap().code,
            "SESSION_INVALIDATED"
        );
        assert!(s
            .authorize(&p.id, 2, 2, Capability::Chat, None)
            .await
            .is_ok());
        assert!(s
            .authorize(&p.id, 2, 2, Capability::Attachment, None)
            .await
            .is_err());
        assert!(s
            .authorize(
                &p.id,
                2,
                2,
                Capability::Chat,
                Some(&uuid::Uuid::new_v4().to_string())
            )
            .await
            .is_err());
    }
    #[tokio::test]
    async fn stale_owner_revisions_cannot_overwrite_new_grants() {
        let (_d, s, p) = fixture().await;
        s.update_permissions(&p.id, 1, &PeerPermissions::default())
            .await
            .unwrap();
        assert_eq!(
            s.update_permissions(
                &p.id,
                1,
                &PeerPermissions {
                    chat: true,
                    ..Default::default()
                }
            )
            .await
            .unwrap_err()
            .code,
            "REVISION_CONFLICT"
        );
        assert!(!s.peer(&p.id).await.unwrap().permissions.chat);
        assert!(s.revoke(&p.id, 1).await.is_err());
    }
    #[tokio::test]
    async fn nonexistent_roots_cannot_be_granted() {
        let (_d, s, p) = fixture().await;
        let g = PeerPermissions {
            roots: vec![RootGrant {
                root_id: uuid::Uuid::new_v4().to_string(),
                capabilities: vec![Capability::Browse],
            }],
            ..Default::default()
        };
        assert_eq!(
            s.update_permissions(&p.id, 1, &g).await.unwrap_err().code,
            "ROOT_NOT_AVAILABLE"
        );
    }
    #[tokio::test]
    async fn withdrawn_root_is_denied_even_with_a_current_device_epoch() {
        let (_d, s, p) = fixture().await;
        let root = uuid::Uuid::new_v4().to_string();
        sqlx::query("INSERT INTO authorization_scopes(root_id) VALUES(?)")
            .bind(&root)
            .execute(&s.pool)
            .await
            .unwrap();
        s.update_permissions(
            &p.id,
            1,
            &PeerPermissions {
                roots: vec![RootGrant {
                    root_id: root.clone(),
                    capabilities: vec![Capability::Read],
                }],
                ..Default::default()
            },
        )
        .await
        .unwrap();
        sqlx::query("UPDATE authorization_scopes SET active=0 WHERE root_id=?")
            .bind(&root)
            .execute(&s.pool)
            .await
            .unwrap();
        assert_eq!(
            s.authorize(&p.id, 2, 2, Capability::Read, Some(&root))
                .await
                .unwrap_err()
                .code,
            "PERMISSION_DENIED"
        );
    }
    #[tokio::test]
    async fn revocation_is_durable_and_denies_even_new_epoch_requests() {
        let (d, s, p) = fixture().await;
        s.revoke(&p.id, 1).await.unwrap();
        s.pool.close().await;
        let core = Core::open(d.path()).await.unwrap();
        let s = Store::new(core);
        let stored = s.peer(&p.id).await.unwrap();
        assert_eq!(stored.state, "revoked");
        assert_eq!(stored.epoch, 2);
        assert_eq!(
            s.authorize_session(&p.id, 2, 2).await.err().unwrap().code,
            "DEVICE_REVOKED"
        );
    }
    #[tokio::test]
    async fn fresh_explicit_repair_never_resets_epochs() {
        let (_d, s, p) = fixture().await;
        s.revoke(&p.id, 1).await.unwrap();
        s.put_peer(&p).await.unwrap();
        let stored = s.peer(&p.id).await.unwrap();
        assert_eq!(stored.epoch, 3);
        assert_eq!(stored.revision, 3);
        assert_eq!(stored.state, "active");
        assert!(s.authorize_session(&p.id, 1, 1).await.is_err());
    }
    #[tokio::test]
    async fn cancellation_tombstone_prevents_late_trust_publication() {
        let (_d, s, p) = fixture().await;
        s.cancel_attempt(&p.attempt_id).await.unwrap();
        assert_eq!(s.put_peer(&p).await.unwrap_err().code, "PAIRING_CANCELLED");
    }
    #[tokio::test]
    async fn local_and_remote_grants_are_not_confused() {
        let (_d, s, p) = fixture().await;
        s.remote_permissions(
            &p.id,
            &PeerPermissions {
                chat: true,
                attachment: true,
                roots: vec![],
            },
        )
        .await
        .unwrap();
        assert_eq!(
            s.peer(&p.id).await.unwrap().permissions,
            PeerPermissions::default()
        );
        assert!(s
            .authorize(&p.id, 1, 1, Capability::Chat, None)
            .await
            .is_err());
    }
    #[test]
    fn ids_cannot_be_paths_queries_or_unknown_identifiers() {
        for value in ["../private", "' OR 1=1 --", "", "admin"] {
            assert!(validate_id(value).is_err());
        }
    }
    #[tokio::test]
    async fn concurrent_pairing_cannot_exceed_the_device_limit() {
        let (_d, s, _) = fixture().await;
        for _ in 0..MAX_PEERS - 2 {
            s.put_peer(&peer()).await.unwrap();
        }
        let a = peer();
        let b = peer();
        let (x, y) = tokio::join!(s.put_peer(&a), s.put_peer(&b));
        assert_eq!(usize::from(x.is_ok()) + usize::from(y.is_ok()), 1);
        assert_eq!(
            s.peers()
                .await
                .unwrap()
                .iter()
                .filter(|p| p.state == "active")
                .count(),
            MAX_PEERS
        );
    }
    #[tokio::test]
    async fn old_revocation_tombstones_preserve_epochs_after_history_rotates() {
        let (_d, s, p) = fixture().await;
        s.revoke(&p.id, 1).await.unwrap();
        for _ in 0..20 {
            let next = peer();
            s.put_peer(&next).await.unwrap();
            s.revoke(&next.id, 1).await.unwrap();
        }
        assert_eq!(s.peer(&p.id).await.unwrap().epoch, 2);
        s.put_peer(&p).await.unwrap();
        assert_eq!(s.peer(&p.id).await.unwrap().epoch, 3);
    }
}
