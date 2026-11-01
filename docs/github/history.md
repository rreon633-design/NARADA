# Historical evidence boundary

The first GitHub campaign is a **newly authorized validation campaign**. The original workspace archives, failed runs, corrections and phase reports are preserved; they are not rewritten or relabeled as GitHub/native success.

| Historical delivery | Recorded result and boundary |
|---|---|
| Desktop Phase 4 | 5 passed, 2 failed, 12 not run; later corrections were not rerun in that single-pass campaign |
| Desktop Phases 5–9 | 342 passed, 6 failed; later corrections were not rerun in that campaign |
| Android Phase 4 | 479 passing host/interop checks; native gates remained open |
| Android Phase 5 | 581 passing host/interop checks; native/independent gates remained open |
| Android Phases 6–7 | 796 passes: 32 Rust + 22 domain + 418 connection + 65 real-peer interop + 259 host/UI; 13 instrumentation cases compiled, **0 executed** in that delivery |

The Phase 6–7 handoff also preserved 2,370 original inputs and recorded exact 223-Android/101-desktop source fingerprints. Its Linux/JNI/JSSE/software-vault/Robolectric/static-APK evidence was not AndroidKeyStore/Conscrypt/Windows/device proof.

## Preserved handoff archives

These are prior downloadable workspace deliverables, **not files embedded in this clean Git repository**:

- `NARADA_Android_Phase6-7_Source.tar.xz` — SHA-256 `886ba905b584f96049719a43a21994e52da95388fd4d7a112fc48c4c58208c1f`
- `NARADA_Android_Phase6-7_APKs.tar.xz` — SHA-256 `c7253dea2d85a3cef9d9f59ad6645865d8024702daee682bd33de1cdf387a9cd`

Earlier full reports, screenshots, binaries, recovery deltas and provenance archives remain in the original handoff workspace. Legacy phase documents and scripts retained as source can reference those separate archives/directories. Such references are **not** current CI evidence or promises that every archived binary is stored in Git.

## Current changes

GitHub preparation adds a fresh workflow/evidence pipeline, a release-style isolated manual-test Android build, per-ABI APK output, explicit Windows size optimization/runtime policy, artifact size/hash audits, and focused installation/CI documentation. Existing internal app/versionCode/protocol/schema numbers are retained. No app security mechanism is deliberately weakened for package size or test convenience.

The original bytes of modified build/preparation files are separately recorded before changes. The published Phase 6–7 source archive is immutable and still describes that earlier validation, not the new build configuration. New CI will report only what it actually executes; failures block publication rather than changing the old baselines.

## Fresh GitHub failures and source repair

Run `41279487321` at source `3ddeacc69624c5477d8bcf0b61db69dff23f7b5c` failed and remains failed: tooling passed, Android SDK setup stopped, Linux rejected a mistakenly 33-byte development-vault fixture, and Windows executed 47 connection tests successfully but failed two attachment exports with `UNSAFE_OR_BUSY`. A scoped read-only diagnostic workflow recovered the logs without asking for an API credential. The next SDK setup progressed after explicitly pinning current command-line tools and excluding the obsolete `tools` package. This does not establish that later Android build/test steps pass.

The Linux CI fixture is corrected to exactly 32 bytes; production key handling is unchanged. Debug-only filesystem diagnostics report numeric OS codes/source locations, never user paths/content or arbitrary error text. All existing safety checks, collision handling and native test assertions remain enabled. Rust now continues across test binaries after a failure so one failed library does not hide other regressions. Original source bytes are preserved separately; these changes must pass a new campaign.

Run `41280634057` also exposed one incorrect legacy process-fixture expectation: chat denies missing peer capability with `PERMISSION_DENIED`, while sync denies a missing root grant with `ACCESS_DENIED`. The new fixture asserts each exact code, still requires `ok: false`, and additionally rejects returned content. No production authorization logic is changed for this correction. The old 17-pass/1-failure process result remains recorded.
