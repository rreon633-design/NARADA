use super::*;
use std::os::windows::{ffi::OsStrExt, fs::OpenOptionsExt, io::AsRawHandle};
use windows_sys::Wdk::Storage::FileSystem::{
    FileRenameInformation, NtSetInformationFile, FILE_RENAME_INFORMATION,
};
use windows_sys::Win32::Foundation::RtlNtStatusToDosError;
use windows_sys::Win32::System::IO::IO_STATUS_BLOCK;
use windows_sys::Win32::System::WindowsProgramming::DRIVE_FIXED;
use windows_sys::Win32::{Foundation::HANDLE, Storage::FileSystem::*};
fn open(path: &Path, mutation: bool, directory: bool) -> Result<File, RpcError> {
    let access = FILE_READ_ATTRIBUTES
        | if directory {
            FILE_LIST_DIRECTORY | FILE_TRAVERSE
        } else {
            FILE_READ_DATA
        }
        | if mutation { DELETE } else { 0 };
    let file = std::fs::OpenOptions::new()
        .access_mode(access)
        .share_mode(if directory {
            FILE_SHARE_READ | FILE_SHARE_WRITE
        } else {
            FILE_SHARE_READ
        })
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS)
        .open(path)
        .map_err(io_error)?;
    info(&file)?;
    Ok(file)
}
fn raw(file: &File) -> Result<BY_HANDLE_FILE_INFORMATION, RpcError> {
    let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
    // SAFETY: live owned File handle and correctly sized writable output.
    if unsafe { GetFileInformationByHandle(file.as_raw_handle() as HANDLE, &mut info) } == 0 {
        return Err(io_error(std::io::Error::last_os_error()));
    }
    Ok(info)
}
pub fn anchor(path: &Path) -> Result<(File, Vec<File>), RpcError> {
    let mut full = PathBuf::new();
    let mut held = Vec::new();
    for part in path.components() {
        match part {
            std::path::Component::Prefix(p) => {
                if !matches!(p.kind(), std::path::Prefix::Disk(_)) {
                    return Err(err(
                        "UNSAFE_ROOT",
                        "Only explicit local drive-letter directories are supported.",
                    ));
                }
                full.push(p.as_os_str());
            }
            std::path::Component::RootDir => {
                full.push("\\");
                let w: Vec<u16> = full.as_os_str().encode_wide().chain(Some(0)).collect();
                if unsafe { GetDriveTypeW(w.as_ptr()) } != DRIVE_FIXED {
                    return Err(err(
                        "FILESYSTEM_UNSUPPORTED",
                        "Only a local fixed drive is supported in this desktop slice.",
                    ));
                }
                held.push(open(&full, false, true)?);
            }
            std::path::Component::Normal(s) => {
                policy::component(
                    s.to_str()
                        .ok_or_else(|| err("UNSAFE_ROOT", "A Unicode folder path is required."))?,
                )?;
                full.push(s);
                held.push(open(&full, false, true)?);
            }
            _ => return Err(err("UNSAFE_ROOT", "Choose a normalized local directory.")),
        }
    }
    let file = held
        .pop()
        .ok_or_else(|| err("UNSAFE_ROOT", "A local folder is required."))?;
    Ok((file, held))
}
pub fn supported_root(file: &File) -> Result<(), RpcError> {
    let mut name = [0u16; 32];
    // SAFETY: valid file handle, optional null outputs, and bounded filesystem-name buffer.
    if unsafe {
        GetVolumeInformationByHandleW(
            file.as_raw_handle() as HANDLE,
            std::ptr::null_mut(),
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            name.as_mut_ptr(),
            name.len() as u32,
        )
    } == 0
    {
        return Err(io_error(std::io::Error::last_os_error()));
    }
    let n = name.iter().position(|c| *c == 0).unwrap_or(name.len());
    if String::from_utf16_lossy(&name[..n]) != "NTFS" {
        return Err(err("FILESYSTEM_UNSUPPORTED","This implementation requires local NTFS. Other Windows filesystems need separate qualification."));
    }
    Ok(())
}
pub fn info(file: &File) -> Result<Info, RpcError> {
    let i = raw(file)?;
    let m = file.metadata().map_err(io_error)?;
    if i.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
        || (!m.is_dir() && !m.is_file())
        || (m.is_file() && i.nNumberOfLinks != 1)
    {
        return Err(err(
            "UNSAFE_OBJECT",
            "Reparse points, junctions, special files and multiply-linked files are refused.",
        ));
    }
    let identity = format!(
        "{}:{}:{}:{}:{}",
        i.dwVolumeSerialNumber,
        i.nFileIndexHigh,
        i.nFileIndexLow,
        i.ftCreationTime.dwHighDateTime,
        i.ftCreationTime.dwLowDateTime
    );
    let mut basic: FILE_BASIC_INFO = unsafe { std::mem::zeroed() };
    if unsafe {
        GetFileInformationByHandleEx(
            file.as_raw_handle() as HANDLE,
            FileBasicInfo,
            (&mut basic as *mut FILE_BASIC_INFO).cast(),
            std::mem::size_of::<FILE_BASIC_INFO>() as u32,
        )
    } == 0
    {
        return Err(io_error(std::io::Error::last_os_error()));
    }
    let mut value = version(&identity, &m, &basic.ChangeTime.to_string());
    value.hidden = i.dwFileAttributes & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM) != 0;
    Ok(value)
}
pub fn open_relative(root: &SafeRoot, rel: &str, mutation: bool) -> Result<Opened, RpcError> {
    if rel.is_empty() {
        return Ok(Opened {
            file: root.file.try_clone().map_err(io_error)?,
            _ancestors: vec![],
            relative: String::new(),
        });
    }
    let mut path = root.path.clone();
    let mut held = Vec::new();
    let parts: Vec<_> = rel.split('/').collect();
    let volume = raw(&root.file)?.dwVolumeSerialNumber;
    for (n, part) in parts.iter().enumerate() {
        path.push(part);
        let last = n + 1 == parts.len();
        // Determine the final kind without following it; ancestors are already pinned.
        let meta = std::fs::symlink_metadata(&path).map_err(io_error)?;
        let f = open(&path, last && mutation, meta.is_dir())?;
        let metadata = raw(&f)?;
        if metadata.dwFileAttributes & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM) != 0 {
            return Err(err(
                "ACCESS_DENIED",
                "Hidden and system objects or ancestors are not accessible.",
            ));
        }
        if metadata.dwVolumeSerialNumber != volume {
            return Err(err("UNSAFE_OBJECT", "Cross-volume objects are refused."));
        }
        if !last && !f.metadata().map_err(io_error)?.is_dir() {
            return Err(err("NOT_A_DIRECTORY", "An ancestor is not a directory."));
        }
        held.push(f);
    }
    let file = held
        .pop()
        .ok_or_else(|| err("UNSAFE_PATH", "A relative object is required."))?;
    Ok(Opened {
        file,
        _ancestors: held,
        relative: rel.into(),
    })
}
pub fn names(root: &SafeRoot, object: &Opened) -> Result<Vec<String>, RpcError> {
    if !object.file.metadata().map_err(io_error)?.is_dir() {
        return Err(err("NOT_A_DIRECTORY", "Select a directory to browse."));
    }
    let mut result = Vec::new();
    for e in std::fs::read_dir(root.path.join(&object.relative)).map_err(io_error)? {
        let e = e.map_err(io_error)?;
        result.push(e.file_name().to_str().unwrap_or("\0").to_string());
        if result.len() > 5000 {
            return Err(err(
                "INDEX_LIMIT",
                "A directory exceeds the 5,000-entry limit.",
            ));
        }
    }
    Ok(result)
}
pub fn mkdir(root: &SafeRoot, name: &str) -> Result<(), RpcError> {
    std::fs::create_dir(root.path.join(name)).map_err(|e|if e.kind()==std::io::ErrorKind::AlreadyExists{err("TRASH_UNTRUSTED","Managed trash exists without a saved identity. Preserve it for owner recovery; no contents were adopted.")}else{io_error(e)})
}
pub fn rename(
    _root: &SafeRoot,
    source: &Opened,
    _rel: &str,
    dest: &Opened,
    name: &str,
) -> Result<(), RpcError> {
    let utf: Vec<u16> = name.encode_utf16().collect();
    let offset = std::mem::offset_of!(FILE_RENAME_INFORMATION, FileName);
    let length = offset + (utf.len() + 1) * 2;
    // usize storage provides the alignment required by FILE_RENAME_INFORMATION and its flexible tail.
    let mut buffer = vec![0usize; length.div_ceil(std::mem::size_of::<usize>())];
    unsafe {
        let p = buffer.as_mut_ptr().cast::<FILE_RENAME_INFORMATION>();
        (*p).Anonymous.ReplaceIfExists = 0;
        (*p).RootDirectory = dest.file.as_raw_handle() as HANDLE;
        (*p).FileNameLength = (utf.len() * 2) as u32;
        std::ptr::copy_nonoverlapping(
            utf.as_ptr(),
            buffer.as_mut_ptr().cast::<u8>().add(offset).cast::<u16>(),
            utf.len(),
        );
        let mut status_block: IO_STATUS_BLOCK = std::mem::zeroed();
        // The Win32 wrapper rejected this handle-relative operation with error
        // 87 on the Windows 2022 CI runner. Use the documented native relative
        // rename, NOT an absolute-path/reopen or replace-existing fallback.
        // SAFETY: source is a live, synchronous (non-OVERLAPPED) owned handle
        // with DELETE access. Destination and all ancestors remain pinned.
        // The aligned buffer and status block stay live through completion;
        // FileNameLength describes only the validated leaf's UTF-16 bytes.
        let status = NtSetInformationFile(
            source.file.as_raw_handle() as HANDLE,
            &mut status_block,
            p.cast(),
            length as u32,
            FileRenameInformation,
        );
        if status != 0 {
            return Err(io_error(std::io::Error::from_raw_os_error(
                RtlNtStatusToDosError(status) as i32,
            )));
        }
    }
    Ok(())
}

