//! Transport keys are protected outside SQLite. Windows uses per-user DPAPI.
//! The feature-gated non-Windows wrapper is explicitly a DEVELOPMENT TEST vault.
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use narada_protocol::{IdentityView, RpcError};
use rcgen::{
    CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, KeyPair, KeyUsagePurpose,
};
use ring::rand::{SecureRandom, SystemRandom};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{io::Write, path::Path, sync::Arc};
use x509_parser::prelude::*;
use zeroize::{Zeroize, Zeroizing};

pub const IDENTITY_FILE: &str = "transport.identity";
pub const TLS_NAME: &str = "narada.local";
const MAGIC: &[u8] = b"NARADA-ID-1\0";
#[derive(Clone)]
pub struct Identity {
    pub certificate: Vec<u8>,
    pub device_id: String,
    pub fingerprint: String,
    key: Arc<Zeroizing<Vec<u8>>>,
    pub protection: String,
}
#[derive(Serialize, Deserialize, Zeroize)]
#[serde(deny_unknown_fields)]
struct SecretDocument {
    key: String,
    certificate: String,
}
impl Drop for SecretDocument {
    fn drop(&mut self) {
        self.zeroize();
    }
}
pub fn err(code: &str, message: &str) -> RpcError {
    RpcError::new(code, message)
}
pub fn random<const N: usize>() -> Result<[u8; N], RpcError> {
    let mut b = [0u8; N];
    SystemRandom::new().fill(&mut b).map_err(|_| {
        err(
            "ENTROPY_UNAVAILABLE",
            "Secure randomness is unavailable. No weak identity or invitation was created.",
        )
    })?;
    Ok(b)
}
pub fn fingerprint(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
pub fn validate_certificate(der: &[u8]) -> Result<String, RpcError> {
    if der.is_empty() || der.len() > 4096 {
        return Err(err(
            "INVALID_CERTIFICATE",
            "The transport certificate is outside the permitted size.",
        ));
    }
    let (rest, cert) = parse_x509_certificate(der).map_err(|_| {
        err(
            "INVALID_CERTIFICATE",
            "The transport certificate is invalid.",
        )
    })?;
    let algorithm = &cert.public_key().algorithm;
    let p256 = algorithm.algorithm.to_id_string() == "1.2.840.10045.2.1"
        && algorithm
            .parameters
            .as_ref()
            .and_then(|p| p.as_oid().ok())
            .is_some_and(|o| o.to_id_string() == "1.2.840.10045.3.1.7");
    if !rest.is_empty()
        || !p256
        || cert.subject() != cert.issuer()
        || !cert.validity().is_valid()
        || cert.verify_signature(None).is_err()
    {
        return Err(err("INVALID_CERTIFICATE","A currently valid, self-signed P-256 transport certificate is required. Check the device clock."));
    }
    Ok(fingerprint(cert.public_key().raw))
}
impl Identity {
    pub fn generate(protection: String) -> Result<Self, RpcError> {
        let key = KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(|_| {
            err(
                "KEYSTORE_UNAVAILABLE",
                "A transport identity could not be generated.",
            )
        })?;
        let mut params = CertificateParams::new(vec![TLS_NAME.into()])
            .map_err(|_| err("KEYSTORE_UNAVAILABLE", "Certificate configuration failed."))?;
        params.distinguished_name = DistinguishedName::new();
        params
            .distinguished_name
            .push(DnType::CommonName, "NARADA transport identity");
        params.not_before = ::time::OffsetDateTime::now_utc() - ::time::Duration::minutes(5);
        params.not_after = ::time::OffsetDateTime::now_utc() + ::time::Duration::days(3650);
        params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
        params.extended_key_usages = vec![
            ExtendedKeyUsagePurpose::ServerAuth,
            ExtendedKeyUsagePurpose::ClientAuth,
        ];
        let cert = params
            .self_signed(&key)
            .map_err(|_| err("KEYSTORE_UNAVAILABLE", "Certificate creation failed."))?;
        Self::from_parts(cert.der().to_vec(), key.serialize_der(), protection)
    }
    fn from_parts(
        certificate: Vec<u8>,
        key: Vec<u8>,
        protection: String,
    ) -> Result<Self, RpcError> {
        let key = Zeroizing::new(key);
        let device_id = validate_certificate(&certificate)?;
        let fp = fingerprint(&certificate);
        let identity = Self {
            certificate,
            device_id,
            fingerprint: fp,
            key: Arc::new(key),
            protection,
        };
        rustls::sign::CertifiedKey::from_der(
            vec![identity.cert()],
            identity.private_key(),
            &rustls::crypto::ring::default_provider(),
        )
        .map_err(|_| {
            err(
                "IDENTITY_CORRUPT",
                "The protected private key does not match its public certificate.",
            )
        })?;
        Ok(identity)
    }
    pub fn cert(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.certificate.clone())
    }
    pub fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivatePkcs8KeyDer::from(self.key.as_ref().to_vec()).into()
    }
    pub fn view(&self) -> IdentityView {
        IdentityView {
            state: "ready".into(),
            protection: self.protection.clone(),
            device_id: Some(self.device_id.clone()),
            fingerprint: Some(self.fingerprint.clone()),
            error: None,
        }
    }
    pub fn open(dir: &Path, expected: Option<(&str, &str)>) -> Result<Self, RpcError> {
        let path = dir.join(IDENTITY_FILE);
        let protection = Protector::open()?;
        match std::fs::read(&path) {
            Ok(blob) => {
                if blob.len() > 32_768 || !blob.starts_with(MAGIC) {
                    return Err(err(
                        "IDENTITY_CORRUPT",
                        "The protected identity is invalid. It has not been replaced.",
                    ));
                }
                let plaintext = Zeroizing::new(protection.unseal(&blob[MAGIC.len()..])?);
                let doc: SecretDocument = serde_json::from_slice(&plaintext).map_err(|_| {
                    err(
                        "IDENTITY_CORRUPT",
                        "The protected identity could not be decoded. It has not been reset.",
                    )
                })?;
                let cert = B64.decode(&doc.certificate).map_err(|_| {
                    err("IDENTITY_CORRUPT", "The protected certificate is invalid.")
                })?;
                let key = B64
                    .decode(&doc.key)
                    .map_err(|_| err("IDENTITY_CORRUPT", "The protected key is invalid."))?;
                let id = Self::from_parts(cert, key, protection.label())?;
                if expected.is_some_and(|(d, f)| d != id.device_id || f != id.fingerprint) {
                    return Err(err("IDENTITY_CHANGED","The protected identity does not match this workspace. Restore the original key; do not silently re-pair."));
                }
                Ok(id)
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                if expected.is_some() {
                    return Err(err("IDENTITY_KEY_MISSING","This workspace's protected identity is missing. Connections are blocked; restore the key or explicitly start a new workspace."));
                }
                let id = Self::generate(protection.label())?;
                let doc = SecretDocument {
                    key: B64.encode(id.key.as_ref().as_slice()),
                    certificate: B64.encode(&id.certificate),
                };
                let plaintext = Zeroizing::new(
                    serde_json::to_vec(&doc)
                        .map_err(|_| err("KEYSTORE_UNAVAILABLE", "Identity encoding failed."))?,
                );
                let mut blob = MAGIC.to_vec();
                blob.extend(protection.seal(&plaintext)?);
                let mut temp = tempfile::NamedTempFile::new_in(dir).map_err(|_| {
                    err(
                        "KEYSTORE_UNAVAILABLE",
                        "Protected identity storage is not writable.",
                    )
                })?;
                temp.write_all(&blob)
                    .and_then(|_| temp.as_file().sync_all())
                    .map_err(|_| {
                        err(
                            "KEYSTORE_UNAVAILABLE",
                            "The protected identity could not be made durable.",
                        )
                    })?;
                temp.persist_noclobber(path).map_err(|_| {
                    err(
                        "KEYSTORE_UNAVAILABLE",
                        "Identity publication failed; no existing key was overwritten.",
                    )
                })?;
                #[cfg(unix)]
                std::fs::File::open(dir)
                    .and_then(|f| f.sync_all())
                    .map_err(|_| {
                        err(
                            "KEYSTORE_UNAVAILABLE",
                            "Identity directory persistence failed.",
                        )
                    })?;
                Ok(id)
            }
            Err(_) => Err(err(
                "KEYSTORE_UNAVAILABLE",
                "The protected identity cannot be opened. No replacement has been generated.",
            )),
        }
    }
}
pub struct Protector {
    #[cfg(all(not(windows), feature = "dev-vault"))]
    key: Zeroizing<Vec<u8>>,
}
impl Protector {
    pub fn open() -> Result<Self, RpcError> {
        #[cfg(windows)]
        {
            Ok(Self {})
        }
        #[cfg(all(not(windows), feature = "dev-vault"))]
        {
            let value=Zeroizing::new(std::env::var("NARADA_DEV_VAULT_KEY").map_err(|_|err("KEYSTORE_UNAVAILABLE","This Linux development host needs an explicit test-vault key. Use the development launcher; production Windows uses DPAPI."))?);
            let key = hex::decode(value.as_str()).map_err(|_| {
                err(
                    "KEYSTORE_UNAVAILABLE",
                    "The development test-vault key must be 32 bytes in hex.",
                )
            })?;
            if key.len() != 32 {
                return Err(err(
                    "KEYSTORE_UNAVAILABLE",
                    "The development test-vault key must be 32 bytes in hex.",
                ));
            }
            Ok(Self {
                key: Zeroizing::new(key),
            })
        }
        #[cfg(all(not(windows), not(feature = "dev-vault")))]
        {
            Err(err("KEYSTORE_UNAVAILABLE","Production transport identities require Windows DPAPI. No plaintext or development fallback is enabled."))
        }
    }
    pub fn label(&self) -> String {
        if cfg!(windows) {
            "Windows DPAPI · current user"
        } else {
            "Development test vault · not OS-backed"
        }
        .into()
    }
    pub fn seal(&self, input: &[u8]) -> Result<Vec<u8>, RpcError> {
        #[cfg(windows)]
        {
            dpapi(input, true)
        }
        #[cfg(all(not(windows), feature = "dev-vault"))]
        {
            use aes_gcm::{
                aead::{Aead, KeyInit, Payload},
                Aes256Gcm, Nonce,
            };
            let nonce = random::<12>()?;
            let cipher = Aes256Gcm::new_from_slice(&self.key)
                .map_err(|_| err("KEYSTORE_UNAVAILABLE", "Test-vault initialization failed."))?;
            let mut result = nonce.to_vec();
            result.extend(
                cipher
                    .encrypt(
                        Nonce::from_slice(&nonce),
                        Payload {
                            msg: input,
                            aad: MAGIC,
                        },
                    )
                    .map_err(|_| err("KEYSTORE_UNAVAILABLE", "Identity wrapping failed."))?,
            );
            Ok(result)
        }
        #[cfg(all(not(windows), not(feature = "dev-vault")))]
        {
            let _ = input;
            Err(err(
                "KEYSTORE_UNAVAILABLE",
                "No supported secret protector is available.",
            ))
        }
    }
    pub fn unseal(&self, input: &[u8]) -> Result<Vec<u8>, RpcError> {
        #[cfg(windows)]
        {
            dpapi(input, false)
        }
        #[cfg(all(not(windows), feature = "dev-vault"))]
        {
            use aes_gcm::{
                aead::{Aead, KeyInit, Payload},
                Aes256Gcm, Nonce,
            };
            if input.len() < 28 {
                return Err(err(
                    "IDENTITY_CORRUPT",
                    "The wrapped identity is truncated.",
                ));
            }
            let cipher = Aes256Gcm::new_from_slice(&self.key)
                .map_err(|_| err("KEYSTORE_UNAVAILABLE", "Test-vault initialization failed."))?;
            cipher.decrypt(Nonce::from_slice(&input[..12]),Payload{msg:&input[12..],aad:MAGIC}).map_err(|_|err("KEYSTORE_LOCKED","The identity cannot be unwrapped with this user's key. No new identity was generated."))
        }
        #[cfg(all(not(windows), not(feature = "dev-vault")))]
        {
            let _ = input;
            Err(err(
                "KEYSTORE_UNAVAILABLE",
                "No supported secret protector is available.",
            ))
        }
    }
}
#[cfg(windows)]
fn dpapi(input: &[u8], encrypt: bool) -> Result<Vec<u8>, RpcError> {
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
    };
    if input.len() > 32768 {
        return Err(err(
            "IDENTITY_CORRUPT",
            "The identity exceeds the protected storage limit.",
        ));
    }
    let src = CRYPT_INTEGER_BLOB {
        cbData: input.len() as u32,
        pbData: input.as_ptr() as *mut u8,
    };
    let entropy = CRYPT_INTEGER_BLOB {
        cbData: MAGIC.len() as u32,
        pbData: MAGIC.as_ptr() as *mut u8,
    };
    let mut dest = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    // No LOCAL_MACHINE flag: the Windows user's DPAPI context owns this identity.
    unsafe {
        let ok = if encrypt {
            CryptProtectData(
                &src,
                std::ptr::null(),
                &entropy,
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut dest,
            )
        } else {
            CryptUnprotectData(
                &src,
                std::ptr::null_mut(),
                &entropy,
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut dest,
            )
        };
        if ok == 0 {
            return Err(err("KEYSTORE_LOCKED","Windows DPAPI could not access this identity in the current user context. No replacement was created."));
        }
        let result = std::slice::from_raw_parts(dest.pbData, dest.cbData as usize).to_vec();
        if !encrypt {
            std::slice::from_raw_parts_mut(dest.pbData, dest.cbData as usize).zeroize();
        }
        LocalFree(dest.pbData.cast());
        Ok(result)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn certificate_identifies_public_key_and_has_no_workspace_label() {
        let a = Identity::generate("test".into()).unwrap();
        let b = Identity::generate("test".into()).unwrap();
        assert_ne!(a.fingerprint, b.fingerprint);
        assert_eq!(validate_certificate(&a.certificate).unwrap(), a.device_id);
    }
    #[test]
    fn malformed_certificates_fail() {
        for b in [vec![], vec![0; 5000], vec![1, 2, 3]] {
            assert!(validate_certificate(&b).is_err());
        }
    }
    #[test]
    fn certificate_and_private_key_must_match() {
        let a = Identity::generate("test".into()).unwrap();
        let b = Identity::generate("test".into()).unwrap();
        assert!(Identity::from_parts(a.certificate, b.key.to_vec(), "test".into()).is_err());
    }
    #[cfg(all(not(windows), feature = "dev-vault"))]
    #[test]
    fn authenticated_wrapper_rejects_wrong_key_and_tampering() {
        let a = Protector {
            key: Zeroizing::new(vec![1; 32]),
        };
        let b = Protector {
            key: Zeroizing::new(vec![2; 32]),
        };
        let mut data = a.seal(b"private PKCS8 data").unwrap();
        assert!(!data.windows(17).any(|w| w == b"private PKCS8 data"));
        assert_eq!(a.unseal(&data).unwrap(), b"private PKCS8 data");
        assert!(b.unseal(&data).is_err());
        data[15] ^= 1;
        assert!(a.unseal(&data).is_err());
    }
    #[cfg(windows)]
    #[test]
    fn dpapi_round_trip_and_tamper() {
        let p = Protector::open().unwrap();
        let mut data = p.seal(b"private transport key").unwrap();
        assert_eq!(p.unseal(&data).unwrap(), b"private transport key");
        data[20] ^= 1;
        assert!(p.unseal(&data).is_err());
    }
}
