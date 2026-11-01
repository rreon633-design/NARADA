# Android Phase 1 — validation record

[Phase plan](../../android-plan.md) · [Scope](phase-1.md) · [Native checklist](device-checklist.md)

**31 October 2026 · Development foundation complete; native/release qualification open.** This report distinguishes executed host validation from unexecuted device work. Desktop validation was not rerun or relabeled.

## Final executed checks

| Record | Stage | Actual result | Duration |
|---|---|---|---|
| `domain-02` | `domain` | 22 passed; 0 failed/skipped | 12.045 s |
| `host-05` | `host` | 60 passed; 0 failed/skipped | 75.123 s |
| `lint-02` | `lint` | 0 errors/warnings; 30 informational update hints | 75.123 s |
| `apk-01` | `apk` | Built successfully; not installed/executed | 95.641 s |
| `instrumentation-01` | `instrumentation` | Built successfully; not installed/executed | 31.769 s |

**Total: 82 passed, 0 failed, 0 errors, 0 skipped.** The instrumentation APK contains one additional smoke test that was **compiled and packaged, not executed**. Build/lint stages are not counted as test cases.

- [Machine-readable summary](../../artifacts/android-phase-1/validation-summary.json)
- [Validated runtime/test/build-input hashes](../../artifacts/android-phase-1/validated-input-hashes.json)
- [Final host JUnit/log/source receipt](../../artifacts/android-phase-1/runs/host-05/summary.json)
- [Final lint XML](../../artifacts/android-phase-1/runs/lint-02/lint-results-debug.xml)
- [APK-extracted manifest](../../artifacts/android-phase-1/apk-audit/AndroidManifest.xml) and [debug signature](../../artifacts/android-phase-1/apk-audit/signature.txt)

All five final stages used normal dependency verification, without checksum/lock-writing flags. **83 runtime/test/build-input files matched across the final stages and the audited source.** Later documentation and the unexecuted CI definition were clarified; no application, test, catalog, lock or verification-input correction was made after the final pass.

### Test distribution

| Suite | Passed | What was exercised |
|---|---:|---|
| Domain policies | 22 | Labels, grant flags, owned work transitions, bounded allowlisted reports and unavailable sensitive-store gate |
| MetadataStore | 12 | Actual Room/SQLite operations, synthetic 1→2 migration, bounds, missing/corrupt state and preservation |
| SettingsAndPlatform | 18 | DataStore/preservation, private files/report bounds, serialized probes and orphan cleanup, narrow sharing |
| WorkOwnership | 8 | Durable IDs, application ownership, enqueue/cancel/recovery windows and stale results |
| NativeAdapters | 7 | Worker/SAF/notification adapters under Android shadows |
| ViewModelState | 3 | Committed versus draft state and restoration behavior |
| ComposeShell | 11 | Onboarding, navigation/Back, tabs/state, editing guard, motion preference and host renders |
| ApplicationIntegration | 1 | Real Hilt application and MainActivity launch/recreation, without auto-enqueuing tagged work |

These are host tests—not a claim that all paths, Android versions, providers or failures are covered. No coverage percentage is claimed.

## Environment and evidence interpretation

Linux x86_64 development host, approximately 2 GB RAM, no connected device/emulator and no usable KVM. JDK Corretto **17.0.21+11**, Gradle **8.13**, AGP **8.13.2**, Kotlin **2.2.21**, SDK **36**, Build Tools **35.0.0**. Exact source pins and bootstrap provenance are in the project and `artifacts/android-phase-1/preparation/`.

Android host tests intentionally use **API 35** with Robolectric **4.16.1** and its verified offline `15-robolectric-13954326-i7` fixture. Robolectric prints that API 36 requires JDK 21; this does **not** mean cases were skipped: all declared tests here select API 35, and the final reports record zero skips. API 29/36 native runtime behavior remains untested. Compile/target 36 is not runtime qualification.

A small-memory build was split into compilation, tests, lint and packaging. Matching `--no-build-cache --info` flags avoided invalidating KSP inputs inside a low-heap test process. Preparation logs and failed attempts remain preserved; successful final runs have separate, immutable record directories. The temporary build-host memory guard was stopped after builds.

### Earlier failures and corrections (not erased)