pub fn location(file: &File, _path: &Path) -> Result<PathBuf, RpcError> {
    let mut output = vec![0u16; 4096];
    let n = unsafe {
        GetFinalPathNameByHandleW(
            file.as_raw_handle() as HANDLE,
            output.as_mut_ptr(),
            output.len() as u32,
            FILE_NAME_NORMALIZED | VOLUME_NAME_DOS,
        )
    } as usize;
    if n == 0 || n >= output.len() {
        return Err(err(
            "UNSAFE_ROOT",
            "The normalized folder path is unavailable or too long.",
        ));
    }
    let value = String::from_utf16(&output[..n])
        .map_err(|_| err("UNSAFE_ROOT", "The path is not representable as Unicode."))?;
    let trimmed = value.strip_prefix("\\\\?\\").unwrap_or(&value);
    Ok(PathBuf::from(trimmed))
}

pub fn writable(
    root: &SafeRoot,
    parent: &Opened,
    name: &str,
    create: bool,
) -> Result<File, RpcError> {
    let path = root.path.join(&parent.relative).join(name);
    let file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create_new(create)
        .share_mode(0)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
        .open(path)
        .map_err(io_error)?;
    info(&file)?;
    Ok(file)
}
pub fn delete(_root: &SafeRoot, _rel: &str, opened: &Opened) -> Result<(), RpcError> {
    let disposition = FILE_DISPOSITION_INFO { DeleteFile: 1 };
    // SAFETY: the file was opened with DELETE access, identity checked and retained.
    if unsafe {
        SetFileInformationByHandle(
            opened.file.as_raw_handle() as HANDLE,
            FileDispositionInfo,
            (&disposition as *const FILE_DISPOSITION_INFO).cast(),
            std::mem::size_of::<FILE_DISPOSITION_INFO>() as u32,
        )
    } == 0
    {
        return Err(io_error(std::io::Error::last_os_error()));
    }
    Ok(())
}
pub fn mkdir_private(root: &SafeRoot, name: &str) -> Result<(), RpcError> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, LocalFree},
        Security::{
            Authorization::{
                ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            },
            GetTokenInformation, TokenUser, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER,
        },
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    };
    // This creates the protected DACL at creation, not via an insecure inherited
    // ACL followed by repair. Native token/ACL/share-mode execution is unqualified.
    unsafe {
        let mut token = std::ptr::null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(io_error(std::io::Error::last_os_error()));
        }
        let mut len = 0;
        GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut len);
        if len == 0 || len > 65536 {
            CloseHandle(token);
            return Err(err("STAGING_ACL", "The owner token could not be bounded."));
        }
        let mut buf = vec![0usize; (len as usize).div_ceil(std::mem::size_of::<usize>())];
        let ok = GetTokenInformation(token, TokenUser, buf.as_mut_ptr().cast(), len, &mut len);
        CloseHandle(token);
        if ok == 0 {
            return Err(io_error(std::io::Error::last_os_error()));
        }
        let user = &*(buf.as_ptr() as *const TOKEN_USER);
        let mut sid = std::ptr::null_mut();
        if ConvertSidToStringSidW(user.User.Sid, &mut sid) == 0 {
            return Err(io_error(std::io::Error::last_os_error()));
        }
        let mut n = 0;
        while n < 1024 && *sid.add(n) != 0 {
            n += 1;
        }
        let text = String::from_utf16_lossy(std::slice::from_raw_parts(sid, n));
        LocalFree(sid.cast());
        let sddl: Vec<u16> = format!("D:P(A;OICI;FA;;;{text})")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let mut sd = std::ptr::null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            1,
            &mut sd,
            std::ptr::null_mut(),
        ) == 0
        {
            return Err(io_error(std::io::Error::last_os_error()));
        }
        let sa = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        let path: Vec<u16> = root
            .path
            .join(name)
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect();
        let result = CreateDirectoryW(path.as_ptr(), &sa);
        let error = std::io::Error::last_os_error();
        LocalFree(sd);
        if result == 0 {
            return Err(if error.kind() == std::io::ErrorKind::AlreadyExists {
                err(
                    "STAGING_UNTRUSTED",
                    "A staging directory exists without its saved identity. It was not adopted.",
                )
            } else {
                io_error(error)
            });
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_relative_rename_is_cross_directory_and_never_replaces_a_collision() {
        let base = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent().unwrap().parent().unwrap().join("test-fixtures");
        std::fs::create_dir_all(&base).unwrap();
        let temp = tempfile::Builder::new().prefix("win-relative-rename-")
            .tempdir_in(base).unwrap();
        std::fs::create_dir(temp.path().join("from")).unwrap();
        std::fs::create_dir(temp.path().join("to")).unwrap();
        std::fs::write(temp.path().join("from/source.bin"), b"SYNTHETIC_SOURCE").unwrap();
        std::fs::write(temp.path().join("to/keep.bin"), b"PRESERVE_EXISTING").unwrap();
        let root = SafeRoot::open(temp.path(), None).unwrap();
        let source = root.open_relative("from/source.bin", true).unwrap();
        let identity = info(&source.file).unwrap().identity;
        let destination = root.open_relative("to", false).unwrap();
        // Call the native primitive itself, bypassing the user-facing collision
        // precheck: the kernel must enforce no-replace even at the rename race.
        let collision = rename(&root, &source, "from/source.bin", &destination, "keep.bin")
            .unwrap_err();
        assert_eq!(collision.code, "NAME_COLLISION");
        assert_eq!(std::fs::read(temp.path().join("to/keep.bin")).unwrap(), b"PRESERVE_EXISTING");
        rename(&root, &source, "from/source.bin", &destination, "moved.bin").unwrap();
        assert_eq!(info(&source.file).unwrap().identity, identity);
        drop(source);
        assert!(!temp.path().join("from/source.bin").exists());
        assert_eq!(root.info("to/moved.bin").unwrap().identity, identity);
        assert_eq!(std::fs::read(temp.path().join("to/moved.bin")).unwrap(), b"SYNTHETIC_SOURCE");
    }
}
