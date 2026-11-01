use super::*;
use chrono::{Duration as ChronoDuration, Utc};
#[derive(Clone)]
struct Intent {
    actor: String,
    id: String,
    root: String,
    action: String,
    source: String,
    destination: String,
    identity: String,
    object: String,
    expected: String,
    created: String,
}
impl Files {
    pub async fn mutate(
        &self,
        input: FileMutation,
        access: Access,
        guard: OwnedRwLockReadGuard<()>,
    ) -> Result<Value, RpcError> {
        let permit = self
            .0
            .jobs
            .clone()
            .try_acquire_owned()
            .map_err(|_| err("FILE_BUSY", "The bounded file worker queue is full."))?;
        let me = self.clone();
        // Dropping an IPC/TLS waiter does not abandon a committed intent or release its policy lease.
        tokio::spawn(async move{let _permit=permit;let _guard=guard;let _io=me.0.io.lock().await;input.validate()?;bounded(me.mutate_locked(input,&access).await?)}).await.map_err(|_|err("FILE_WORK_FAILED","The file worker stopped. Refresh the catalog and retry the same operation identifier."))?
    }
    async fn mutate_locked(&self, input: FileMutation, a: &Access) -> Result<Value, RpcError> {
        let r = self.root(&input.root_id).await?;
        let cap = match &input.action {
            MutationAction::Rename { .. } => Capability::Modify,
            MutationAction::Trash {} | MutationAction::Restore { .. } => Capability::Trash,
        };
        a.require(&r.id, cap)?;
        if matches!(input.action, MutationAction::Restore { .. }) {
            a.require(&r.id, Capability::Create)?;
        }
        let digest = catalog::signature_query(&input);
        if let Some(row)=sqlx::query("SELECT request_hash,state,result,error_code,source_path,destination_path,action FROM file_operations WHERE actor=? AND operation_id=?").bind(a.actor()).bind(&input.operation_id).fetch_optional(&self.0.pool).await.map_err(|_|RpcError::storage())?{
   if row.get::<&str,_>("request_hash")!=digest{return Err(err("IDEMPOTENCY_CONFLICT","This operation identifier was already used for different arguments."));}
   let action=row.get::<&str,_>("action");
   if (action!="restore"&&policy::excluded(row.get::<&str,_>("source_path"),&r.exclusions))||(action!="trash"&&policy::excluded(row.get::<&str,_>("destination_path"),&r.exclusions)){return Err(err("ACCESS_DENIED","The saved operation is outside the current root exclusions policy."));}
   if row.get::<&str,_>("state")=="committed"{return serde_json::from_str(row.get::<&str,_>("result")).map_err(|_|RpcError::storage());}
   return Err(err(row.get::<Option<&str>,_>("error_code").unwrap_or("OPERATION_INDETERMINATE"),"This saved operation is not a confirmed success. Refresh the root and inspect its recovery state; no replacement action is automatic."));
  }
        let pending:i64=sqlx::query_scalar("SELECT COUNT(*) FROM file_operations WHERE root_id=? AND state IN ('prepared','recovery_required')").bind(&r.id).fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        if pending > 0 {
            return Err(err("OPERATION_INDETERMINATE","This root has an unresolved operation. Preserve its files; further mutations are blocked until recovery is resolved."));
        }
        self.transfer_publication_barrier(&r.id, None).await?;
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM file_operations WHERE root_id=?")
            .bind(&r.id)
            .fetch_one(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        if count >= 5000 {
            return Err(err("OPERATION_HISTORY_LIMIT","The 5,000-operation history quota was reached. Receipts were not discarded; no further mutation was performed."));
        }
        let (source, identity, destination, object, action) = match &input.action {
            MutationAction::Rename { name } => {
                catalog::new_name(name)?;
                let e = self.entry(&r.id, &input.object_id).await?;
                let target = join(&e.parent_path, name);
                (
                    e.relative_path,
                    e.info.identity,
                    target,
                    e.object_id,
                    "rename",
                )
            }
            MutationAction::Trash {} => {
                let e = self.entry(&r.id, &input.object_id).await?;
                let row=sqlx::query("SELECT COUNT(*) AS n,COALESCE(SUM(size),0) AS bytes FROM file_trash WHERE root_id=? AND state='retained'").bind(&r.id).fetch_one(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                if row.get::<i64, _>("n") >= 200
                    || row.get::<i64, _>("bytes") as u64 + e.info.size > 100 * 1024 * 1024
                {
                    return Err(err("TRASH_QUOTA","Managed trash is limited to 200 items / 100 MiB per root. Restore retained items before adding more; nothing was purged."));
                }
                let rr = r.clone();
                let tid = blocking(move || {
                    let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
                    root.ensure_trash(rr.trash_identity.as_deref())
                })
                .await?;
                if r.trash_identity.is_none() {
                    sqlx::query("UPDATE file_roots SET trash_identity=? WHERE root_id=?")
                        .bind(tid)
                        .bind(&r.id)
                        .execute(&self.0.pool)
                        .await
                        .map_err(|_| RpcError::storage())?;
                }
                (
                    e.relative_path,
                    e.info.identity,
                    format!(".narada-trash/{}", input.operation_id),
                    e.object_id,
                    "trash",
                )
            }
            MutationAction::Restore { name } => {
                let row = sqlx::query(
                    "SELECT * FROM file_trash WHERE root_id=? AND trash_id=? AND state='retained'",
                )
                .bind(&r.id)
                .bind(&input.object_id)
                .fetch_optional(&self.0.pool)
                .await
                .map_err(|_| RpcError::storage())?
                .ok_or_else(|| {
                    err(
                        "TRASH_UNAVAILABLE",
                        "This retained object is no longer available.",
                    )
                })?;
                let original: String = row.get("original_path");
                let (parent, old_name) =
                    original.rsplit_once('/').unwrap_or(("", original.as_str()));
                let name = name.as_deref().unwrap_or(old_name);
                catalog::new_name(name)?;
                (
                    row.get("stored_path"),
                    row.get("identity"),
                    join(parent, name),
                    row.get("object_id"),
                    "restore",
                )
            }
        };
        if source == destination {
            return Err(err(
                "NAME_COLLISION",
                "The source already has that name. No operation was performed.",
            ));
        }
        if (action != "restore" && policy::excluded(&source, &r.exclusions))
            || (action != "trash" && policy::excluded(&destination, &r.exclusions))
        {
            return Err(err(
                "ACCESS_DENIED",
                "This entry or destination is excluded by the current root policy.",
            ));
        }
        if action == "restore" {
            let rr = r.clone();
            blocking(move || {
                let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
                root.ensure_trash(rr.trash_identity.as_deref()).map(|_| ())
            })
            .await?;
        }
        let rr = r.clone();
        let src = source.clone();
        let expected = input.expected_version.clone();
        let ident = identity.clone();
        blocking(move||{let root=safe_fs::SafeRoot::open(&rr.path,Some(&rr.identity))?;let opened=root.open_relative(&src,false)?;let info=safe_fs::info(&opened.file)?;if info.hidden||info.identity!=ident||info.version!=expected{return Err(err("STALE_VERSION","The source changed. Refresh it before creating a new operation."));}
if info.kind=="directory"&&!root.names(&opened)?.is_empty(){return Err(err("DIRECTORY_NOT_EMPTY","Nonempty folders cannot be renamed or trashed in this slice. Move individual files instead."));}Ok(())}).await?;
        let intent = Intent {
            actor: a.actor().into(),
            id: input.operation_id,
            root: r.id.clone(),
            action: action.into(),
            source,
            destination,
            identity,
            object,
            expected: input.expected_version,
            created: Utc::now().to_rfc3339(),
        };
        sqlx::query("INSERT INTO file_operations(actor,operation_id,root_id,request_hash,action,source_path,destination_path,identity,object_id,expected_version,state,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,'prepared',?)").bind(&intent.actor).bind(&intent.id).bind(&intent.root).bind(digest).bind(&intent.action).bind(&intent.source).bind(&intent.destination).bind(&intent.identity).bind(&intent.object).bind(&intent.expected).bind(&intent.created).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let rr = r.clone();
        let ii = intent.clone();
        match blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            root.rename(&ii.source, &ii.destination, &ii.identity, &ii.expected)
        })
        .await
        {
            Ok(info) => {
                let value = self.finish(&intent, &info).await?;
                let _ = self.reindex_locked(&r.id).await;
                self.0.core.record("files","File operation committed","An authorized version-checked file operation was committed. Names and paths are omitted from this activity log.").await?;
                Ok(value)
            }
            Err(error) => {
                // A syscall/flush failure may occur after publication. Inspect both names; never retry blindly.
                match self.reconcile_intent(&intent, &r).await? {
                    Some(value) => Ok(value),
                    None => Err(error),
                }
            }
        }
    }
    async fn finish(&self, i: &Intent, info: &safe_fs::Info) -> Result<Value, RpcError> {
        let value = json!({"operation_id":i.id,"state":"committed","action":i.action,"object_id":i.object,"version":info.version,"trash_id":if i.action=="trash"{Some(&i.id)}else{None}});
        let mut tx = self.0.pool.begin().await.map_err(|_| RpcError::storage())?;
        if i.action == "trash" {
            let retain = chrono::DateTime::parse_from_rfc3339(&i.created)
                .map_err(|_| RpcError::storage())?
                + ChronoDuration::days(14);
            sqlx::query("INSERT OR IGNORE INTO file_trash(trash_id,root_id,object_id,original_path,stored_path,identity,version,kind,size,trashed_at,retain_until) VALUES (?,?,?,?,?,?,?,?,?,?,?)").bind(&i.id).bind(&i.root).bind(&i.object).bind(&i.source).bind(&i.destination).bind(&info.identity).bind(&info.version).bind(&info.kind).bind(info.size as i64).bind(&i.created).bind(retain.to_rfc3339()).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
            sqlx::query("DELETE FROM file_entries WHERE root_id=? AND object_id=?")
                .bind(&i.root)
                .bind(&i.object)
                .execute(&mut *tx)
                .await
                .map_err(|_| RpcError::storage())?;
        } else {
            if i.action == "restore" {
                sqlx::query("UPDATE file_trash SET state='restored' WHERE root_id=? AND stored_path=? AND state='retained'").bind(&i.root).bind(&i.source).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
            }
            sqlx::query("DELETE FROM file_entries WHERE root_id=? AND object_id=?")
                .bind(&i.root)
                .bind(&i.object)
                .execute(&mut *tx)
                .await
                .map_err(|_| RpcError::storage())?;
            let (parent, name) = i
                .destination
                .rsplit_once('/')
                .unwrap_or(("", &i.destination));
            insert_entry(
                &mut tx,
                &Entry {
                    object_id: i.object.clone(),
                    root_id: i.root.clone(),
                    relative_path: i.destination.clone(),
                    parent_path: parent.into(),
                    name: name.into(),
                    info: info.clone(),
                },
            )
            .await?;
        }
        sqlx::query("UPDATE file_operations SET state='committed',result=?,error_code=NULL WHERE actor=? AND operation_id=?").bind(serde_json::to_string(&value).map_err(|_|RpcError::storage())?).bind(&i.actor).bind(&i.id).execute(&mut *tx).await.map_err(|_|RpcError::storage())?;
        tx.commit().await.map_err(|_| RpcError::storage())?;
        Ok(value)
    }
    async fn reconcile_intent(&self, i: &Intent, r: &Root) -> Result<Option<Value>, RpcError> {
        let rr = r.clone();
        let ii = i.clone();
        let result = blocking(move || {
            let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
            Ok((root.info(&ii.source), root.info(&ii.destination)))
        })
        .await;
        if let Ok((source, target)) = &result {
            if source
                .as_ref()
                .is_err_and(|e| e.code == "OBJECT_UNAVAILABLE")
            {
                if let Ok(info) = target {
                    if info.identity == i.identity {
                        return self.finish(i, info).await.map(Some);
                    }
                }
            }
            if source
                .as_ref()
                .is_ok_and(|s| s.identity == i.identity && s.version == i.expected)
                && target
                    .as_ref()
                    .is_err_and(|e| e.code == "OBJECT_UNAVAILABLE")
            {
                sqlx::query("UPDATE file_operations SET state='failed',error_code='OPERATION_NOT_APPLIED' WHERE actor=? AND operation_id=?").bind(&i.actor).bind(&i.id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                return Ok(None);
            }
            // Existing unrelated destination and an intact source is also a safe, non-overwriting failure.
            if source
                .as_ref()
                .is_ok_and(|s| s.identity == i.identity && s.version == i.expected)
                && target.as_ref().is_ok_and(|t| t.identity != i.identity)
            {
                sqlx::query("UPDATE file_operations SET state='failed',error_code='NAME_COLLISION' WHERE actor=? AND operation_id=?").bind(&i.actor).bind(&i.id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
                return Ok(None);
            }
        }
        sqlx::query("UPDATE file_operations SET state='recovery_required',error_code='OPERATION_INDETERMINATE' WHERE actor=? AND operation_id=?").bind(&i.actor).bind(&i.id).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        sqlx::query("UPDATE file_roots SET state='recovery_required',error_code='OPERATION_INDETERMINATE' WHERE root_id=?").bind(&i.root).execute(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        Err(err("OPERATION_INDETERMINATE","The operation could not be safely reconciled. Preserve both locations; further root mutations are blocked."))
    }
    pub(super) async fn recover(&self) -> Result<(), RpcError> {
        let rows = sqlx::query("SELECT * FROM file_operations WHERE state='prepared' LIMIT 64")
            .fetch_all(&self.0.pool)
            .await
            .map_err(|_| RpcError::storage())?;
        for row in rows {
            let i = Intent {
                actor: row.get("actor"),
                id: row.get("operation_id"),
                root: row.get("root_id"),
                action: row.get("action"),
                source: row.get("source_path"),
                destination: row.get("destination_path"),
                identity: row.get("identity"),
                object: row.get("object_id"),
                expected: row.get("expected_version"),
                created: row.get("created_at"),
            };
            if let Ok(r) = self.root(&i.root).await {
                let _ = self.reconcile_intent(&i, &r).await;
            }
        }
        Ok(())
    }
    pub(super) async fn trash_view(
        &self,
        r: &Root,
        a: &Access,
        q: &FileQuery,
    ) -> Result<Value, RpcError> {
        let FileQuery::Trash { cursor, .. } = q else {
            return Err(err("INVALID_QUERY", "A trash query is required."));
        };
        let offset = self.cursor_offset(cursor.as_deref(), a, r, "trash")?;
        let rows=sqlx::query("SELECT * FROM file_trash WHERE root_id=? AND state='retained' ORDER BY trashed_at DESC,trash_id LIMIT 201").bind(&r.id).fetch_all(&self.0.pool).await.map_err(|_|RpcError::storage())?;
        let mut entries = Vec::new();
        let mut unavailable = 0usize;
        for row in rows.iter().skip(offset).take(16) {
            let original: String = row.get("original_path");
            if policy::excluded(&original, &r.exclusions) {
                continue;
            }
            let stored: String = row.get("stored_path");
            let identity: String = row.get("identity");
            let rr = r.clone();
            let current = blocking(move || {
                let root = safe_fs::SafeRoot::open(&rr.path, Some(&rr.identity))?;
                root.ensure_trash(rr.trash_identity.as_deref())?;
                root.info(&stored)
            })
            .await;
            let Ok(info) = current else {
                unavailable += 1;
                continue;
            };
            if info.hidden || info.identity != identity {
                unavailable += 1;
                continue;
            }
            entries.push(json!({"object_id":row.get::<String,_>("trash_id"),"name":original.rsplit('/').next().unwrap_or(""),"original_path":original,"kind":info.kind,"version":info.version,"size":info.size.to_string(),"trashed_at":row.get::<String,_>("trashed_at"),"retain_until":row.get::<String,_>("retain_until")}));
        }
        Ok(
            json!({"root":self.root_view(r,a).await?,"entries":entries,"unavailable_count":unavailable,"next_cursor":if offset+16<rows.len(){Some(self.make_cursor(a,r,"trash",offset+16))}else{None},"notice":format!("Retained at least 14 days; no automatic purge or permanent delete. {} retained objects on this page are unavailable or changed; their records and quota were preserved for owner recovery. Restore available items to release quota.",unavailable)}),
        )
    }
}
fn join(parent: &str, name: &str) -> String {
    if parent.is_empty() {
        name.into()
    } else {
        format!("{parent}/{name}")
    }
}
