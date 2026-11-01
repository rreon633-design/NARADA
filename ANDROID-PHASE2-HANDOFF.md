# NARADA — Android Phase 2 development handoff

**31 October 2026 · 0.2.0 (2) · Room 3 · min API 29 / target 36**

[Phase-wise plan](android-plan.md) · [Project/build setup](apps/android/README.md) · [Documentation index](docs/android/README.md) · [Measured validation](docs/android/phase-2-validation.md)

## Delivered

- Explicit nonexportable AndroidKeyStore P-256 transport identity and independent AES-GCM trust protection, enrollment anchor and fail-closed storage recovery.
- Real offline CameraX/ZXing QR input, bounded image import and manual paste; no code-bearing result Intent or saved-state token.
- One-time pinned desktop bootstrap, mTLS private-key proof, explicit desktop owner approval, phone fingerprint confirmation, durable ACK_PENDING and idempotent recovery.
- Foreground authenticated health, read-only remote grants/revisions, private Wi-Fi/Ethernet policy, bounded retries, untrusted NSD hints, manual pinned reconnect and durable local revocation.
- Native Devices UI/ViewModel/Home integration following the supplied mobile references; no invented connected state. Foundation features remain intact.
- Split documentation, current tests, CI source and a separate real Rust-agent interoperability harness. No wholesale backend JNI port.

**This is a development implementation, not completed native/release qualification.** Files, transfers, Olm messaging, age attachments and sync remain unavailable on Android.

## What passed

| Final check | Result |
|---|---:|
| Domain tests | 22 passed |
| Connection/protocol/state/real JVM TLS tests | 59 passed |
| Android host tests (Room/DataStore/Compose/repository/QR/coordinator) | 95 passed |
| Real current Rust-agent interoperability cases | 10 passed |
| **Total current host/interop checks** | **186 passed; 0 failed/errors/skipped** |
| Lint | 0 errors/warnings; 36 informational update hints |
| Main + instrumentation APKs | Built; not installed or executed here |
| Artifact audit | Manifest/export scope, v2 signer, ZIP/ELF alignment and test-crypto exclusion passed |

All seven final stages match **108 runtime/test/build inputs**. The 186 total includes retained foundation checks, not 186 entirely new tests. Earlier compile/lint failures and their fixes remain recorded. See [validation details](docs/android/phase-2-validation.md).

## Deliverables

- `NARADA_Android_Phase2_Source.zip`: native source, matching current inputs/evidence, unchanged Rust peer source for interop, focused Markdown docs and original references.
- `NARADA_Android_Phase2_APKs.zip`: lossless outer ZIP containing the signed debug app, matching instrumentation APK, installation note and APK SHA-256 receipts. **Extract before installation**.
- `NARADA_Android_Phase2_SHA256SUMS.txt` and package-check receipt: final downloadable-bundle integrity.

| APK inside bundle | Bytes | SHA-256 |
|---|---:|---|
| `NARADA_Android_Phase2_Debug.apk` | 38,569,644 | `d1b1a89f99ee12f45ee015752e6321d40406619fb651525797d6b59c008e88c9` |
| `NARADA_Android_Phase2_Instrumentation.apk` | 2,217,410 | `53657026a6849aef5e28646c33210a45bb063415046614525a75765cb7af66f8` |

The app package is `com.narada.mobile.dev`. Both artifacts match the **original Phase 1 debug signer**: `4867bd2ab95831d130e9d23c21942cca04ad60863f698c9a953f041fc6952c72`. This is not a production signing key or proof of a completed device upgrade.

## Try it safely

1. Use a disposable test device/profile; extract and verify the APK bundle.
2. Install the debug APK. Use the actual Windows agent/private-interface setup for a phone LAN test—not the Linux loopback-only development harness.
3. Open **More → Devices**, prepare identity, enable connections, then follow the [pairing/approval workflow](docs/android/pairing-workflow.md).
4. Require explicit desktop owner approval and full fingerprint comparison. Only authenticated health displays Connected.
5. For disconnect/recovery/revoke, follow the documented stage-specific actions. Uninstall/clear-data is **not** a lossless repair. Revoke on the desktop too when needed.

## Still required

[Native checklist](docs/android/phase-2-device-checklist.md): actual AndroidKeyStore/Conscrypt mTLS against Windows, camera/NSD/network routing, real install-over/process kill/reboot/Doze/fsync/backup/provider/OEM/TalkBack/16 KB behavior, resource measurements and signing/release/security review. Three instrumentation tests compile but remain unexecuted. Remote CI definition is supplied, not run.

The host interop uses JSSE + software fixture client keys against a **real corrected Rust process**, on Linux loopback. It is valuable protocol/negative evidence, not a phone/Windows demonstration or hardware-key attestation. No permanent background availability or coherent whole-state rollback resistance is claimed.

## Historical preservation

The desktop single-pass 342-pass/6-failure record was not rerun or rewritten. The current agent was newly built/tested only for this narrower Android interoperability campaign. The Phase 1 handoff/evidence and all previous source archives remain unchanged.

To fit workspace persistence limits, historical Phase 1 APK bytes are now preserved losslessly in `NARADA_Android_Phase1_APKs.zip`; extract to restore their original filenames. Both original hashes were verified. Private synthetic desktop runtime fixtures were privately archived, not included in the deliverable. The [relocation receipt](artifacts/android-phase-2/preparation/snapshot-budget.json) records the reversible operation. Large DEX inspection output is also preserved losslessly as gzip.

**Next implementation phase:** Phase 3 authorized files. Do not treat open native/security gates as passed or enable content behavior merely because pairing works in a host harness.
