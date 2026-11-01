//! Protected desktop transport identity and trusted connections. Root-scoped file routes are explicit; owner controls are never LAN permissions.
mod content;
mod discovery;
mod endpoint;
mod identity;
mod service;
mod store;
mod tls;
mod transport;
mod wire;
pub use service::Service;
