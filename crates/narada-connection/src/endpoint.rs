use crate::identity::err;
use narada_protocol::{ConnectionConfig, InterfaceView, RpcError};
use std::net::{Ipv4Addr, SocketAddrV4};
/// Only literal private IPv4 or loopback; no DNS, redirects, credentials, paths or cloud metadata.
pub fn endpoint(value: &str) -> Result<SocketAddrV4, RpcError> {
    if value.len() > 80 {
        return Err(err(
            "INVALID_ENDPOINT",
            "Enter a private IPv4 address and port, without a path or credentials.",
        ));
    }
    let raw = value.strip_prefix("https://").unwrap_or(value);
    let addr: SocketAddrV4 = raw.parse().map_err(|_| {
        err(
            "INVALID_ENDPOINT",
            "Use a private IPv4 address and port, for example 192.168.1.20:45821.",
        )
    })?;
    if addr.port() == 0
        || !(addr.ip().is_private() || addr.ip().is_loopback())
        || addr.ip().is_unspecified()
        || addr.ip().is_broadcast()
    {
        return Err(err("ENDPOINT_NOT_LOCAL","Only private IPv4 and local-test loopback endpoints are supported. Public, multicast and link-local addresses are rejected."));
    }
    Ok(addr)
}
pub async fn interfaces() -> Result<Vec<InterfaceView>, RpcError> {
    #[cfg(windows)]
    {
        windows_interfaces().await
    }
    #[cfg(not(windows))]
    {
        let mut out = vec![InterfaceView {
            ip: "127.0.0.1".into(),
            name: "This development host".into(),
            profile: "loopback".into(),
            eligible: true,
            reason: None,
        }];
        for i in if_addrs::get_if_addrs()
            .map_err(|_| {
                err(
                    "INTERFACE_UNAVAILABLE",
                    "Network interfaces could not be inspected.",
                )
            })?
            .into_iter()
            .take(64)
        {
            if let std::net::IpAddr::V4(ip) = i.ip() {
                if !ip.is_loopback() {
                    out.push(InterfaceView{ip:ip.to_string(),name:i.name,profile:"unqualified development interface".into(),eligible:false,reason:Some("The Linux preview is loopback-only. Private LAN operation requires the Windows profile guard.".into())});
                }
            }
        }
        Ok(out)
    }
}
pub async fn validate_config(c: &ConnectionConfig) -> Result<Vec<InterfaceView>, RpcError> {
    let ip: Ipv4Addr = c.bind_ip.parse().map_err(|_| {
        err(
            "INVALID_INTERFACE",
            "Select an eligible local IPv4 interface.",
        )
    })?;
    if !(ip.is_private() || ip.is_loopback()) {
        return Err(err(
            "PUBLIC_NETWORK_BLOCKED",
            "Public and link-local interfaces are not eligible.",
        ));
    }
    if c.discovery && ip.is_loopback() {
        return Err(err(
            "DISCOVERY_UNAVAILABLE",
            "Loopback tests use pairing codes or a manual endpoint, not LAN discovery.",
        ));
    }
    let list = interfaces().await?;
    if !list.iter().any(|i| i.ip == c.bind_ip && i.eligible) {
        return Err(err("PUBLIC_NETWORK_BLOCKED","The selected interface is absent, public, or unqualified. No broader listener was enabled."));
    }
    Ok(list)
}
#[cfg(windows)]
async fn windows_interfaces() -> Result<Vec<InterfaceView>, RpcError> {
    // Fixed, read-only OS query: no user text is interpolated into PowerShell.
    // A missing/unknown/public category fails closed. Firewall rules are never opened automatically.
    let script="$ErrorActionPreference='Stop'; @([pscustomobject]@{ip='127.0.0.1';name='This computer only';profile='loopback'}; Get-NetIPAddress -AddressFamily IPv4 | ForEach-Object { $a=$_; $p=Get-NetConnectionProfile -InterfaceIndex $a.InterfaceIndex -ErrorAction SilentlyContinue; [pscustomobject]@{ip=$a.IPAddress;name=$a.InterfaceAlias;profile=[string]$p.NetworkCategory} }) | ConvertTo-Json -Compress";
    let system = std::env::var_os("SystemRoot").ok_or_else(|| {
        err(
            "INTERFACE_UNAVAILABLE",
            "The Windows system directory is unavailable.",
        )
    })?;
    let exe =
        std::path::PathBuf::from(system).join("System32/WindowsPowerShell/v1.0/powershell.exe");
    let text = tokio::time::timeout(
        std::time::Duration::from_secs(3),
        tokio::process::Command::new(exe)
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .creation_flags(0x08000000)
            .kill_on_drop(true)
            .output(),
    )
    .await
    .map_err(|_| {
        err(
            "INTERFACE_UNAVAILABLE",
            "Windows profile inspection timed out. LAN listeners remain closed.",
        )
    })?
    .map_err(|_| {
        err(
            "INTERFACE_UNAVAILABLE",
            "Windows network profile inspection failed.",
        )
    })?;
    if !text.status.success() || text.stdout.len() > 32_768 {
        return Err(err(
            "INTERFACE_UNAVAILABLE",
            "Windows network profiles could not be verified. LAN listeners remain closed.",
        ));
    }
    #[derive(serde::Deserialize)]
    struct Item {
        ip: String,
        name: String,
        profile: String,
    }
    let data: Vec<Item> = serde_json::from_slice(&text.stdout).map_err(|_| {
        err(
            "INTERFACE_UNAVAILABLE",
            "Windows returned an unrecognized profile response.",
        )
    })?;
    Ok(data
        .into_iter()
        .take(64)
        .map(|r| {
            let ip = r.ip.parse::<Ipv4Addr>().ok();
            let eligible = ip.is_some_and(|ip| {
                ip.is_loopback()
                    || (ip.is_private()
                        && matches!(r.profile.as_str(), "Private" | "DomainAuthenticated"))
            });
            InterfaceView {
                ip: r.ip,
                name: r
                    .name
                    .chars()
                    .filter(|c| !c.is_control())
                    .take(48)
                    .collect(),
                profile: r.profile,
                eligible,
                reason: if eligible {
                    None
                } else {
                    Some("Private or domain-authenticated Windows networks only.".into())
                },
            }
        })
        .collect())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn endpoints_do_not_allow_dns_public_metadata_or_url_credentials() {
        for s in [
            "https://example.org:443",
            "https://u:p@192.168.1.2:80",
            "169.254.169.254:80",
            "8.8.8.8:443",
            "224.0.0.251:5353",
            "0.0.0.0:443",
            "127.0.0.1:0",
            "192.168.1.2:443/path",
            "[::1]:80",
        ] {
            assert!(endpoint(s).is_err(), "{s}");
        }
        assert!(endpoint("https://192.168.1.2:45821").is_ok());
        assert!(endpoint("127.0.0.1:1234").is_ok());
    }
}
