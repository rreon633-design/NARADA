//! Explicit owner attachment snapshots and verified export. No plaintext cache
//! is created while preparing an attachment; the bounded snapshot lives in RAM.
use super::*;
use sha2::{Digest, Sha256};
use std::io::Read;
use zeroize::Zeroizing;
pub struct ContentSnapshot {
    pub name: String,
    pub bytes: Zeroizing<Vec<u8>>,
}
impl Files {
    pub async fn content_snapshot(
        &self,
        source: &TransferSource,
        _policy: &OwnedRwLockReadGuard<()>,
    ) -> Result<ContentSnapshot, RpcError> {
        source.validate()?;
        let _permit = self
            .0
            .jobs
            .clone()
            .try_acquire_owned()
            .map_err(|_| err("FILE_BUSY", "File admission is busy."))?;
        let _io = self.0.io.lock().await;
        let r = self.root(&source.root_id).await?;
        let e = self.entry(&r.id, &source.object_id).await?;
        if e.info.kind != "file"
            || e.info.hidden
            || e.info.size > MAX_ATTACHMENT_BYTES
            || policy::excluded(&e.relative_path, &r.exclusions)
        {
            return Err(err(
                "ATTACHMENT_UNSUPPORTED",
                "Choose an ordinary approved file no larger than 8 MiB.",
            ));
        }
        if e.info.version != source.expected_version {
            return Err(err(
                "STALE_VERSION",
                "The selected attachment changed. Refresh before sending.",
            ));
        }
        blocking(move || {
            let root = safe_fs::SafeRoot::open(&r.path, Some(&r.identity))?;
            let mut source = root.open_relative(&e.relative_path, false)?;
            let before = safe_fs::info(&source.file)?;
            if before.identity != e.info.identity || before.version != e.info.version {
                return Err(err("STALE_VERSION", "The attachment source changed."));
            }
            let mut bytes = Zeroizing::new(Vec::with_capacity(before.size as usize));
            Read::by_ref(&mut source.file)
                .take(MAX_ATTACHMENT_BYTES + 1)
                .read_to_end(&mut bytes)
                .map_err(safe_fs::io_error)?;
            let after = safe_fs::info(&source.file)?;
            root.verify()?;
            if bytes.len() as u64 != before.size
                || after.identity != before.identity
                || after.version != before.version
            {
                return Err(err(
                    "STALE_VERSION",
                    "An attachment cannot mix source versions.",
                ));
            }
            Ok(ContentSnapshot {
                name: e.name,
                bytes,
            })
        })
        .await
    }
    pub async fn abandon_content_export(&self, id: &str) -> Result<bool, RpcError> {
        let g = self.gate().read_owned().await;
        let state = self
            .transfer_action(
                TransferAction::Status {
                    transfer_id: id.into(),
                    side: TransferSide::Receive,
                },
                Access::Owner,
                g,
            )
            .await;
        match state {
            Err(e) if e.code == "TRANSFER_NOT_FOUND" => return Ok(true),
            Err(e) => return Err(e),
            Ok(value) => {
                let state: TransferStatus =
                    serde_json::from_value(value).map_err(|_| RpcError::storage())?;
                if state.state == "completed" {
                    return Ok(false);
                }
            }
        }
        self.transfer_action(
            TransferAction::Cancel {
                transfer_id: id.into(),
                side: TransferSide::Receive,
            },
            Access::Owner,
            self.gate().read_owned().await,
        )
        .await?;
        let state: TransferStatus = serde_json::from_value(
            self.transfer_action(
                TransferAction::Status {
                    transfer_id: id.into(),
                    side: TransferSide::Receive,
                },
                Access::Owner,
                self.gate().read_owned().await,
            )
            .await?,
        )
        .map_err(|_| RpcError::storage())?;
        Ok(state.state == "cancelled")
    }
    pub async fn publish_content(
        &self,
        id: &str,
        destination: TransferDestination,
        bytes: Zeroizing<Vec<u8>>,
    ) -> Result<Value, RpcError> {
        file_id(id)?;
        destination.validate()?;
        if bytes.len() as u64 > MAX_ATTACHMENT_BYTES {
            return Err(err(
                "ATTACHMENT_LIMIT",
                "The plaintext exceeds the attachment limit.",
            ));
        }
        let sha = hex::encode(Sha256::digest(bytes.as_slice()));
        let hashes = bytes
            .chunks(TRANSFER_CHUNK_BYTES)
            .map(|b| hex::encode(Sha256::digest(b)))
            .collect::<Vec<_>>();
        let mut manifest = TransferManifest {
            version: TRANSFER_PROTOCOL_VERSION,
            manifest_id: String::new(),
            source_version: sha.clone(),
            name: destination.name.clone(),
            size: bytes.len().to_string(),
            chunk_size: TRANSFER_CHUNK_BYTES as u32,
            chunk_count: hashes.len() as u32,
            sha256: sha,
        };
        manifest.manifest_id = transfers::manifest_digest(&manifest, &hashes)?;
        manifest.validate()?;
        let opened = self
            .transfer_action(
                TransferAction::OpenReceive {
                    transfer_id: id.into(),
                    destination,
                    manifest: manifest.clone(),
                },
                Access::Owner,
                self.gate().read_owned().await,
            )
            .await?;
        let status: TransferStatus =
            serde_json::from_value(opened).map_err(|_| RpcError::storage())?;
        if status.state == "completed" {
            return self
                .transfer_action(
                    TransferAction::Commit {
                        transfer_id: id.into(),
                    },
                    Access::Owner,
                    self.gate().read_owned().await,
                )
                .await;
        }
        for (page, values) in hashes.chunks(TRANSFER_MANIFEST_PAGE).enumerate() {
            self.transfer_action(
                TransferAction::ManifestPage {
                    transfer_id: id.into(),
                    offset: (page * TRANSFER_MANIFEST_PAGE) as u32,
                    hashes: values.to_vec(),
                },
                Access::Owner,
                self.gate().read_owned().await,
            )
            .await?;
        }
        for (index, data) in bytes.chunks(TRANSFER_CHUNK_BYTES).enumerate() {
            self.transfer_chunk(
                id.into(),
                index as u32,
                Some(data.to_vec()),
                Access::Owner,
                self.gate().read_owned().await,
            )
            .await?;
        }
        self.transfer_action(
            TransferAction::Commit {
                transfer_id: id.into(),
            },
            Access::Owner,
            self.gate().read_owned().await,
        )
        .await
    }
}
