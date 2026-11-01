use super::*;
use rustix::fs::{openat2, Mode, OFlags, ResolveFlags};
use std::os::{fd::AsRawFd, unix::fs::MetadataExt};
fn open(parent: &File, path: &str, directory: bool, within: bool) -> Result<File, RpcError> {
    let mut flags = OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NONBLOCK;
    if directory {
        flags |= OFlags::DIRECTORY;
    }
    let mut resolve =
        ResolveFlags::BENEATH | ResolveFlags::NO_SYMLINKS | ResolveFlags::NO_MAGICLINKS;
    if within {
        resolve |= ResolveFlags::NO_XDEV;
    }
    openat2(parent, path, flags, Mode::empty(), resolve)
        .map(File::from)
        .map_err(|e| io_error(e.into()))
}
pub fn anchor(path: &Path) -> Result<(File, Vec<File>), RpcError> {
    let mut current = File::open("/").map_err(io_error)?;
    let mut ancestors = Vec::new();
    for part in path.components() {
        match part {
            std::path::Component::RootDir => {}
            std::path::Component::Normal(s) => {
                let s = s
                    .to_str()
                    .ok_or_else(|| err("UNSAFE_ROOT", "Use a UTF-8 folder path."))?;
                policy::component(s)?;
                let next = open(&current, s, true, false)?;
                ancestors.push(current);
                current = next;
            }
            _ => {
                return Err(err(
                    "UNSAFE_ROOT",
                    "An absolute normalized local folder is required.",
                ))
            }
        }
    }
    Ok((current, ancestors))
}
pub fn supported_root(file: &File) -> Result<(), RpcError> {
    let mut stat: libc::statfs = unsafe { std::mem::zeroed() };
    // SAFETY: a live file descriptor and valid writable statfs allocation.
    if unsafe { libc::fstatfs(file.as_raw_fd(), &mut stat) } != 0 {
        return Err(io_error(std::io::Error::last_os_error()));
    }
    if !matches!(
        stat.f_type as u64,
        0xEF53 | 0x58465342 | 0x9123683E | 0x794c7630 | 0x01021994
    ) {
        return Err(err("FILESYSTEM_UNSUPPORTED","Development file access supports local ext4, XFS, Btrfs, overlay and tmpfs only; network and pseudo filesystems are refused."));
    }
    if file.metadata().map_err(io_error)?.mode() & 0o022 != 0 {
        return Err(err(
            "ROOT_PERMISSIONS",
            "The development root must not be group- or world-writable.",
        ));
    }
    Ok(())
}
pub fn info(file: &File) -> Result<Info, RpcError> {
    let m = file.metadata().map_err(io_error)?;
    if (!m.is_file() && !m.is_dir()) || (m.is_file() && m.nlink() != 1) {
        return Err(err(
            "UNSAFE_OBJECT",
            "Only ordinary directories and singly-linked regular files are supported.",
        ));
    }
    let identity = format!("{}:{}:{}", m.dev(), m.ino(), time_key(m.created()));
    Ok(version(
        &identity,
        &m,
        &format!("{}:{}", m.ctime(), m.ctime_nsec()),
    ))
}
pub fn open_relative(root: &SafeRoot, rel: &str, _mutation: bool) -> Result<Opened, RpcError> {
    let file = if rel.is_empty() {
        root.file.try_clone().map_err(io_error)?
    } else {
        open(&root.file, rel, false, true)?
    };
    info(&file)?;
    Ok(Opened {
        file,
        _ancestors: vec![],
        relative: rel.into(),
    })
}
pub fn names(_root: &SafeRoot, object: &Opened) -> Result<Vec<String>, RpcError> {
    if !object.file.metadata().map_err(io_error)?.is_dir() {
        return Err(err("NOT_A_DIRECTORY", "Select a directory to browse."));
    }
    let dir = std::fs::read_dir(format!("/proc/self/fd/{}", object.file.as_raw_fd()))
        .map_err(io_error)?;
    let mut result = Vec::new();
    for value in dir {
        let value = value.map_err(io_error)?;
        if let Some(s) = value.file_name().to_str() {
            result.push(s.to_owned());
        } else {
            result.push("\0".into());
        }
        if result.len() > 5000 {
            return Err(err(
                "INDEX_LIMIT",
                "A directory exceeds the 5,000-entry development limit. Choose a smaller root.",
            ));
        }
    }
    Ok(result)
}
pub fn mkdir(root: &SafeRoot, name: &str) -> Result<(), RpcError> {
    rustix::fs::mkdirat(&root.file,name,Mode::RUSR|Mode::WUSR|Mode::XUSR).map_err(|e|if e==rustix::io::Errno::EXIST{err("TRASH_UNTRUSTED","A managed-trash directory exists without its saved identity. It was not adopted; preserve it for owner recovery.")}else{io_error(e.into())})?;
    root.file.sync_all().map_err(io_error)
}
pub fn rename(
    root: &SafeRoot,
    source: &Opened,
    rel: &str,
    dest: &Opened,
    name: &str,
) -> Result<(), RpcError> {
    let (parent, leaf) = rel.rsplit_once('/').unwrap_or(("", rel));
    let parent = root.open_relative(parent, false)?;
    if info(&root.open_relative(rel, false)?.file)?.identity != info(&source.file)?.identity {
        return Err(err(
            "STALE_VERSION",
            "The source entry changed before publication.",
        ));
    }
    // No fallback to replace-existing or cross-filesystem copy/delete.
    rustix::fs::renameat_with(
        &parent.file,
        leaf,
        &dest.file,
        name,
        rustix::fs::RenameFlags::NOREPLACE,
    )
    .map_err(|e| io_error(e.into()))?;
    parent.file.sync_all().map_err(io_error)?;
    dest.file.sync_all().map_err(io_error)?;
    Ok(())
}

