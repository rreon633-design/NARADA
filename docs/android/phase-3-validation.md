# Android Phase 3 — measured validation

[Index](README.md) · [Handoff](../../ANDROID-PHASE3-HANDOFF.md) · [Native checklist](phase-3-device-checklist.md)

## Final campaign, 31 October 2026

These are new Android Phase 3 records, not a rewrite of Phase 1/2 or the failed historical desktop campaigns. Final source is app **0.3.0 (3)**, Room **3**, sealed trust payload **2**, min API **29**, compile/target **36**.

| Stage / record | Result | Elapsed seconds |
|---|---|---:|
| JVM domain / `domain-01` | **22 passed** | 13.367 |
| JVM connection/files / `connection-02` | **135 passed** | 48.142 |
| Real Rust peer interop / `interop-02` | **22 passed** | 20.393 |
| Android host / `host-03` | **118 passed** | 149.890 |
| Lint / `lint-01` | **0 errors / 0 warnings / 36 Hints** | 165.464 |
| Debug build / `apk-01` | **Passed** | 34.398 |
| Instrumentation build / `instrumentation-01` | **Passed; not device-executed** | 32.712 |

**297 passed, zero failures/errors/skips across the final four test stages.** This includes 186 retained Phase 2 checks and **111 new Phase 3 checks**. A successful build is not counted as an executed test.

[Audit summary](../../artifacts/android-phase-3/validation-summary.json) · [127 matched input hashes](../../artifacts/android-phase-3/validated-input-hashes.json) · [Final host summary](../../artifacts/android-phase-3/runs/host-03/summary.json) · [Final real-agent summary](../../artifacts/android-phase-3/runs/interop-02/summary.json)

## Test inventory

| Scope | Cases |
|---|---:|
| Retained JVM connection protocol / pairing / TLS | 26 / 18 / 15 |
| File protocol, names, replies, strict JPEG/receipts | 25 |
| File client authorization, durable intent, exact retry and failure fencing | 24 |
| Browser state, selection/navigation, cursor/epoch/privacy/expiry | 19 |
| File-specific HTTP/TLS bounds and retained route restrictions | 8 |
| Retained real Rust connection cases | 10 |
| Real Rust file cases | 12 |
| Retained Android host cases | 95 |
| Protected file-journal repository cases | 6 |
| Connection-owner/file-lease integration cases | 7 |
| Files browsing and action/decoder UI cases | 5 + 5 |
| Retained domain cases | 22 |

The real-agent file cases cover roots, exclusion of hidden/ungranted content, folders/breadcrumbs, search and all four sorts, pagination/reindex stale cursors, live details, bounded JPEG output, active-format/stale-version refusal, changed grants/revocation, actual rename/trash/restore effects, collisions without overwrite, and a lost mutation reply recovered with the same receipt after agent and phone-repository reload.

