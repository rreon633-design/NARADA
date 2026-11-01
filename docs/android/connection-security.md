# Connection security, storage and recovery

[General privacy](security-storage.md) · [Protocol](connection-protocol.md) · [Native gates](phase-2-device-checklist.md)

## Key custody

Production uses AndroidKeyStore P-256 signing/verification with SHA-256 and a separate AES-256-GCM sealing key. Private/secret key encodings must be null; software-key serialization is not a recovery path. The generic-subject certificate contains no device attestation or device-properties request. A fresh signing challenge verifies that the certificate and private key match.

The transport key's hardware backing is **OS-reported**, not assumed or independently attested. AndroidKeyStore can be software-backed on a device/emulator; that is not an application fallback. StrongBox availability is not required. JVM fixtures use explicitly test-only exportable keys; Bouncy Castle is a **test dependency** and APK inspection confirms its classes are not bundled in the app.

## Durable enrollment and trust

1. Reserve a Room `connection_anchor` as **PREPARING before key generation**.
2. Generate the two owned aliases; validate the actual certificate and key possession.
3. Seal a versioned aggregate to `noBackupFilesDir/connections-v1/trust.bin` using standard AES-GCM with store-ID associated data.
4. Atomically write, fsync, read back and sync the directory; bind the anchor as READY to the same store/device/fingerprint.
5. A complete interrupted enrollment can finish binding. Incomplete/corrupt/missing evidence blocks; it does not generate replacement keys.

The aggregate contains public certificates/pins, attempts/stages, private endpoints, read-only remote grants/counters, local epochs, enabled/preferred selection and retry budget. It contains neither a persisted pairing token nor an exported transport private key. In the current payload6, the same seal also protects software Olm account/session material and age attachment identities; these are not hardware-held content keys. See [current storage/privacy](security-storage.md). Schema 2→3 preserves the old events/job/SAF ledger and adds an empty anchor table; it does not enroll an identity on upgrade.

Missing keys, missing ciphertext/anchor, mismatched identity, malformed sealed state or authentication-tag failure remain errors. Corruption is preserved. A repository never returns a successful mutation before the new bytes have been written.

AES-GCM authenticity detects modification, **not coherent rollback of a previously valid whole app state with usable keys**. Room markers/local generations do not create a hardware monotonic counter. Rooted-device compromise, rollback resistance, signing/backup custody and recovery require explicit review; none is silently claimed solved here.

## Revocation and race fences

- Persist the tombstone and advance the local epoch before reporting revocation. Close transports even when persistence fails, but do not report durable success.
- Recheck ownership/enabled state after key loading and handshake, before health, and before durable updates. A socket generation fence covers close racing with socket creation; newer sockets are not lost from cancellation tracking.
- Production health sockets stay inside a scoped IO operation so dispatcher cancellation cannot discard an acquired closeable result.
- Storage/key failures latch networking off in the current process until an explicit successful reopen/enable action. Automatic foreground refresh cannot clear that latch.
- The latch itself is not a durable substitute for a failed write. If disabling/revoking cannot be persisted, the prior valid saved state remains authoritative after process restart. Keep the app disconnected, fix the storage problem and verify/retry **both devices**; never describe the failed operation as completed.
- Phone revocation does not erase desktop trust, remote files or an ACK already processed remotely. Explicitly revoke the phone on the desktop as well.

Historical peer certificates remain structurally checked/readable after expiry (including tombstones); **every actual TLS handshake still requires current validity**. A new endpoint never changes either saved pin.

## Invitation and camera privacy

- Invitations are short-lived memory-only values. Preview state contains no token. Camera capture is single-use with monotonic expiry; result Intents carry no code.
- Manual input is not `rememberSaveable`; its subtree disables the saveable registry. Saved-state restoration tests verify it is dropped. Pairing/scanner windows use secure-window protection where applicable; manual code uses password-like display/input.
- Camera access is contextual and optional. CameraX frames are decoded locally by ZXing, closed/recycled and never saved/uploaded. The scanner times out after two minutes.
- Image import accepts content URIs only, ≤8 MiB encoded input, PNG/JPEG/WebP bounds ≤16 million pixels, and bounded sampled decode. The app does not fetch QR URLs. Provider blocking/cancellation and native image decoding remain device gates; size limits are not a promise that every third-party provider returns promptly.
- No token in Room/DataStore, saved activity results, logs, clipboard writes, backups or support reports. Immutable JVM strings cannot be guaranteed wiped from heap; owned mutable buffers are cleared where practical.

## Network and grants

Only a valid exact certificate+SPKI pin can receive bootstrap token bytes. Proof and normal health require mTLS. Desktop approval is local owner IPC/UI, never a peer route. A scanned label, discovered name or successful TCP connection is not authentication.

Android advertises/provides no inbound content grants. Displayed desktop permissions do not enable unimplemented files/chat/sync. Remote error text is not shown verbatim. Backup/cleartext remain disabled; no foreground-service or broad-storage permission is added.

For compile/target 36, INTERNET remains the normal LAN permission path. Review [Android local-network permission guidance](https://developer.android.com/privacy-and-security/local-network-permission) and [Play policy](https://support.google.com/googleplay/android-developer/answer/16935362?hl=en) before target 37/release. Do not add a nonexistent-at-this-target runtime prompt or disable Android's restrictions to make tests pass.

No independent security, vulnerability/license, OEM backup or production-release approval is represented by the host tests.