pub fn location(_file: &File, path: &Path) -> Result<PathBuf, RpcError> {
    std::fs::canonicalize(path).map_err(io_error)
}

pub fn mkdir_private(root: &SafeRoot, name: &str) -> Result<(), RpcError> {
    rustix::fs::mkdirat(&root.file,name,Mode::RUSR|Mode::WUSR|Mode::XUSR).map_err(|e|if e==rustix::io::Errno::EXIST{err("STAGING_UNTRUSTED","A private staging directory exists without its saved identity. It was not adopted.")}else{io_error(e.into())})?;
    root.file.sync_all().map_err(io_error)
}
pub fn writable(
    _root: &SafeRoot,
    parent: &Opened,
    name: &str,
    create: bool,
) -> Result<File, RpcError> {
    let mut flags = OFlags::RDWR | OFlags::CLOEXEC | OFlags::NONBLOCK;
    if create {
        flags |= OFlags::CREATE | OFlags::EXCL;
    }
    let f = openat2(
        &parent.file,
        name,
        flags,
        if create {
            Mode::RUSR | Mode::WUSR
        } else {
            Mode::empty()
        },
        ResolveFlags::BENEATH
            | ResolveFlags::NO_SYMLINKS
            | ResolveFlags::NO_MAGICLINKS
            | ResolveFlags::NO_XDEV,
    )
    .map(File::from)
    .map_err(|e| io_error(e.into()))?;
    info(&f)?;
    parent.file.sync_all().map_err(io_error)?;
    Ok(f)
}
pub fn delete(root: &SafeRoot, rel: &str, _opened: &Opened) -> Result<(), RpcError> {
    let (parent, leaf) = rel.rsplit_once('/').unwrap_or(("", rel));
    let parent = root.open_relative(parent, false)?;
    rustix::fs::unlinkat(&parent.file, leaf, rustix::fs::AtFlags::empty())
        .map_err(|e| io_error(e.into()))?;
    parent.file.sync_all().map_err(io_error)
}
