# NARADA Android — Phases 6 & 7 handoff

**Development implementations complete. Native qualification, independent reviews and release approval remain open.**

Application: **0.7.0 (7)** · `com.narada.mobile.dev` · minimum API29 / target36. The authorized desktop Rust peer extension is **0.7.1**. The phone remains **outbound-only**.

[Build / tech stack](apps/android/README.md) · [Split documentation](docs/android/README.md) · [Phase-wise plan](android-plan.md) · [Measured evidence](docs/android/phase-6-7-validation.md)

## What is delivered

### Phase 6 — two-way encrypted attachments

- Desktop→phone fetch and phone→desktop upload, with the explicitly authorized, negotiated **version-1 desktop upload extension**.
- Fresh age X25519 identity per encryption; immutable ciphertext; protected typed Olm manifest; bounded ≤8 MiB imports, chunk/resume, complete authentication and plaintext/ciphertext verification.
- Explicit manifest sync before byte upload. Message delivery, private verification and remote byte-storage receipt are separate facts. Restart never auto-sends or reconstructs encryption state.
- Temporary read-only SAF import and separate reviewed create-new inert export, with durable intents, single-use picker ownership, readback, uncertainty and bounded worker quarantine.

### Phase 7 — retained offline versions

- Scoped snapshot/changes metadata, protected quota-bound caches/pins and exact object/version download candidates.
- Explicit full re-verification/adoption; current/candidate/one previous references. Failed refresh, source deletion and tombstones do not erase verified copies.
- Reference-aware cleanup, work/scope fences, no silent eviction or destructive replacement. **Fresh metadata never means downloaded bytes are available.**

Earlier pairing, files, verified downloads, transactional Olm chat, private UI/notifications and protected-storage failure behavior are retained. No phone listener, owner reindex API, permanent presence, bidirectional filesystem writes, automatic opening or crypto/storage fallback was added.

## Verification

| Current campaign | Passed |
|---|---:|
| age / Olm Rust host adapters | 12 / 20 |
| Kotlin domain / connection-content core | 22 / 418 |
| Actual current Rust-peer interoperability | 65 |
| Android JVM/Robolectric application host | 259 |
| **Total** | **796 — 0 failures, errors or skips** |

- **223 exact Android input files** matched across final stages; 101 current desktop input files and the real peer binary fingerprinted.
- **Lint: 0 errors, 0 warnings**, 36 informational Hints.
- Debug + instrumentation APK builds and manifest/signature/DEX/native-provenance/static-16-KiB audit passed.
- **13 instrumentation cases compiled; 0 executed.** Both JNI libraries built for Linux host and four Android ABIs; that is not Android execution.
- 28 labeled synthetic host renders. No production sample activity or invented peer/files were added.
- **2,370 frozen historical inputs preserved exactly**; seven desktop live paths explicitly superseded by the authorized extension. Historical desktop validation was not rerun or relabeled.

The 796 total includes retained regression cases; it is not added to the prior Phase5 total. Failed/preliminary attempts and corrections remain documented in [problems and solutions](docs/android/phase-6-7-problems-solutions.md).

## Files to use

In the shared workspace, alongside this project:

- `NARADA_Android_Phase6-7_Source.tar.xz` — source, split docs, actual peer source and selected evidence.
- `NARADA_Android_Phase6-7_APKs.tar.xz` — standalone current development/instrumentation APK container; **extract first**.
- `NARADA_Android_Phase6-7_SHA256SUMS.txt` and `NARADA_Android_Phase6-7_Package_Check.json` — integrity/package checks.

Current APK-container SHA-256: `c7253dea2d85a3cef9d9f59ad6645865d8024702daee682bd33de1cdf387a9cd` (13,117,244 bytes). Exact APK member hashes/signers are in [validation](docs/android/phase-6-7-validation.md) and the container's checksum file.

Old Phase4/Phase5 APK containers are byte-for-byte recoverable from the current container plus their `.bsdiff`/JSON recovery files and `NARADA_Recover_APKs.py`. This preserves original signed bytes while avoiding redundant snapshot storage. Older source archives and historical results remain preserved. See [artifact recovery](docs/android/artifact-recovery.md).

## Try the flows

1. Use a **new disposable profile/device**, enable the permitted connection, pair with pinned approval/fingerprint comparison and wait for authenticated health.
2. **Attachment:** Chat → enable private chat → select one ordinary file ≤8 MiB → sync the protected manifest → explicitly send encrypted bytes. Receiving and external export require their own explicit actions.
3. **Offline:** More → Offline copies → select authorized root → refresh metadata → pin an exact file version → complete its Transfers candidate → explicitly verify/adopt.

See [workflow](docs/android/phase-6-7-workflow.md), [tips](docs/android/phase-6-7-tips.md), [attachment precautions](docs/android/attachments-recovery.md) and [offline recovery](docs/android/offline-recovery.md).

## Remaining gates — do not treat this as release approval

The supplied signer **differs from Phase5**. No same-signer/in-place migration is qualified. Do not uninstall/clear valued data to bypass an update conflict: identity, trust and sealed history can be lost.

Android/emulator/Windows execution and remote CI: **not performed**. Independent age/Olm/JNI/application/storage/advisory/legal reviews remain open. Hardware reports are not attestation; software content keys are protected at rest, not hardware-held. Whole-state rollback resistance, real power-loss/ENOSPC, native provider/IME/accessibility behavior, hostile-provider stoppability and atomic external durability are unproven.

**Next Android phases: 8 — native qualification; 9 — release and pilot**, plus the retained earlier review/device gates. The [native/release checklist](docs/android/phase-6-7-device-checklist.md) separates those tasks from this completed development handoff.
