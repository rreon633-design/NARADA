//! Windows-only owner SID ACL + process-token verification. Needs native Windows qualification.
use sha2::{Digest, Sha256};
use std::{
    ffi::c_void,
    io,
    os::windows::{ffi::OsStrExt, io::AsRawHandle},
    path::Path,
    ptr::null_mut,
    time::Duration,
};
use tokio::net::windows::named_pipe::{
    ClientOptions, NamedPipeClient, NamedPipeServer, ServerOptions,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree, HANDLE},
    Security::{
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
        },
        GetTokenInformation, TokenUser, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER,
    },
    System::{
        Pipes::{GetNamedPipeClientProcessId, GetNamedPipeServerProcessId},
        Threading::{
            GetCurrentProcess, OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION,
        },
    },
};
struct OwnedHandle(HANDLE);
impl Drop for OwnedHandle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}
fn process_sid(process: HANDLE) -> io::Result<String> {
    unsafe {
        let mut token = null_mut();
        if OpenProcessToken(process, TOKEN_QUERY, &mut token) == 0 {
            return Err(io::Error::last_os_error());
        }
        let token = OwnedHandle(token);
        let mut len = 0;
        GetTokenInformation(token.0, TokenUser, null_mut(), 0, &mut len);
        if len == 0 || len > 65536 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "Invalid owner token",
            ));
        }
        let mut buffer = vec![0usize; (len as usize).div_ceil(std::mem::size_of::<usize>())];
        if GetTokenInformation(
            token.0,
            TokenUser,
            buffer.as_mut_ptr().cast(),
            len,
            &mut len,
        ) == 0
        {
            return Err(io::Error::last_os_error());
        }
        let user = &*(buffer.as_ptr() as *const TOKEN_USER);
        let mut text = null_mut();
        if ConvertSidToStringSidW(user.User.Sid, &mut text) == 0 {
            return Err(io::Error::last_os_error());
        }
        let mut n = 0;
        while *text.add(n) != 0 {
            n += 1;
        }
        let sid = String::from_utf16_lossy(std::slice::from_raw_parts(text, n));
        LocalFree(text.cast());
        Ok(sid)
    }
}
fn owner_sid() -> io::Result<String> {
    unsafe { process_sid(GetCurrentProcess()) }
}
fn pid_is_owner(pid: u32) -> io::Result<bool> {
    unsafe {
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if process.is_null() {
            return Err(io::Error::last_os_error());
        }
        let process = OwnedHandle(process);
        Ok(process_sid(process.0)? == owner_sid()?)
    }
}
pub fn pipe_name(data_dir: &Path) -> io::Result<String> {
    // SID authenticates the user. This hash only separates that user's workspaces.
    let canonical = std::fs::canonicalize(data_dir)?;
    let mut hasher = Sha256::new();
    for unit in canonical.as_os_str().encode_wide() {
        hasher.update(unit.to_le_bytes());
    }
    let digest = hasher.finalize();
    let scope: String = digest[..12].iter().map(|b| format!("{b:02x}")).collect();
    Ok(format!(
        r"\\.\pipe\narada-phase1-{}-{}",
        owner_sid()?,
        scope
    ))
}
pub fn create_server(name: &str, first: bool) -> io::Result<NamedPipeServer> {
    unsafe {
        // Protected DACL grants only the current user's SID; no Everyone or remote clients.
        let sddl: Vec<u16> = format!("D:P(A;;GA;;;{})", owner_sid()?)
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let mut descriptor = null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            1,
            &mut descriptor,
            null_mut(),
        ) == 0
        {
            return Err(io::Error::last_os_error());
        }
        let mut attrs = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor,
            bInheritHandle: 0,
        };
        let result = ServerOptions::new()
            .first_pipe_instance(first)
            .reject_remote_clients(true)
            .create_with_security_attributes_raw(name, &mut attrs as *mut _ as *mut c_void);
        LocalFree(descriptor);
        result
    }
}
pub fn verify_client(pipe: &NamedPipeServer) -> io::Result<bool> {
    unsafe {
        let mut pid = 0;
        if GetNamedPipeClientProcessId(pipe.as_raw_handle() as HANDLE, &mut pid) == 0 {
            return Err(io::Error::last_os_error());
        }
        pid_is_owner(pid)
    }
}
pub async fn connect(data_dir: &Path) -> io::Result<NamedPipeClient> {
    let name = pipe_name(data_dir)?;
    let mut last = None;
    for _ in 0..30 {
        match ClientOptions::new().open(&name) {
            Ok(pipe) => {
                unsafe {
                    let mut pid = 0;
                    if GetNamedPipeServerProcessId(pipe.as_raw_handle() as HANDLE, &mut pid) == 0 {
                        return Err(io::Error::last_os_error());
                    }
                    if !pid_is_owner(pid)? {
                        return Err(io::Error::new(
                            io::ErrorKind::PermissionDenied,
                            "IPC server owner mismatch",
                        ));
                    }
                }
                return Ok(pipe);
            }
            Err(e) => {
                last = Some(e);
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        }
    }
    Err(last
        .unwrap_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "Local pipe unavailable")))
}
