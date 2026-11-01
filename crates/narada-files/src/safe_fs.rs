//! Handle-rooted IO. Windows pins every ancestor with delete sharing denied.
//! Linux development uses openat2 BENEATH/NO_SYMLINKS/NO_XDEV, never a canonicalize-then-open fallback.
use crate::policy::{self, err};
use narada_protocol::RpcError;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{File, Metadata},
    path::{Path, PathBuf},
    time::UNIX_EPOCH,
};
#[cfg(target_os = "linux")]
mod platform_linux;
#[cfg(target_os = "linux")]
use platform_linux as platform;
#[cfg(windows)]
mod platform_windows;
#[cfg(windows)]
use platform_windows as platform;
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Info {
    pub identity: String,
    pub version: String,
    pub kind: String,
    pub size: u64,
    pub modified: String,
    #[serde(default)]
    pub hidden: bool,
}
pub struct Opened {
    pub file: File,
    pub _ancestors: Vec<File>,
    #[cfg_attr(not(windows), allow(dead_code))]
    pub relative: String,
}
pub struct SafeRoot {
    pub path: PathBuf,
    pub file: File,
    pub identity: String,
    pub _ancestors: Vec<File>,
}
impl SafeRoot {
    pub fn open(path: &Path, expected: Option<&str>) -> Result<Self, RpcError> {
        let (file, ancestors) = platform::anchor(path)?;
        let info = platform::info(&file)?;
        if info.kind != "directory" || info.hidden || expected.is_some_and(|s| s != info.identity) {
            return Err(err("ROOT_CHANGED","The selected directory is absent or its filesystem identity changed. It was not silently replaced."));
        }
        platform::supported_root(&file)?;
        let path = platform::location(&file, path)?;
        Ok(Self {
            path,
            file,
            identity: info.identity,
            _ancestors: ancestors,
        })
    }
    pub fn verify(&self) -> Result<(), RpcError> {
        Self::open(&self.path, Some(&self.identity)).map(|_| ())
    }
    pub fn open_relative(&self, relative: &str, mutation: bool) -> Result<Opened, RpcError> {
        policy::relative(relative)?;
        platform::open_relative(self, relative, mutation)
    }
    pub fn info(&self, relative: &str) -> Result<Info, RpcError> {
        let object = self.open_relative(relative, false)?;
        platform::info(&object.file)
    }
    pub fn names(&self, object: &Opened) -> Result<Vec<String>, RpcError> {
        platform::names(self, object)
    }
    pub fn ensure_trash(&self, expected: Option<&str>) -> Result<String, RpcError> {
        if expected.is_none() {
            platform::mkdir(self, ".narada-trash")?;
        }
        let object = self.open_relative(".narada-trash", false)?;
        let info = platform::info(&object.file)?;
        if info.kind != "directory" || info.hidden || expected.is_some_and(|s| s != info.identity) {
            return Err(err(
                "TRASH_CHANGED",
                "Managed trash identity changed. No contents were adopted or deleted.",
            ));
        }
        Ok(info.identity)
    }
    pub fn rename(
        &self,
        source: &str,
        target: &str,
        identity: &str,
        version: &str,
    ) -> Result<Info, RpcError> {
        policy::relative(source)?;
        policy::relative(target)?;
        self.verify()?;
        let src = self.open_relative(source, true)?;
        let info = platform::info(&src.file)?;
        if info.identity != identity || info.version != version {
            return Err(err(
                "STALE_VERSION",
                "This object changed. Refresh its details before trying a new operation.",
            ));
        }
        if info.kind == "directory" && !self.names(&src)?.is_empty() {
            return Err(err("DIRECTORY_NOT_EMPTY","This phase mutates files and empty folders only. Nonempty folder moves require a separately qualified recursive policy."));
        }
        let parent = target.rsplit_once('/').map_or("", |v| v.0);
        let name = target.rsplit('/').next().unwrap_or("");
        let dst_parent = self.open_relative(parent, false)?;
        for existing in self.names(&dst_parent)? {
            if policy::fold(&existing) == policy::fold(name) {
                return Err(err(
                    "NAME_COLLISION",
                    "An entry with that name already exists. Nothing was overwritten.",
                ));
            }
        }
        platform::rename(self, &src, source, &dst_parent, name)?;
        #[cfg(windows)]
        let actual = platform::info(&src.file)?;
        #[cfg(not(windows))]
        let actual = self.info(target)?;
        if actual.identity != identity {
            return Err(err("OPERATION_INDETERMINATE","The namespace changed during this operation. Preserve the directory and inspect the operation journal; no retry is automatic."));
        }
        drop(src);
        self.verify()?;
        Ok(actual)
    }
}
pub fn info(file: &File) -> Result<Info, RpcError> {
    platform::info(file)
}
pub fn version(identity: &str, meta: &Metadata, change: &str) -> Info {
    let modified = meta.modified().unwrap_or(UNIX_EPOCH);
    let since = modified.duration_since(UNIX_EPOCH).unwrap_or_default();
    let data = format!(
        "{}:{}:{}:{}:{}",
        identity,
        meta.len(),
        since.as_secs(),
        since.subsec_nanos(),
        change
    );
    Info {
        identity: identity.into(),
        version: hex::encode(Sha256::digest(data.as_bytes())),
        kind: if meta.is_dir() { "directory" } else { "file" }.into(),
        size: if meta.is_file() { meta.len() } else { 0 },
        modified: chrono::DateTime::<chrono::Utc>::from(modified).to_rfc3339(),
        hidden: false,
    }
}
#[track_caller]
pub fn io_error(e: std::io::Error) -> RpcError {
    // Debug-build diagnostics contain OS codes and compile-time source locations
    // only, never paths, payloads, credentials or the OS error's arbitrary text.
    // Public RPC errors and release-build behavior remain unchanged.
    #[cfg(debug_assertions)]
    {
        let location = std::panic::Location::caller();
        eprintln!("Safe filesystem failure: OS {:?}, source {}:{}", e.raw_os_error(), location.file(), location.line());
    }
    match e.kind(){std::io::ErrorKind::NotFound=>err("OBJECT_UNAVAILABLE","The approved object is missing or no longer accessible."),std::io::ErrorKind::AlreadyExists=>err("NAME_COLLISION","The destination exists. Nothing was overwritten."),_=>err("UNSAFE_OR_BUSY","The filesystem object is busy, unsafe, unsupported or inaccessible. Symbolic links, reparse points, special files and cross-filesystem traversal are refused.")}
}
#[cfg(not(windows))]
pub fn time_key(t: Result<std::time::SystemTime, std::io::Error>) -> String {
    format!(
        "{:?}",
        t.ok().and_then(|v| v.duration_since(UNIX_EPOCH).ok())
    )
}

