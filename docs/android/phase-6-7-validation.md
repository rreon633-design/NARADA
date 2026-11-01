# Phases 6 & 7 — measured development validation

[Machine summary](../../artifacts/android-phase-6-7/validation-summary.json) · [Problems and preserved attempts](phase-6-7-problems-solutions.md) · [Native/release gates](phase-6-7-device-checklist.md)

## Final source-bound campaign

| Evidence | Passed | Failed / error / skipped | Final record |
|---|---:|---:|---|
| age Rust adapter, Linux | 12 | 0 / 0 / 0 | `native-runs/host-01` |
| Olm Rust adapter, Linux | 20 | 0 / 0 / 0 | `native-runs/host-01` |
| Kotlin domain | 22 | 0 / 0 / 0 | `runs/domain-02` |
| Kotlin connection/content core, host JNI/JSSE | 418 | 0 / 0 / 0 | `runs/connection-06` |
| Actual current Rust peer interoperability | 65 | 0 / 0 / 0 | `runs/interop-05` |
| Android JVM/Robolectric application host | 259 | 0 / 0 / 0 | `runs/host-09` |
| **Total** | **796** | **0 / 0 / 0** | No historical desktop rerun |

These are 796 checks in the current combined campaign, **not** 796 plus the prior Phase 5 total. There are 215 additional checks relative to Phase 5's preserved 581-case campaign.

- **223 Android input files** match across every final stage and the audited source, including project docs/config/assets. Exact before/after input manifests, commands, tool-source hashes/copies, complete XML and logs are retained. Native tests separately bind the three Rust adapter inputs to their build receipts.
- **101 current desktop-source/build input files** (Rust workspace, Desktop UI, config/release and root Cargo/npm inputs) and the actual peer binary are fingerprinted. This is not a Windows build or a rerun of the full desktop validator.
- Actual-peer tests include 12 attachment and 10 offline cases, plus retained pairing/files/transfers/chat checks. Linux JSSE + a software dev vault is not Android Conscrypt/AndroidKeyStore/Windows DPAPI proof.
- App-host coverage includes 54 new protected journal/import/picker/coordinator/UI cases. Software AES-GCM/JVM JNI and fake coordinator/provider fixtures are labeled; they are not production software fallbacks or native qualification.
- 28 synthetic API35/Robolectric render files are retained. Screenshots capture the activity content view; separate consent dialog windows are tested via semantics and are not necessarily present in the base-window PNG. This is not device, TalkBack, nonlinear font-scaling or IME proof.

## Build and artifact inspection

| Item | Result |
|---|---|
| Lint `lint-03` | **0 errors, 0 warnings**, 36 informational Hints; no application lint baseline |
| Development APK `apk-01` | Built; `com.narada.mobile.dev`, 0.7.0 (7), min29/target36 |
| Instrumentation APK `instrumentation-01` | Built; **13 cases compiled, 0 executed** |
| Artifact audit `apk-audit-02` | Passed manifest/permissions/exported components/signatures/DEX checks |
| Native provenance | Both adapters × four Android ABIs; generated→merged→reproduced NDK strip→packaged bytes matched |
| ELF/ZIP checks | JNI exports, RELRO/BIND_NOW, non-executable stack, static 16 KiB ZIP/ELF alignment checked |
| Dependency notices | Current age/Olm inventories, exact provided/upstream notice texts and APK asset hashes checked |
| Original history | **2,370 frozen inputs preserved exactly**, 1,910 relocated; seven live desktop paths explicitly superseded by the authorized extension |

710 Maven components remain checksum-verified under the existing TOFU policy. Native inventories include target/build-only packages and unresolved legal/advisory work; neither is an independent security approval or reachability SBOM.

## APK integrity and signing

- Debug: **47,560,849 bytes**, SHA-256 `8bfd3b82f13077867f4a8a4e0e8acbc7873def76b053820a76f744bba2e76709`.
- Instrumentation: **2,242,746 bytes**, SHA-256 `480570012d79d3ca2911df6a6b90446aa5c53cd68cac34a1113d5375f70788ee`.
- APK tar.xz: **13,117,244 bytes**, SHA-256 `c7253dea2d85a3cef9d9f59ad6645865d8024702daee682bd33de1cdf387a9cd`.
- Matching debug/test signer SHA-256: `776219bd4ccd00d7368553fea17c1406f1d8e3bfa554632b2d13ff9ad0965887`, **different from Phase 5**. No in-place upgrade or release-signing qualification.

The APK container holds the exact signed APKs, a README and member checksums. Prior Phase 4/5 APK containers are preserved byte-for-byte through standard-library-restorable deltas against this current container; they were not resigned, patched internally or executed. Full past source archives and historical results remain separate. See [artifact recovery](artifact-recovery.md).

## What this does not prove

Android/device/emulator execution **0**; Windows/native LAN execution **0**; remote CI execution **0**. No independent crypto/JNI/application/storage/advisory/legal review, production signing, same-signer migration, attestation, whole-state rollback resistance or release approval. Host provider/process faults do not establish native power-loss durability, Binder stoppability or atomic external publication.

Phase 5's 581 and Phase 4's 479 checks retain their original scope. Desktop Phase 4 and desktop 5–9 failures/corrections remain exactly as recorded; this fresh Android campaign does not retrospectively qualify them.
