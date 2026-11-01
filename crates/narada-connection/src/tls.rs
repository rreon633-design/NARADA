//! Exact-certificate pins PLUS WebPKI validation and rustls handshake signatures.
//! There is deliberately no accept-all certificate verifier or TLS downgrade path.
use crate::identity::{err, fingerprint, validate_certificate, Identity, TLS_NAME};
use narada_protocol::RpcError;
use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, ServerName, UnixTime},
    server::danger::{ClientCertVerified, ClientCertVerifier},
    CertificateError, ClientConfig, DigitallySignedStruct, DistinguishedName, Error, RootCertStore,
    ServerConfig, SignatureScheme,
};
use std::{
    collections::HashMap,
    sync::{Arc, RwLock},
    time::Instant,
};

#[derive(Clone, Debug)]
pub struct AllowedPin {
    pub expires: Option<Instant>,
}
pub type Pins = Arc<RwLock<HashMap<String, AllowedPin>>>;
fn invalid() -> Error {
    Error::InvalidCertificate(CertificateError::UnknownIssuer)
}
fn roots(cert: &CertificateDer<'_>) -> Result<Arc<RootCertStore>, Error> {
    let mut roots = RootCertStore::empty();
    roots.add(cert.clone())?;
    Ok(Arc::new(roots))
}
fn signature(
    message: &[u8],
    cert: &CertificateDer<'_>,
    dss: &DigitallySignedStruct,
) -> Result<HandshakeSignatureValid, Error> {
    rustls::crypto::verify_tls13_signature(
        message,
        cert,
        dss,
        &rustls::crypto::ring::default_provider().signature_verification_algorithms,
    )
}
#[derive(Debug)]
struct PinnedServer {
    fingerprint: String,
    device_id: String,
}
impl ServerCertVerifier for PinnedServer {
    fn verify_server_cert(
        &self,
        end: &CertificateDer<'_>,
        inter: &[CertificateDer<'_>],
        name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        if !inter.is_empty()
            || fingerprint(end) != self.fingerprint
            || validate_certificate(end).map_err(|_| invalid())? != self.device_id
        {
            return Err(invalid());
        }
        rustls::client::WebPkiServerVerifier::builder_with_provider(
            roots(end)?,
            Arc::new(rustls::crypto::ring::default_provider()),
        )
        .build()
        .map_err(|_| invalid())?
        .verify_server_cert(end, inter, name, ocsp, now)
    }
    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Err(Error::General("TLS 1.2 is disabled".into()))
    }
    fn verify_tls13_signature(
        &self,
        m: &[u8],
        c: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        signature(m, c, d)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}
#[derive(Debug)]
struct PinnedClients {
    pins: Pins,
}
impl ClientCertVerifier for PinnedClients {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }
    fn verify_client_cert(
        &self,
        end: &CertificateDer<'_>,
        inter: &[CertificateDer<'_>],
        now: UnixTime,
    ) -> Result<ClientCertVerified, Error> {
        if !inter.is_empty() || validate_certificate(end).is_err() {
            return Err(invalid());
        }
        let allowed = self
            .pins
            .read()
            .map_err(|_| invalid())?
            .get(&fingerprint(end))
            .is_some_and(|p| p.expires.is_none_or(|deadline| Instant::now() < deadline));
        if !allowed {
            return Err(invalid());
        }
        rustls::server::WebPkiClientVerifier::builder_with_provider(
            roots(end)?,
            Arc::new(rustls::crypto::ring::default_provider()),
        )
        .build()
        .map_err(|_| invalid())?
        .verify_client_cert(end, inter, now)
    }
    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Err(Error::General("TLS 1.2 is disabled".into()))
    }
    fn verify_tls13_signature(
        &self,
        m: &[u8],
        c: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        signature(m, c, d)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}
pub fn server(identity: &Identity, pins: Option<Pins>) -> Result<Arc<ServerConfig>, RpcError> {
    let b = ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|_| err("TLS_CONFIG_FAILED", "TLS 1.3 initialization failed."))?;
    let b = if let Some(pins) = pins {
        b.with_client_cert_verifier(Arc::new(PinnedClients { pins }))
    } else {
        b.with_no_client_auth()
    };
    let mut c = b
        .with_single_cert(vec![identity.cert()], identity.private_key())
        .map_err(|_| err("IDENTITY_CORRUPT", "The protected TLS identity is invalid."))?;
    c.max_early_data_size = 0;
    c.send_tls13_tickets = 0;
    c.session_storage = Arc::new(rustls::server::NoServerSessionStorage {});
    c.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(c))
}
pub fn client(
    identity: Option<&Identity>,
    pin: &str,
    device_id: &str,
) -> Result<Arc<ClientConfig>, RpcError> {
    let b = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|_| err("TLS_CONFIG_FAILED", "TLS 1.3 initialization failed."))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedServer {
            fingerprint: pin.into(),
            device_id: device_id.into(),
        }));
    let mut c = if let Some(i) = identity {
        b.with_client_auth_cert(vec![i.cert()], i.private_key())
            .map_err(|_| err("IDENTITY_CORRUPT", "The client transport key is invalid."))?
    } else {
        b.with_no_client_auth()
    };
    c.enable_early_data = false;
    c.resumption = rustls::client::Resumption::disabled();
    c.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(c))
}
pub fn server_name() -> ServerName<'static> {
    ServerName::try_from(TLS_NAME)
        .expect("constant valid DNS name")
        .to_owned()
}