| Evidence | Actual outcome / correction |
|---|---|
| Preparation builds 01–10 | Lock-policy/API/import corrections; combined build memory starvation; a 384 MB DEX heap failed, 640 MB packaging succeeded; one proactive memory-guard stop. See raw preparation logs. |
| `domain-01` | 22 passed on the earlier source. Re-executed as `domain-02` for the final snapshot. |
| `host-01` | No test cases ran: prerequisite compiler invalidation exceeded the test-process budget. |
| `host-02` | 34 passed / 8 failed: one JUnit expression returned a non-void value; host PixelCopy could not use a physical compositor. Fixed test signatures and captured the actual Compose View/Canvas instead. |
| `host-03` | 57 passed / 1 failed: FileProvider cached an earlier per-test temporary root. Fixed test-only cache isolation; production roots were not broadened. |
| `host-04` | 58 passed; later production lint fixes and two additional private-probe regression cases required the final `host-05` run. |
| `lint-01` | Verification stopped before analysis: lint-only artifact checksums had not yet been bootstrapped. |
| Lint bootstrap | Reached 36 findings: six code/config/resource findings were corrected; 30 new-version advisories retained as informational under an explicit pinning policy. Original XML/HTML remain preserved. |
| `lint-02` | Passed under normal verification: zero errors/warnings, 30 `Hint` entries. No application baseline or security-detector suppression. |

The three informational categories are `GradleDependency`, `NewerVersionAvailable` and `AndroidGradlePluginVersion`; this is **not** a vulnerability waiver. Verification metadata covers 684 components including build/test dependencies. Checksums are trust-on-first-use, not independent publisher authentication, vulnerability review or license approval.

## Artifact audit

The delivered application is **debuggable**, package `com.narada.mobile.dev`, version **0.1.0 (1)**, minimum **29**, target **36**, Room schema **2**. Its application ID and signing identity are development-only.

| Artifact | Bytes | Purpose |
|---|---:|---|
| `NARADA_Android_Phase1_Debug.apk` | 35,404,306 | Normal development app; approximately 35.4 MB, including debug tooling |
| `NARADA_Android_Phase1_Instrumentation.apk` | 2,211,822 | Matching device-test package; not the normal application |

App SHA-256:

```text
1f10415a37e53c56c37317606cafeb7b6e9971c10fa0469c6862730d090477dc
```

- `apksigner verify` passed. The debug certificate's SHA-256 is `4867bd2ab95831d130e9d23c21942cca04ad60863f698c9a953f041fc6952c72`. This is not production signing, Play approval or an independent authenticity endorsement.
- The actual binary APK manifest was extracted—not merely inferred from source. **No INTERNET, camera, broad-storage or foreground-service permission/service** is present.
- Requested permissions: POST_NOTIFICATIONS, ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED and the app's AndroidX non-exported-receiver signature permission. WorkManager contributes the scheduling-related permissions; this does not make it an always-on service.
- MainActivity is the only unprotected exported component. WorkManager's system job service requires BIND_JOB_SERVICE; its diagnostics receiver and the profile installer receiver require DUMP. PreviewActivity is removed, and the Compose test ComponentActivity is not exported.
- FileProvider is not exported and maps only `cacheDir/support/`; generated share intents grant read access to one URI. Backup and cleartext are disabled. Actual OEM backup/share behavior remains unqualified.
- ZIP CRC and `zipalign -c -P 16 -v 4` passed. All eight bundled AndroidX/DataStore native libraries have 16,384-byte ELF LOAD alignment. These are **static metadata checks, not execution on a 16 KB device**.
- Private debug signing material, SDKs, dependency/build caches and personal profiles are excluded from the source handoff. A new debug key is not a compatible in-place update signer.

## Actual UI evidence

Seven PNGs from the final `host-05` run are stored in `artifacts/android-phase-1/runs/host-05/host-renders/`: onboarding, Home, Files, More, Settings, Diagnostics and Home at 200% font scale. They come from the actual Compose tree using View/Canvas under Robolectric native graphics, with nonblank-pixel assertions, API 35 and a 411 × 891 dp/xhdpi host configuration.

Final Home, Settings and 200% Home images were visually inspected. At 200%, the Transfers label wraps over two lines; this is not a physical accessibility certification. Screens use synthetic local test fixtures, not real paired devices or content. **These are not installed-APK/emulator/phone screenshots.** The supplied reference assets remain unchanged and separately identifiable.

## Not executed / not accepted

- No app installation, emulator, physical phone, connected instrumentation or remote CI execution.
- No real permission/revocation/cloud-provider, chooser-recipient, lock-screen notification, IME/predictive Back/TalkBack, reboot/force-stop/Doze/OEM backup/16 KB runtime test.
- No Android ↔ desktop pairing, LAN, transport, files, E2EE, attachment or sync interoperability; these features are not implemented in Phase 1.
- No release AAB, production signing/update/pilot, measured battery/startup performance, independent security or comprehensive dependency/license review.

Follow the [native/device checklist](device-checklist.md), preserving new artifact-bound evidence. The separately preserved desktop result remains **342 passed / 6 failed / zero retries**, with its documented unverified corrections; Android success does not change that result.
