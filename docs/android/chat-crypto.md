# Android chat — crypto, binding feasibility and review gate

[Index](README.md) · [Wire protocol](chat-protocol.md) · [Recovery](chat-recovery.md)

## Decision and limits of the gate

Use **vodozemac 0.11.1**, locked in the standalone `apps/android/native-olm/Cargo.lock`, behind a small **jni 0.21.1** Rust adapter. Default features are disabled; the wrapper explicitly requires **SessionConfig v1**, matching the actual desktop's Olm v1 with an **8-byte /64-bit MAC**. Experimental-v2 mode, custom ratchets, plaintext/TLS-only alternatives, third-party JNI forks and runtime-downloaded libraries are not fallbacks.

This is a **compiled integration feasibility decision**, not an independently reviewed Android binding. Historical upstream auditing does not certify this library release, this JNI wrapper, the application transactions, platform keystore/storage or the truncated-MAC deployment choice. Independent review remains blocking.

| Build component | Recorded pin / scope |
|---|---|
| Rust |1.99.1; Linux x86_64 host |
| NDK |30.0.16248370 (r30); API29 native floor |
| Android ABIs |arm64-v8a, armeabi-v7a, x86_64, x86 |
| Adapter |`narada_olm`0.1.0; cdylib plus rlib; bridge format 1 |
| Kotlin integration |`NativeOlmBridge` / `RustOlmCryptography`; exact version/mode checks |
| Android platform |min29/target36, JDK 17, AGP8.13.2 |

The build receipt binds the three Rust source/lock inputs to all five generated libraries. The APK audit separately verifies generated→merged, reproduces NDK `llvm-strip --strip-unneeded`, and compares that output and AGP's stripped output **byte-for-byte** with each packaged Olm library. Direct equality to the pre-AGP binary would be wrong. All four Olm ELF LOAD alignments meet16 KiB;64-bit bundled dependencies and APK ZIP alignment were also inspected. ELF machine, exported JNI symbol, RELRO/BIND_NOW and non-executable stack checks passed. **None of this executes the libraries on Android.**

## Stateless native boundary

Each call parses a strict, bounded request and returns a candidate account/session/output. No persistent native global handles, filesystem/network I/O or internal commit occurs. Kotlin commits candidates together with the matching history/envelope/receipt in the existing protected repository. A failed candidate or failed commit cannot authorize a later send/ACK.

Bounds include512 KiB JNI input,64 KiB account,96 KiB session, two sessions/peer,8500-byte inner body and13000-byte ciphertext. Unknown/duplicate fields, unsupported commands/configurations, invalid keys/pickles, malformed messages and incompatible sessions are refused. A reopened session must retain its identity and v1 configuration. There is no “unavailable crypto → allow plaintext” path.

Account public keys, signed bundles, session IDs and retained continuity are validated when protected state loads or persists. Partial state replacement/damage fails closed. This does **not** establish monotonic whole-state rollback protection against restoring an older valid aggregate and matching metadata.

## Key ownership and memory

- AndroidKeyStore P256 transport identity signs/anchors the messaging bundle. TLS and peer grants remain required in addition to Olm encryption.
- AndroidKeyStore AES256-GCM protects the no-backup aggregate. Room 3 stores the non-secret enrollment anchor, not chat plaintext, account/session pickles, drafts or content keys.
- Olm account/session secrets are **software cryptographic state**, protected at rest. They are not non-exportable hardware ratchet keys. Hardware capability reports are not attestation.
- Existing transport/store identity is not regenerated on missing/corrupt evidence. Messaging enrollment is explicit and absent from migrations.
- Temporary byte buffers are cleared where controlled. Java immutable strings, GC copies, JNI/library temporaries and process memory are not guaranteed erased. `FLAG_SECURE` is a screen-capture mitigation, not memory isolation or control of an external IME.

## Dependency notices and provenance

The102 locked-package inventory and hashes are in `artifacts/android-phase-5/native-dependencies.json`. It includes target/build-only dependencies and broad Rust copyright material: **not an attested binary SBOM or legal approval**. The compressed source asset is498105 bytes; its verified expanded text is16956338 bytes. AGP expands `.gz` while merging assets, so the actual APK member is `assets/native-olm-notices.txt`, checked against the decompressed source hash. No runtime network fetch is needed for notices.

NDK download size/SHA1 and computed SHA256 were recorded; Rust target archives were verified against the official HTTPS manifest. Maven checksums are TOFU over the selected official repositories. Publisher-signature/advisory/license review is not thereby complete.

Reference material: <https://docs.rs/vodozemac/0.11.1/vodozemac/>, <https://raw.githubusercontent.com/matrix-org/vodozemac/0.11.1/CHANGELOG.md>, <https://developer.android.com/ndk/downloads>. These are dependency/platform references, not approval of NARADA.

## Independent reviewer checklist — all open

- Confirm Olm v1 compatibility and explicitly accept or reject the64-bit MAC threat/trial budget; review identity, OTK, sender/session and inner/outer binding.
- Review native parsing, JNI exception/panic/size boundaries, pickle reconstruction, dependency features and memory lifetime/zeroization limitations.
- Review repository mutex/continuity/commit ordering, crash windows, ratchet/envelope/inbox atomicity and duplicate receipt semantics.
- Exercise damaged/missing/partial and whole-state restored stores, enrollment anchors, real AndroidKeyStore invalidation and no-fallback behavior.
- Review lifecycle/permission races, logging/diagnostics/backups, UI state, notifications and external IME/accessibility exposure.
- Review current advisories, licensing/notice scope and locked dependency provenance; approve the actual packaged artifact, not merely a source version.
- Produce an accountable review record with scope, artifact/source hashes, findings and disposition. No such approval is claimed here.
