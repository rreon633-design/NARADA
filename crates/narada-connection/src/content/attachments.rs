use super::*;
use age::secrecy::ExposeSecret;
use narada_files::safe_fs;
use std::io::{Read, Seek, SeekFrom, Write};
use zeroize::Zeroizing;
const BLOB_BUDGET: u64 = 128 * 1024 * 1024;
const RETAIN: i64 = 7 * 86400;
// Account for actual disk occupancy, including unknown interrupted-encryption
// orphans. Never adopt or delete an unrecorded file. Their bytes consume quota.
fn physical_budget(root: &SafeRoot, extra: u64) -> Result<(), RpcError> {
    let names = root.names(&root.open_relative("", false)?)?;
    if names.len() >= 256 {
        return Err(err(
            "CONTENT_QUOTA",
            "The bounded ciphertext directory is full; unknown objects were preserved.",
        ));
    }
    let mut bytes = extra;
    for name in names {
        let info = root.info(&name)?;
        if info.kind != "file" {
            return Err(err(
                "STAGING_CHANGED",
                "The private ciphertext directory contains an unexpected object.",
            ));
        }
        bytes = bytes.checked_add(info.size).ok_or_else(vault::locked)?;
        if bytes > BLOB_BUDGET {
            return Err(err(
                "CONTENT_QUOTA",
                "Known and orphan ciphertext exhaust the 128 MiB disk budget.",
            ));
        }
    }
    root.verify()?;
    Ok(())
}
fn size_at(d: &BlobDescriptor, index: usize) -> Result<usize, RpcError> {
    if index >= d.hashes.len() {
        return Err(err(
            "INVALID_CHUNK",
            "The ciphertext chunk index is invalid.",
        ));
    }
    Ok(
        (d.size - index as u64 * TRANSFER_CHUNK_BYTES as u64).min(TRANSFER_CHUNK_BYTES as u64)
            as usize,
    )
}
fn descriptor(bytes: &[u8], id: &str) -> BlobDescriptor {
    BlobDescriptor {
        version: 1,
        blob_id: id.into(),
        size: bytes.len() as u64,
        sha256: fingerprint(bytes),
        hashes: bytes
            .chunks(TRANSFER_CHUNK_BYTES)
            .map(fingerprint)
            .collect(),
    }
}
pub(super) fn validate_attachment(a: &AttachmentSecret) -> Result<(), RpcError> {
    let d = &a.descriptor;
    file_id(&d.blob_id)?;
    transfer_hash(&d.sha256)?;
    transfer_hash(&a.plaintext_sha256)?;
    if d.version != 1
        || d.size == 0
        || d.size > MAX_CIPHER_BYTES
        || a.plaintext_size > MAX_ATTACHMENT_BYTES
        || d.hashes.len() != d.size.div_ceil(TRANSFER_CHUNK_BYTES as u64) as usize
        || a.name.is_empty()
        || a.name.len() > 180
        || a.name
            .chars()
            .any(|c| c.is_control() || matches!(c, '/' | '\\'))
        || a.identity.len() > 128
    {
        return Err(err(
            "INVALID_ATTACHMENT",
            "The encrypted attachment descriptor exceeds its bounds.",
        ));
    }
    for h in &d.hashes {
        transfer_hash(h)?;
    }
    a.identity.parse::<age::x25519::Identity>().map_err(|_| {
        err(
            "INVALID_ATTACHMENT",
            "The protected attachment identity is invalid.",
        )
    })?;
    Ok(())
}
fn encrypt(bytes: &[u8]) -> Result<(Vec<u8>, String), RpcError> {
    let key = age::x25519::Identity::generate();
    let recipient = key.to_public();
    let encryptor =
        age::Encryptor::with_recipients(std::iter::once(&recipient as &dyn age::Recipient))
            .map_err(|_| {
                err(
                    "ATTACHMENT_ENCRYPT_FAILED",
                    "The age library rejected the recipient.",
                )
            })?;
    let mut cipher = Vec::new();
    let mut writer = encryptor.wrap_output(&mut cipher).map_err(|_| {
        err(
            "ATTACHMENT_ENCRYPT_FAILED",
            "The streaming encryption header failed.",
        )
    })?;
    for chunk in bytes.chunks(65536) {
        writer
            .write_all(chunk)
            .map_err(|_| err("ATTACHMENT_ENCRYPT_FAILED", "Streaming encryption failed."))?;
    }
    writer.finish().map_err(|_| {
        err(
            "ATTACHMENT_ENCRYPT_FAILED",
            "The final encrypted record could not be authenticated.",
        )
    })?;
    if cipher.len() as u64 > MAX_CIPHER_BYTES {
        return Err(err(
            "ATTACHMENT_LIMIT",
            "Ciphertext exceeded the explicit attachment limit.",
        ));
    }
    Ok((cipher, key.to_string().expose_secret().to_owned()))
}
fn decrypt(cipher: &[u8], a: &AttachmentSecret) -> Result<Zeroizing<Vec<u8>>, RpcError> {
    validate_attachment(a)?;
    if cipher.len() as u64 != a.descriptor.size || fingerprint(cipher) != a.descriptor.sha256 {
        return Err(err(
            "HASH_MISMATCH",
            "The immutable attachment ciphertext is incomplete or changed.",
        ));
    }
    let key = a
        .identity
        .parse::<age::x25519::Identity>()
        .map_err(|_| err("INVALID_ATTACHMENT", "Attachment identity is invalid."))?;
    let decryptor = age::Decryptor::new(cipher).map_err(|_| {
        err(
            "ATTACHMENT_DECRYPT_FAILED",
            "The age file header is invalid.",
        )
    })?;
    let reader = decryptor
        .decrypt(std::iter::once(&key as &dyn age::Identity))
        .map_err(|_| {
            err(
                "ATTACHMENT_DECRYPT_FAILED",
                "Attachment header authentication failed.",
            )
        })?;
    let mut plain = Zeroizing::new(Vec::new());
    reader
        .take(MAX_ATTACHMENT_BYTES + 1)
        .read_to_end(&mut plain)
        .map_err(|_| {
            err(
                "ATTACHMENT_DECRYPT_FAILED",
                "A stream record, ordering or final authentication tag failed.",
            )
        })?;
    if plain.len() as u64 != a.plaintext_size || fingerprint(&plain) != a.plaintext_sha256 {
        return Err(err(
            "HASH_MISMATCH",
            "The complete decrypted length/hash failed. No plaintext was exported.",
        ));
    }
    Ok(plain)
}
// Peer ownership is checked INSIDE every protected blob operation, never by a route's
// check-then-act wrapper. Message IDs and ciphertext descriptors are not upload authority.
fn upload_owned(d: &Document, peer: &str, message: &str, id: &str) -> Result<Blob, RpcError> {
    let m = d.messages.iter().find(|m| m.id == message && m.peer == peer && m.direction == "incoming"
        && m.state == "delivered" && m.receipt.is_some() && m.body.sender == peer && m.body.recipient == d.device)
        .ok_or_else(|| err("MESSAGE_UNAVAILABLE", "No committed attachment belongs to this peer."))?;
    let a = m.body.attachment.as_ref().filter(|a| a.descriptor.blob_id == id)
        .ok_or_else(|| err("MESSAGE_UNAVAILABLE", "No committed attachment belongs to this peer."))?;
    let b = d.blobs.get(id).filter(|b| b.peer == peer && b.direction == "incoming" && b.descriptor == a.descriptor)
        .ok_or_else(|| err("MESSAGE_UNAVAILABLE", "No committed attachment belongs to this peer."))?;
    if b.state == "cleaned" { return Err(err("BLOB_EXPIRED", "The explicitly cleaned attachment is no longer retained.")); }
    Ok(b.clone())
}
impl Content {
    async fn blob_root(&self) -> Result<SafeRoot, RpcError> {
        let expected = self.0.vault.read(|d| Ok(d.blob_directory.clone())).await?;
        let root = SafeRoot::open(&self.0.dir, None)?;
        let blobs = root.private_directory("content-blobs", expected.as_deref())?;
        if expected.is_none() {
            let identity = blobs.identity.clone();
            self.0
                .vault
                .update(|d| {
                    if d.blob_directory.is_some() {
                        return Err(err(
                            "STAGING_CHANGED",
                            "Another managed blob directory was recorded.",
                        ));
                    }
                    d.blob_directory = Some(identity);
                    Ok(())
                })
                .await?;
        }
        Ok(blobs)
    }
    pub(super) async fn prepare_attachment(
        &self,
        id: &str,
        peer: &str,
        source: &TransferSource,
        files: &narada_files::Files,
        policy: &tokio::sync::OwnedRwLockReadGuard<()>,
    ) -> Result<(AttachmentSecret, Blob), RpcError> {
        let _lock = self.0.blobs.lock().await;
        self.0.vault.read(|d|{let reserved=d.blobs.values().filter(|b|b.file_identity.is_some()&&b.state!="cleaned").map(|b|b.descriptor.size).sum::<u64>();if reserved+MAX_CIPHER_BYTES>BLOB_BUDGET{return Err(err("CONTENT_QUOTA","The ciphertext-cache budget is full; pending and unknown data are preserved."));}Ok(())}).await?;
        let snapshot = files.content_snapshot(source, policy).await?;
        let sha = fingerprint(&snapshot.bytes);
        let length = snapshot.bytes.len() as u64;
        let name = snapshot.name;
        let (cipher, identity) = tokio::task::spawn_blocking(move || encrypt(&snapshot.bytes))
            .await
            .map_err(|_| {
                err(
                    "ATTACHMENT_ENCRYPT_FAILED",
                    "The encryption worker stopped.",
                )
            })??;
        let desc = descriptor(&cipher, id);
        let root = self.blob_root().await?;
        physical_budget(&root, desc.size)?;
        if fs2::available_space(&root.path).map_err(|_| RpcError::storage())?
            < desc.size + 16 * 1024 * 1024
        {
            return Err(err(
                "STORAGE_FULL",
                "Ciphertext staging requires its size plus 16 MiB headroom.",
            ));
        }
        let mut f = root.open_owned_write(id, true, None)?;
        f.file
            .write_all(&cipher)
            .and_then(|_| f.file.sync_all())
            .map_err(|_| {
                err(
                    "STORAGE_FULL",
                    "Ciphertext staging did not finish. The original was not modified.",
                )
            })?;
        let file_identity = safe_fs::info(&f.file)?.identity;
        let secret = AttachmentSecret {
            descriptor: desc.clone(),
            name,
            plaintext_size: length,
            plaintext_sha256: sha,
            identity,
        };
        validate_attachment(&secret)?;
        Ok((
            secret,
            Blob {
                id: id.into(),
                peer: peer.into(),
                direction: "outgoing".into(),
                descriptor: desc.clone(),
                file_identity: Some(file_identity),
                state: "available".into(),
                verified: vec![true; desc.hashes.len()],
                error: None,
                created: now(),
                export: None,
                export_id: None,
                export_destination: None,
            },
        ))
    }
    pub async fn blob_chunk(&self, peer: &str, id: &str, index: u32) -> Result<Vec<u8>, RpcError> {
        file_id(id)?;
        let _lock = self.0.blobs.lock().await;
        let b = self
            .0
            .vault
            .read(|d| {
                let b = d.blobs.get(id).ok_or_else(|| {
                    err(
                        "BLOB_UNAVAILABLE",
                        "No peer-scoped ciphertext is available.",
                    )
                })?;
                let referenced = d.messages.iter().any(|m| {
                    m.peer == peer
                        && m.direction == "outgoing"
                        && m.state != "cancelled"
                        && m.body
                            .attachment
                            .as_ref()
                            .is_some_and(|a| a.descriptor.blob_id == id)
                });
                if b.peer != peer
                    || b.direction != "outgoing"
                    || b.state != "available"
                    || !referenced
                {
                    return Err(err(
                        "ACCESS_DENIED",
                        "This blob is not available to this peer.",
                    ));
                }
                if b.created + RETAIN < now() {
                    return Err(err("BLOB_EXPIRED", "The attachment snapshot has expired."));
                }
                Ok(b.clone())
            })
            .await?;
        let n = size_at(&b.descriptor, index as usize)?;
        let root = self.blob_root().await?;
        let mut f = root.open_relative(id, false)?;
        let info = safe_fs::info(&f.file)?;
        if b.file_identity.as_deref() != Some(info.identity.as_str())
            || info.size != b.descriptor.size
        {
            return Err(err("STAGING_CHANGED", "The immutable ciphertext changed."));
        }
        let mut bytes = vec![0; n];
        f.file
            .seek(SeekFrom::Start(index as u64 * TRANSFER_CHUNK_BYTES as u64))
            .and_then(|_| f.file.read_exact(&mut bytes))
            .map_err(|_| err("BLOB_UNAVAILABLE", "Ciphertext read failed."))?;
        if fingerprint(&bytes) != b.descriptor.hashes[index as usize] {
            return Err(err("HASH_MISMATCH", "The ciphertext chunk changed."));
        }
        Ok(bytes)
    }
    pub async fn begin_fetch(&self, message: &str) -> Result<Value, RpcError> {
        self.begin_fetch_inner(message, None).await
    }
    pub(crate) async fn begin_upload(&self, peer: &str, message: &str, id: &str) -> Result<Value, RpcError> {
        file_id(id)?;
        self.begin_fetch_inner(message, Some((peer, id))).await?;
        self.upload_status(peer, message, id).await
    }
    async fn begin_fetch_inner(&self, message: &str, upload: Option<(&str, &str)>) -> Result<Value, RpcError> {
        file_id(message)?;
        let active = if upload.is_some() { "receiving_push" } else { "queued" };
        let _lock = self.0.blobs.lock().await;
        let b =
            self.0
                .vault
                .read(|d| {
                    if let Some((peer, id)) = upload { upload_owned(d, peer, message, id)?; }
                    let m = d
                        .messages
                        .iter()
                        .find(|m| m.id == message && m.direction == "incoming")
                        .ok_or_else(|| {
                            err(
                                "MESSAGE_UNAVAILABLE",
                                "Choose a received attachment message.",
                            )
                        })?;
                    let a = m.body.attachment.as_ref().ok_or_else(|| {
                        err("INVALID_ATTACHMENT", "This message has no attachment.")
                    })?;
                    let b = d
                        .blobs
                        .get(&a.descriptor.blob_id)
                        .ok_or_else(vault::locked)?;
                    if b.state == "cleaned" {
                        return Err(err(
                            "BLOB_EXPIRED",
                            "This explicitly cleaned attachment is no longer retained.",
                        ));
                    }
                    let used = d
                        .blobs
                        .values()
                        .filter(|v| v.file_identity.is_some() && v.state != "cleaned")
                        .map(|v| v.descriptor.size)
                        .sum::<u64>();
                    if b.file_identity.is_none() && used + b.descriptor.size > BLOB_BUDGET {
                        return Err(err("CONTENT_QUOTA", "The ciphertext budget is full."));
                    }
                    Ok(b.clone())
                })
                .await?;
        if b.state == "available" || b.state == "exported" {
            return Ok(json!({"state":b.state}));
        }
        let root = self.blob_root().await?;
        let existing = b.file_identity.clone();
        if existing.is_none() {
            physical_budget(&root, b.descriptor.size)?;
        }
        if existing.is_none()
            && fs2::available_space(&root.path).map_err(|_| RpcError::storage())?
                < b.descriptor.size + 16 * 1024 * 1024
        {
            return Err(err(
                "STORAGE_FULL",
                "Insufficient ciphertext staging headroom.",
            ));
        }
        let mut f = root.open_owned_write(&b.id, existing.is_none(), existing.as_deref())?;
        let identity = safe_fs::info(&f.file)?.identity;
        if existing.is_none() {
            self.0
                .vault
                .update(|d| {
                    let r = d.blobs.get_mut(&b.id).ok_or_else(vault::locked)?;
                    r.file_identity = Some(identity.clone());
                    r.state = "allocating".into();
                    Ok(())
                })
                .await?;
            f.file
                .set_len(b.descriptor.size)
                .and_then(|_| f.file.sync_all())
                .map_err(|_| {
                    err(
                        "STORAGE_FULL",
                        "Ciphertext reservation failed; retained data was not reset.",
                    )
                })?;
        }
        if safe_fs::info(&f.file)?.size != b.descriptor.size {
            physical_budget(
                &root,
                b.descriptor
                    .size
                    .saturating_sub(safe_fs::info(&f.file)?.size),
            )?;
            if b.verified.iter().any(|v| *v) {
                return Err(err("STAGING_CHANGED", "The saved ciphertext size changed."));
            }
            if fs2::available_space(&root.path).map_err(|_| RpcError::storage())?
                < b.descriptor.size + 16 * 1024 * 1024
            {
                return Err(err(
                    "STORAGE_FULL",
                    "Ciphertext allocation still needs safe headroom.",
                ));
            }
            f.file
                .set_len(b.descriptor.size)
                .and_then(|_| f.file.sync_all())
                .map_err(|_| {
                    err(
                        "STORAGE_FULL",
                        "The saved allocation could not be completed.",
                    )
                })?;
        }
        let mut verified = b.verified.clone();
        if verified.len() != b.descriptor.hashes.len() {
            return Err(vault::locked());
        }
        for (i, acknowledged) in verified.iter_mut().enumerate() {
            if *acknowledged {
                let mut data = vec![0; size_at(&b.descriptor, i)?];
                f.file
                    .seek(SeekFrom::Start(i as u64 * TRANSFER_CHUNK_BYTES as u64))
                    .and_then(|_| f.file.read_exact(&mut data))
                    .map_err(|_| RpcError::storage())?;
                if fingerprint(&data) != b.descriptor.hashes[i] {
                    *acknowledged = false;
                }
            }
        }
        self.0
            .vault
            .update(|d| {
                let row = d.blobs.get_mut(&b.id).ok_or_else(vault::locked)?;
                row.verified = verified;
                row.state = active.into();
                row.error = None;
                Ok(json!({"state":active,"blob_id":b.id}))
            })
            .await
    }
    pub async fn pending_blobs(&self) -> Result<Vec<(String, String, Vec<u32>)>, RpcError> {
        self.0
            .vault
            .read(|d| {
                Ok(d.blobs
                    .values()
                    .filter(|b| b.direction == "incoming" && b.state == "queued")
                    .take(1)
                    .map(|b| {
                        (
                            b.id.clone(),
                            b.peer.clone(),
                            b.verified
                                .iter()
                                .enumerate()
                                .filter(|(_, v)| !**v)
                                .map(|(i, _)| i as u32)
                                .collect(),
                        )
                    })
                    .collect())
            })
            .await
    }
    pub async fn accept_chunk(&self, id: &str, index: u32, data: Vec<u8>) -> Result<(), RpcError> {
        self.accept_chunk_inner(id, index, data, None).await
    }
    pub(crate) async fn upload_chunk(&self, peer: &str, message: &str, id: &str, index: u32, data: Vec<u8>) -> Result<(), RpcError> {
        self.accept_chunk_inner(id, index, data, Some((peer, message))).await
    }
    async fn accept_chunk_inner(&self, id: &str, index: u32, data: Vec<u8>, upload: Option<(&str, &str)>) -> Result<(), RpcError> {
        let _lock = self.0.blobs.lock().await;
        let active = if upload.is_some() { "receiving_push" } else { "queued" };
        let b = self.0.vault.read(|d| match upload {
            Some((peer, message)) => upload_owned(d, peer, message, id),
            None => d.blobs.get(id).cloned().ok_or_else(vault::locked),
        }).await?;
        if b.state != active { return Err(err("BLOB_PAUSED", "This ciphertext receiver is not active.")); }
        let n = size_at(&b.descriptor, index as usize)?;
        if data.len() != n || fingerprint(&data) != b.descriptor.hashes[index as usize] {
            return Err(err("HASH_MISMATCH", "A ciphertext chunk failed manifest validation."));
        }
        let root = self.blob_root().await?;
        let mut f = root.open_owned_write(id, false, b.file_identity.as_deref())?;
        if safe_fs::info(&f.file)?.size != b.descriptor.size { return Err(err("STAGING_CHANGED", "Ciphertext staging changed.")); }
        f.file.seek(SeekFrom::Start(index as u64 * TRANSFER_CHUNK_BYTES as u64))
            .and_then(|_| f.file.write_all(&data)).and_then(|_| f.file.sync_all())
            .map_err(|_| err("STORAGE_FULL", "The ciphertext chunk could not be made durable."))?;
        self.0.vault.update(|d| {
            if let Some((peer, message)) = upload { upload_owned(d, peer, message, id)?; }
            let b = d.blobs.get_mut(id).ok_or_else(vault::locked)?;
            if b.state != active { return Err(err("BLOB_PAUSED", "The ciphertext receiver stopped.")); }
            b.verified[index as usize] = true; Ok(())
        }).await
    }
    pub(crate) async fn upload_status(&self, peer: &str, message: &str, id: &str) -> Result<Value, RpcError> {
        file_id(message)?; file_id(id)?;
        let _lock = self.0.blobs.lock().await;
        self.0.vault.read(|d| {
            let b = upload_owned(d, peer, message, id)?;
            let available = matches!(b.state.as_str(), "available" | "exported");
            if b.verified.len() != b.descriptor.hashes.len() { return Err(vault::locked()); }
            Ok(json!({"version":1,"message_id":message,"blob_id":id,"ciphertext_sha256":b.descriptor.sha256,
                "state": if available { "available" } else if b.state == "receiving_push" { "receiving" } else { "paused" },
                "missing":b.verified.iter().enumerate().filter_map(|(i,v)| if *v { None } else { Some(i as u32) }).collect::<Vec<_>>()}))
        }).await
    }
    pub(crate) async fn finish_upload(&self, peer: &str, message: &str, id: &str) -> Result<Value, RpcError> {
        file_id(message)?; file_id(id)?;
        {
            let _lock = self.0.blobs.lock().await;
            let b = self.0.vault.read(|d| upload_owned(d, peer, message, id)).await?;
            if !matches!(b.state.as_str(), "receiving_push" | "available" | "exported") {
                return Err(err("BLOB_PAUSED", "The ciphertext receiver is not active."));
            }
            // Cipher length+hash, all age authentication (including final record), then plaintext
            // length+hash, before availability. A retry re-verifies; bitmap is not this receipt.
            let _plain = self.plaintext(id).await?;
            self.0.vault.update(|d| {
                let owned = upload_owned(d, peer, message, id)?;
                if owned.state != b.state { return Err(err("BLOB_PAUSED", "The ciphertext receiver stopped.")); }
                let b = d.blobs.get_mut(id).ok_or_else(vault::locked)?;
                if b.state != "exported" { b.state = "available".into(); }
                b.error = None; Ok(())
            }).await?;
        }
        self.upload_status(peer, message, id).await
    }
    async fn plaintext(&self, id: &str) -> Result<Zeroizing<Vec<u8>>, RpcError> {
        let (b, a) = self
            .0
            .vault
            .read(|d| {
                let b = d.blobs.get(id).ok_or_else(vault::locked)?;
                let a = d
                    .messages
                    .iter()
                    .find_map(|m| {
                        m.body
                            .attachment
                            .as_ref()
                            .filter(|a| a.descriptor.blob_id == id)
                    })
                    .ok_or_else(vault::locked)?;
                if b.verified.iter().any(|v| !*v) {
                    return Err(err(
                        "TRANSFER_INCOMPLETE",
                        "Attachment chunks are still missing.",
                    ));
                }
                Ok((b.clone(), a.clone()))
            })
            .await?;
        let root = self.blob_root().await?;
        let mut f = root.open_relative(id, false)?;
        let info = safe_fs::info(&f.file)?;
        if b.file_identity.as_deref() != Some(info.identity.as_str())
            || info.size != b.descriptor.size
        {
            return Err(err("STAGING_CHANGED", "The retained ciphertext changed."));
        }
        let mut cipher = Vec::with_capacity(info.size as usize);
        Read::by_ref(&mut f.file)
            .take(MAX_CIPHER_BYTES + 1)
            .read_to_end(&mut cipher)
            .map_err(|_| RpcError::storage())?;
        tokio::task::spawn_blocking(move || decrypt(&cipher, &a))
            .await
            .map_err(|_| {
                err(
                    "ATTACHMENT_DECRYPT_FAILED",
                    "The verification worker stopped.",
                )
            })?
    }
    pub async fn finish_fetch(&self, id: &str) -> Result<(), RpcError> {
        let _lock = self.0.blobs.lock().await;
        let _plaintext = self.plaintext(id).await?;
        self.0
            .vault
            .update(|d| {
                let b = d.blobs.get_mut(id).ok_or_else(vault::locked)?;
                if b.state != "queued" {
                    return Err(err("BLOB_PAUSED", "The download was paused."));
                }
                b.state = "available".into();
                b.error = None;
                Ok(())
            })
            .await
    }
    pub async fn blob_error(&self, id: &str, code: &str) -> Result<(), RpcError> {
        self.0
            .vault
            .update(|d| {
                let b = d.blobs.get_mut(id).ok_or_else(vault::locked)?;
                if !matches!(b.state.as_str(), "available" | "exported" | "cleaned" | "receiving_push") {
                    b.state = "paused".into();
                    b.error = Some(code.into());
                }
                Ok(())
            })
            .await
    }
    pub async fn pause_attachment(&self, message: &str) -> Result<Value, RpcError> {
        self.0
            .vault
            .update(|d| {
                let id = d
                    .messages
                    .iter()
                    .find(|m| m.id == message)
                    .and_then(|m| m.body.attachment.as_ref())
                    .map(|a| a.descriptor.blob_id.clone())
                    .ok_or_else(|| err("INVALID_ATTACHMENT", "No attachment is selected."))?;
                let b = d.blobs.get_mut(&id).ok_or_else(vault::locked)?;
                if matches!(b.state.as_str(), "queued" | "receiving_push") {
                    b.state = "paused".into();
                }
                Ok(json!({"state":b.state}))
            })
            .await
    }
    pub async fn export(
        &self,
        input: ExportAttachment,
        files: &narada_files::Files,
    ) -> Result<Value, RpcError> {
        file_id(&input.message_id)?;
        file_id(&input.operation_id)?;
        input.destination.validate()?;
        let _lock = self.0.blobs.lock().await;
        let b=self.0.vault.read(|d|{let id=d.messages.iter().find(|m|m.id==input.message_id).and_then(|m|m.body.attachment.as_ref()).map(|a|&a.descriptor.blob_id).ok_or_else(||err("INVALID_ATTACHMENT","Select a retained attachment."))?;
let b=d.blobs.get(id).ok_or_else(vault::locked)?;if !matches!(b.state.as_str(),"available"|"exported"){return Err(err("ATTACHMENT_UNAVAILABLE","Download and authenticate the attachment before export."));}
if b.export_id.as_ref().is_some_and(|id|id!=&input.operation_id)||b.export_destination.as_ref().is_some_and(|dst|dst!=&input.destination){return Err(err("EXPORT_ALREADY_BOUND","Retry the same export identity/destination; existing exports are never overwritten."));}Ok(b.clone())}).await?;
        if let Some(receipt) = b.export {
            return Ok(receipt);
        }
        let bytes = self.plaintext(&b.id).await?;
        self.0
            .vault
            .update(|d| {
                let b = d.blobs.get_mut(&b.id).ok_or_else(vault::locked)?;
                b.export_id = Some(input.operation_id.clone());
                b.export_destination = Some(input.destination.clone());
                Ok(())
            })
            .await?;
        let receipt = match files
            .publish_content(&input.operation_id, input.destination, bytes)
            .await
        {
            Ok(value) => value,
            Err(e) => {
                if e.code == "NAME_COLLISION"
                    && files
                        .abandon_content_export(&input.operation_id)
                        .await
                        .unwrap_or(false)
                {
                    self.0
                        .vault
                        .update(|d| {
                            let saved = d.blobs.get_mut(&b.id).ok_or_else(vault::locked)?;
                            saved.export_id = None;
                            saved.export_destination = None;
                            Ok(())
                        })
                        .await?;
                }
                return Err(e);
            }
        };
        self.0
            .vault
            .update(|d| {
                let b = d.blobs.get_mut(&b.id).ok_or_else(vault::locked)?;
                b.export = Some(receipt.clone());
                b.state = "exported".into();
                Ok(receipt)
            })
            .await
    }
    pub async fn cleanup(&self) -> Result<Value, RpcError> {
        let _lock = self.0.blobs.lock().await;
        let eligible = self
            .0
            .vault
            .read(|d| {
                Ok(d.blobs
                    .values()
                    .filter(|b| {
                        b.created + RETAIN < now()
                            && !(b.export_id.is_some() && b.export.is_none())
                            && !matches!(b.state.as_str(), "queued" | "allocating" | "receiving_push")
                            && !d.messages.iter().any(|m| {
                                m.direction == "outgoing"
                                    && !matches!(m.state.as_str(), "delivered" | "cancelled")
                                    && m.body
                                        .attachment
                                        .as_ref()
                                        .is_some_and(|a| a.descriptor.blob_id == b.id)
                            })
                    })
                    .cloned()
                    .collect::<Vec<_>>())
            })
            .await?;
        let mut removed = 0;
        for b in eligible {
            if b.state == "cleaned" {
                continue;
            }
            if let Some(identity) = &b.file_identity {
                let root = self.blob_root().await?;
                if let Err(e) = root.delete_owned(&b.id, identity) {
                    if e.code != "OBJECT_UNAVAILABLE" {
                        continue;
                    }
                }
            }
            self.0
                .vault
                .update(|d| {
                    let row = d.blobs.get_mut(&b.id).ok_or_else(vault::locked)?;
                    row.file_identity = None;
                    row.state = "cleaned".into();
                    Ok(())
                })
                .await?;
            removed += 1;
        }
        self.0.vault.update(|d|{d.messages.retain(|m|m.created+30*86400>=now()||!matches!(m.state.as_str(),"delivered"|"cancelled")||m.body.attachment.as_ref().is_some_and(|a|d.blobs.get(&a.descriptor.blob_id).is_some_and(|b|b.state!="cleaned")));
let referenced=d.messages.iter().filter_map(|m|m.body.attachment.as_ref().map(|a|a.descriptor.blob_id.clone())).collect::<std::collections::HashSet<_>>();d.blobs.retain(|id,b|b.state!="cleaned"||referenced.contains(id));Ok(json!({"ciphertext_files_cleaned":removed,"published_files_deleted":0,"unknown_orphans_deleted":0}))}).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample(bytes: &[u8]) -> (Vec<u8>, AttachmentSecret) {
        let (cipher, identity) = encrypt(bytes).unwrap();
        let a = AttachmentSecret {
            descriptor: descriptor(&cipher, &uuid::Uuid::new_v4().to_string()),
            name: "inert.bin".into(),
            plaintext_size: bytes.len() as u64,
            plaintext_sha256: fingerprint(bytes),
            identity,
        };
        (cipher, a)
    }
    #[test]
    fn fresh_keys_produce_distinct_authenticated_streams() {
        let (a, sa) = sample(b"synthetic");
        let (b, sb) = sample(b"synthetic");
        assert_ne!(sa.identity, sb.identity);
        assert_ne!(a, b);
        assert_eq!(decrypt(&a, &sa).unwrap().as_slice(), b"synthetic");
        assert!(decrypt(&a, &sb).is_err());
    }
    #[test]
    fn tamper_truncation_reordering_and_wrong_full_length_never_decrypt() {
        let plain = vec![7u8; 150000];
        let (cipher, a) = sample(&plain);
        let mut altered = cipher.clone();
        let n = altered.len() - 1;
        altered[n] ^= 1;
        let mut changed = a.clone();
        changed.descriptor = descriptor(&altered, &a.descriptor.blob_id);
        assert!(decrypt(&altered, &changed).is_err());
        let truncated = &cipher[..cipher.len() - 1];
        changed.descriptor = descriptor(truncated, &a.descriptor.blob_id);
        assert!(decrypt(truncated, &changed).is_err());
        let mut reordered = cipher.clone();
        reordered.swap(80000, 90000);
        changed.descriptor = descriptor(&reordered, &a.descriptor.blob_id);
        if reordered != cipher {
            assert!(decrypt(&reordered, &changed).is_err());
        }
        let mut length = a.clone();
        length.plaintext_size += 1;
        assert!(decrypt(&cipher, &length).is_err());
        let mut hash = a;
        hash.plaintext_sha256 = "0".repeat(64);
        assert!(decrypt(&cipher, &hash).is_err());
    }
    #[test]
    fn empty_plaintext_still_has_final_authentication() {
        let (cipher, a) = sample(b"");
        assert!(!cipher.is_empty());
        assert!(decrypt(&cipher, &a).unwrap().is_empty());
        assert!(decrypt(&cipher[..cipher.len() - 16], &a).is_err());
    }
    #[test]
    fn malicious_descriptor_cannot_create_unbounded_chunks() {
        let (_, mut a) = sample(b"bounded");
        a.descriptor.size = MAX_CIPHER_BYTES + 1;
        assert!(validate_attachment(&a).is_err());
        a.descriptor.size = 100;
        a.descriptor.blob_id = "../escape".into();
        assert!(validate_attachment(&a).is_err());
    }
}
