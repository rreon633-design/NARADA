//! mDNS is a routing hint, never a trust decision. No stable identity is advertised.
use crate::{
    endpoint,
    identity::{err, fingerprint, random},
};
use chrono::Utc;
use mdns_sd::{IfKind, ServiceDaemon, ServiceEvent, ServiceInfo};
use narada_protocol::{DiscoveryCandidate, DiscoveryView, RpcError};
use std::{
    net::{IpAddr, Ipv4Addr},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::{Mutex, RwLock};
use tokio_util::sync::CancellationToken;
const SERVICE: &str = "_narada._tcp.local.";
struct Cache {
    state: String,
    message: String,
    entries: Vec<(DiscoveryCandidate, Instant, String)>,
}
struct Running {
    daemon: ServiceDaemon,
    cancel: CancellationToken,
    task: tokio::task::JoinHandle<()>,
}
#[derive(Clone)]
pub struct Discovery {
    cache: Arc<RwLock<Cache>>,
    running: Arc<Mutex<Option<Running>>>,
}
impl Default for Discovery {
    fn default() -> Self {
        Self {
            cache: Arc::new(RwLock::new(Cache {
                state: "disabled".into(),
                message: "Discovery is opt-in. Pairing codes and manual endpoints work without it."
                    .into(),
                entries: vec![],
            })),
            running: Arc::new(Mutex::new(None)),
        }
    }
}
impl Discovery {
    pub async fn snapshot(&self) -> DiscoveryView {
        let mut c = self.cache.write().await;
        c.entries
            .retain(|(_, t, _)| t.elapsed() < Duration::from_secs(60));
        DiscoveryView {
            state: c.state.clone(),
            message: c.message.clone(),
            candidates: c.entries.iter().map(|(c, _, _)| c.clone()).collect(),
        }
    }
    pub async fn stop(&self) {
        if let Some(r) = self.running.lock().await.take() {
            r.cancel.cancel();
            let _ = r.daemon.shutdown();
            let _ = r.task.await;
        }
        let mut c = self.cache.write().await;
        c.entries.clear();
        c.state = "disabled".into();
        c.message =
            "Discovery is off. Use a pairing code or a saved, pinned manual endpoint.".into();
    }
    pub async fn start(&self, ip: Ipv4Addr, port: u16) -> Result<(), RpcError> {
        self.stop().await;
        let daemon = ServiceDaemon::new().map_err(|_| {
            err(
                "DISCOVERY_UNAVAILABLE",
                "mDNS could not start. Manual pairing remains available.",
            )
        })?;
        let configure = || -> Result<_, RpcError> {
            daemon.disable_interface(IfKind::All).map_err(|_| {
                err(
                    "DISCOVERY_UNAVAILABLE",
                    "The discovery interface could not be restricted.",
                )
            })?;
            daemon
                .enable_interface(IfKind::Addr(IpAddr::V4(ip)))
                .map_err(|_| {
                    err(
                        "DISCOVERY_UNAVAILABLE",
                        "The selected interface is unavailable to mDNS.",
                    )
                })?;
            let ephemeral = hex::encode(random::<8>()?);
            let host = format!("n-{ephemeral}.local.");
            let instance = format!("narada-{ephemeral}");
            let mut service = ServiceInfo::new(
                SERVICE,
                &instance,
                &host,
                IpAddr::V4(ip),
                port,
                &[("v", "1")][..],
            )
            .map_err(|_| {
                err(
                    "DISCOVERY_UNAVAILABLE",
                    "The anonymous discovery record could not be created.",
                )
            })?;
            service.set_interfaces(vec![IfKind::Addr(IpAddr::V4(ip))]);
            daemon.register(service).map_err(|_| {
                err(
                    "DISCOVERY_UNAVAILABLE",
                    "The discovery announcement could not be registered.",
                )
            })?;
            daemon.browse(SERVICE).map_err(|_| {
                err(
                    "DISCOVERY_UNAVAILABLE",
                    "The local discovery browser could not start.",
                )
            })
        };
        let receiver = match configure() {
            Ok(r) => r,
            Err(e) => {
                let _ = daemon.shutdown();
                self.failed().await;
                return Err(e);
            }
        };
        {
            let mut c = self.cache.write().await;
            c.state = "searching".into();
            c.message="Selected-interface mDNS. Candidates are untrusted until paired; announcements contain no device name or certificate.".into();
        }
        let cancel = CancellationToken::new();
        let child = cancel.clone();
        let cache = self.cache.clone();
        let task = tokio::spawn(async move {
            loop {
                tokio::select! {
                 _=child.cancelled()=>break,
                 event=receiver.recv_async()=>match event{
                  Ok(ServiceEvent::ServiceResolved(info))=>{
                   if info.get_property_val_str("v")!=Some("1"){continue;}
                   let mut c=cache.write().await;c.entries.retain(|(_,t,_)|t.elapsed()<Duration::from_secs(60));
                   for addr in info.get_addresses_v4().into_iter().take(4){let address=format!("{addr}:{}",info.get_port());if endpoint::endpoint(&address).is_err()||addr.is_loopback()||(addr==ip&&info.get_port()==port){continue;}
                    let id=fingerprint(format!("{}:{address}",info.get_fullname()).as_bytes())[..24].to_string();let candidate=DiscoveryCandidate{id:id.clone(),endpoint:address,protocol:1,seen_at:Utc::now().to_rfc3339()};
                    c.entries.retain(|(v,_,_)|v.id!=id);if c.entries.len()>=32{c.entries.remove(0);}c.entries.push((candidate,Instant::now(),info.get_fullname().into()));
                   }
                  },
                  Ok(ServiceEvent::ServiceRemoved(_,name))=>cache.write().await.entries.retain(|(_,_,n)|n!=&name),
                  Err(_)=>{let mut c=cache.write().await;c.state="degraded".into();c.message="Discovery stopped. Use a pairing code or manual endpoint.".into();break;},
                  _=>{}
                 }
                }
            }
        });
        *self.running.lock().await = Some(Running {
            daemon,
            cancel,
            task,
        });
        Ok(())
    }
    pub async fn failed(&self) {
        let mut c = self.cache.write().await;
        c.state = "degraded".into();
        c.message="Discovery is unavailable on this network. Manual endpoint entry still verifies the saved identity.".into();
    }
}
