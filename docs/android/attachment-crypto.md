# Attachment crypto, tech stack and review gate

[Phase 6](phase-6.md) · [Olm baseline](chat-crypto.md) · [Protocol](attachments-protocol.md) · [Qualification](phase-6-7-device-checklist.md)

## Pinned decision

Use **age 0.12.1 with default features disabled**, standard X25519 recipient encryption and the library's age v1 / STREAM implementation. The adapter uses jni 0.21.1 and Rust 1.99.1; it is isolated from the desktop Cargo workspace. No custom cipher, nonce schedule, password mode or experimental crypto fallback is introduced. Desktop and Android use the actual compatible age implementation, not a mock format.

`native-age` is a small stateless JNI adapter. Its runtime contract is `narada-age/1;age/0.12.1;x25519/v1;max-plain/8388608`. Exports are `info`, `validIdentity`, `encrypt`, `decrypt`; they are not the Olm bridge's generic `call` entry point. The artifact audit verifies all four exports in all four Android ABIs.

A complete imported plaintext is bounded to 8,388,608 bytes in memory. Encryption creates a **fresh random X25519 identity** and one immutable ciphertext. Age state is not serialized for incremental encryption. Once bytes are staged, resume transfers only those bytes. An interrupted pre-commit encryption/import cannot reuse or reconstruct its nonce stream.

## Authentication order

1. Validate the canonical identity and bounded typed descriptor.
2. Verify ciphertext length and SHA-256; chunks also have exact expected length/hash.
3. Decrypt through the age library, authenticating its complete stream, including final-record/truncation handling.
4. Verify plaintext length and SHA-256 against the protected secret.
5. Only then issue a protected local receipt or proceed to an explicit provider export.

The full secret contains blob/version/ciphertext size/hash/chunk hashes, filename, plaintext size/hash and the age identity. It travels inside a typed **Olm-encrypted body**, bound to message/sender/recipient. The attachment digest is domain-separated as `NARADA-ANDROID-CHAT-ATTACHMENT-1`; the prior text digest is retained. Olm remains vodozemac 0.11.1 / v1 / 64-bit MAC, defaults off; JNI bridge 2 adds typed bodies, not an experimental-v2 fallback.

## Custody and evidence boundaries

- P-256 transport identity and AES-256-GCM sealing keys are AndroidKeyStore-owned in production, with no software identity/storage fallback.
- Olm account/session state and age identities are **software secrets inside the sealed aggregate**, not hardware-held ratchet/attachment keys. Canonical validation and in-process zeroing do not guarantee physical erasure of all VM/native copies.
- Room schema 3 anchors enrollment; protected payload 6 reads 1–6 while preserving ownership/AAD. Missing/damaged evidence fails closed. Continuity checks do not establish whole-state rollback resistance.
- 12 age and 20 Olm Rust tests passed on Linux. JNI host tests and actual Rust-peer format roundtrips passed. Host plus arm64-v8a, armeabi-v7a, x86_64 and x86 libraries built. **Android native execution: zero.**
- Generated→merged→reproduced NDK strip→packaged ELF provenance, exported JNI symbols, RELRO/BIND_NOW/non-executable stack and static 16 KiB alignment were checked. These are not device/runtime/security approval.

## Dependencies and notices

[Locked native inventory](../../artifacts/android-phase-6-7/native-dependencies-03.json) records 166 age-workspace and 103 Olm-workspace package rows (overlapping sets, including target/build-only packages). It is not a reviewed binary-reachability SBOM. Provided license/copyright/notice files are aggregated; the gzip assets expand to plaintext notice assets in the APK and are hash-checked there.

The age/age-core crates omit separate root license files. Four supplemental files were retrieved from the immutable repository commits reported by the Cargo-checksummed crates; [URL/hash receipts](../../artifacts/android-phase-6-7/age-upstream-notices.json) and the exact texts are included. Other missing separate texts remain explicitly listed in the inventory; a declaration is not a legal conclusion.

**Still required:** independent library/version/JNI/application/cryptographic protocol review; complete dependency/advisory and license-obligation review; AndroidKeyStore/Conscrypt and Windows interoperability; native memory/error/lifecycle assessment. Neither upstream reputation nor host test counts satisfy these gates. Do not ship this development build as an independently reviewed secure messenger.