// Transfer staging primitives. Callers supply only server-generated leaves and
// saved identities; no peer-provided temporary path ever reaches these APIs.
impl SafeRoot {
    pub fn private_directory(&self, name: &str, expected: Option<&str>) -> Result<Self, RpcError> {
        policy::component(name)?;
        self.verify()?;
        if expected.is_none() {
            platform::mkdir_private(self, name)?;
        }
        let opened = self.open_relative(name, false)?;
        let i = info(&opened.file)?;
        if i.kind != "directory" || expected.is_some_and(|id| id != i.identity) {
            return Err(err(
                "STAGING_CHANGED",
                "The private staging directory changed. No data was adopted or reset.",
            ));
        }
        Self::open(&self.path.join(name), Some(&i.identity))
    }
    pub fn open_owned_write(
        &self,
        rel: &str,
        create: bool,
        identity: Option<&str>,
    ) -> Result<Opened, RpcError> {
        policy::relative(rel)?;
        self.verify()?;
        let (parent, leaf) = rel.rsplit_once('/').unwrap_or(("", rel));
        let mut parent = self.open_relative(parent, false)?;
        let file = platform::writable(self, &parent, leaf, create)?;
        let i = info(&file)?;
        if i.kind != "file" || identity.is_some_and(|id| id != i.identity) {
            return Err(err(
                "STAGING_CHANGED",
                "The staged file identity changed. Existing content was preserved.",
            ));
        }
        parent._ancestors.push(parent.file);
        Ok(Opened {
            file,
            _ancestors: parent._ancestors,
            relative: rel.into(),
        })
    }
    pub fn delete_owned(&self, rel: &str, identity: &str) -> Result<(), RpcError> {
        policy::relative(rel)?;
        self.verify()?;
        let opened = self.open_relative(rel, true)?;
        let i = info(&opened.file)?;
        if i.kind != "file" || i.identity != identity {
            return Err(err(
                "STAGING_CHANGED",
                "Cleanup refuses an unrecognized staged object.",
            ));
        }
        platform::delete(self, rel, &opened)
    }
}