Host UI tests use actual Compose controls, including explicit confirmation and 200% text. Rendered data and software keys are deliberately synthetic and labeled. [Files host renders](phase-3.md#host-render-examples)

## Artifact-bound audit

The audit verified **127 runtime/test/build inputs** against every final stage and current files, including no added untested source files. Source packaging independently checks them again. It also verified **1,594 frozen historical files** unchanged.

- Debug and instrumentation APKs verify under the same original development certificate:
  `4867bd2ab95831d130e9d23c21942cca04ad60863f698c9a953f041fc6952c72`.
- Application ID/version/min/target, backup-off, cleartext-off, exported components and private scanner were read from the built APK, not assumed from Gradle source.
- **No Android permissions were added.** No foreground transfer service or broad storage permission; FileProvider remains restricted to diagnostic reports under `cacheDir/support/`.
- ZIP 16-KiB alignment and native ELF load alignments were inspected. This is not 16-KiB device execution.
- Application DEX contains no Bouncy Castle/software test-identity generator fallback. Verbose inventory is preserved as a lossless gzip with raw-byte/hash receipt.
- **710 dependency components** retain checksums. This is resolution/TOFU evidence, not an advisory/license/independent security review.
- No SDK/cache, signing key, enrolled trust store or private runtime profile is shipped.

### Measured APKs

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `NARADA_Android_Phase3_Debug.apk` | 38,725,771 | `bc61ccddf2e7f1a59bd1a13fd34be44b794c6fdc67d7c8d5a56bbcc6f1266784` |
| `NARADA_Android_Phase3_Instrumentation.apk` | 2,236,639 | `6db6cb59c7f48bd2d0378fd03ba4f14739048198b5b99cffc2b7ae69bc1b1cd1` |
| `NARADA_Android_Phase3_APKs.zip` | 14,306,839 | `18b8220bd4980abac28f9f71ca36d3198d87056d5c85aa0f5c47b0673397febe` |

Source ZIP hashes are in the delivered `SHA256SUMS` / package-check files to avoid a circular hash inside the source bundle. ZIP CRC and all packaged member bytes are verified, not just the outer filename.

## Preparation failures and corrections

Earlier attempts are preserved separately and are **not** current passes. Preparation transcripts are losslessly gzipped with per-file raw/compressed hashes; the earlier two host-render sets are preserved in a verified workspace tar.xz, with a member receipt. Final host-03 renders remain loose and are included in the source bundle.

Corrections:

- Initial UI compilation exposed cross-module smart-cast assumptions; captured stable locals replaced them.
- Android unit-test compilation cannot use desktop AWT/ImageIO; the decoder test now reads a tiny checked-in JPEG fixture. Core JVM tests may generate their own ImageIO fixtures.
- `connection-01`: 133 initial core checks passed before later expiry/JFIF hardening. Final `connection-02` includes those two added regressions and all final source changes.
- `interop-01`: 21 passed / 1 failed. The test incorrectly expected new grants to be persisted on a session the desktop had correctly closed after permission change. The final test still asserts result rejection, then uses a fresh pinned lease to observe/persist the new grants. Final `interop-02`: 22 passed.
- `host-01`: memory guard terminated a worker; no complete usable JUnit campaign was produced. A killed native-Robolectric extraction occupied tmpfs. Generated cache bytes were moved and verified; host JVM temp now uses excluded disk cache, and graphics-heavy cases are split across worker forks. No checks were removed.
- `host-02`: 116 passed / 1 failed. The rename editor inside the detail alert caused a persistent measure/idling loop. Merely recreating the alert (`host-rename-01`) did not fix it. A separate secure, scrollable full-screen confirmation removed the intrinsic-size feedback loop; `host-rename-02` passed, followed by the **full final 118-case host-03**, including a new 200%-type confirmation regression.
- Minor fixture-access/bracing compilation corrections remain in preparation logs. Final warm compile and all seven stages use the same final inputs.

The early filtered failure record’s legacy `test_output_missing` flag was overbroad when every selected test failed; its preserved JUnit XML shows the single actual failure. The Phase 3 runner was corrected to flag missing suites, not merely zero passes. Final audit rejects filtered test campaigns and requires exact full counts.

## Reproducibility

Use JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, SDK 36 and Build Tools 35.0.0. Host tests run Robolectric/API 35; its API 36 image requires JDK 21 and was not substituted. Rust agent is the unchanged Phase 2 build from pinned Rust 1.99.1; its binary/source receipts are recorded. [Build commands](../../apps/android/README.md)

`validate_android_phase3.py` writes a **new** record directory per stage and refuses collisions. Test outputs are removed before execution so an up-to-date task is not mistaken for a new campaign. Optional filters are preparation-only; the final audit requires full suites. `audit_android_phase3.py` verifies built artifacts and cross-stage inputs; `package_android_phase3.py` performs inclusion/link/hash/CRC checks without rerunning desktop tests. Earlier phase scripts/artifacts are untouched.

## Not executed / not established

**No real Android device, emulator, remote CI or native Windows execution.** Five instrumentation tests compiled: one foundation smoke, two native key boundaries, two native preview/journal boundaries. They were not run. Linux JSSE/software-key interop is not AndroidKeyStore/Conscrypt, Windows DPAPI/ACL/reparse/LAN, hardware-backing, camera/NSD/provider/OEM, crash/fsync/upgrade/backup, physical accessibility or resource qualification. No independent security or production-signing approval. [Required device campaign](phase-3-device-checklist.md)
