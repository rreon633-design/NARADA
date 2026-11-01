# GitHub build and release workflow

Target repository: **https://github.com/rreon633-design/NARADA**. Initial requested tag: **`v0.0.1`**.

## Fresh campaigns, not rewritten history

The active workflow is `.github/workflows/build-test-release.yml`. It runs on main/master pushes, pull requests, version-tag pushes and manual dispatch. The clean export activates this workflow and a read-only failure-diagnostics workflow; historical workflows, results and archives remain unchanged in the handoff workspace.

Every job starts new evidence under `.cache/ci-evidence/<job>`, fingerprints the Git-tracked checkout, records command exits, and rejects source changes during validation. Missing XML, zero test cases, failed cases and skipped cases cannot be counted as a successful JUnit campaign. Gradle test-output cache reuse is disabled. Compiler/dependency caches do not substitute for test execution.

| Job | Required checks |
|---|---|
| Tooling | CI/audit/gating self-tests; existing packaging and release-tool tests |
| Linux desktop | Workspace Rust tests and Clippy; real-agent lifecycle/pairing/files/transfers/chat/offline process checks including opt-in timing cases; Node unit tests; command surface; browser suites and UI size |
| Windows x64 | Native Rust tests and Clippy; Node tests; command surface; production-feature agent and Tauri/NSIS build; silent installer/bootstrap smoke; actual size/hash audit |
| Android | Pinned age/Olm Rust tests; host + four-ABI JNI builds; domain/connection/JVM-to-real-Rust interop/Robolectric checks; strict debug and manual-test lint; minified per-ABI candidates and matching instrumentation APK |
| Emulators, API 29 and 35 | Execute existing native instrumentation against the **same minified x86_64 APK** intended for download; app launch; JNI and AndroidKeyStore cases |

The API 29/35 runs do not execute ARM/x86 32-bit APKs or a 16 KiB-page image. Windows native bootstrap is not an end-to-end GUI/LAN qualification. Actual results are available only after GitHub executes the workflow. The earlier optional long-running fuzz campaign is not executed by this development-release pipeline; fuzz/security qualification remains open.

## Toolchains and supply-chain boundaries

- Rust **1.99.1**, Node **22**, JDK **17**, Python **3.12**.
- Android Gradle wrapper **8.13**, AGP **8.13.2**, SDK **36**, build tools **35.0.0**, NDK **30.0.16248370**; strict checked-in Gradle dependency verification.
- Cargo/npm lockfiles are used. GitHub actions are pinned to full commit SHAs.
- Checkout credentials are not persisted. Build/test jobs have read-only repository permission and receive no repository deploy key or production signing key.
- The debug signing key is created on the runner, never committed, uploaded or cached. Only Gradle dependency caches and explicitly named Rust outputs are cached; no runtime profiles or signing directories are cached.

These are reproducibility/provenance controls, not a complete dependency advisory/license audit or a claim of bit-for-bit reproducibility across machines.

## Artifacts versus releases

Successful platform build jobs expose `windows-x64-manual-test-attempt-N` and `android-per-abi-manual-test-attempt-N` artifacts for 14 days. Every artifact name includes the attempt number, so reruns never overwrite earlier evidence. They are platform-job candidates: **check the overall workflow outcome before using them**, because another required job can still fail. Evidence is retained on failures where available.

A version-tag push, or the first successful `main` campaign while `v0.0.1` is absent, enables the release job only in the owner repository. It requires **every** listed job, including both emulator jobs, to succeed. It verifies:

1. Same commit, workflow run and attempt across all successful job records.
2. File checksums, actual package bytes, source-input fingerprints and per-ABI completeness.
3. Exact identity of the minified APK used in both emulator runs.
4. Installed Windows application/agent hashes matching the packaged build.
5. The existing remote tag identifying the tested commit, or (for the one-time initial release) `main` still identifying that commit before creating the new annotated tag.

After the first green `main` campaign, the job creates the requested annotated **`v0.0.1` tag once, only if absent**. Later main pushes leave that tag/release untouched. It uses the successful same-commit campaign directly; a workflow-token-created tag does not need to trigger a second campaign. Branch-candidate binaries are copied under versioned filenames without changing their tested bytes.

The job creates a **draft prerelease**, uploads and checks all assets, then publishes it. It will not move or replace tags, overwrite an existing release, or promote this to a production release. A failed upload leaves a draft unpublished. If the initial tag was already created, the owner must review/remove the failed draft and rerun **all jobs of the same initial commit** to retry publication; do not move the tag. Rerunning only failed jobs is deliberately rejected when it would mix artifacts from different attempts. Tag-ref creation is create-only, so a concurrent existing tag is never overwritten. Never retag a published version to conceal a failure.

## Initial push procedure

1. User installs the named SSH deploy key with write access and confirms.
2. Verify GitHub's SSH host key through trusted HTTPS metadata; authenticate without printing the private key.
3. Reinspect remote heads/tags/default branch. Preserve existing history. No force push and no replacement of an existing `v0.0.1` tag.
4. Export a source-only staging tree with `python scripts/prepare_github.py --destination .cache/github-publish` (the destination must be new). It excludes prior binary/evidence dumps, runtime profiles, signing material and historical auto-running workflows. This command does **not** push or tag.
5. Commit/push to the appropriate inspected branch and observe the new CI run. Fix failures in new commits; never change historical reports to make CI green.
6. The workflow creates the requested annotated `v0.0.1` tag and publishes the prerelease **only after all required branch checks pass**. No second SSH operation or private key in Actions is needed. For later versions, an owner-created version tag runs the full tag campaign before publication.

The SSH deploy key grants Git access only. It cannot enable disabled Actions, modify repository settings through the REST API, sign application packages, or provide production publishing approval. If Actions are disabled or organization policy blocks a pinned action, the repository owner must adjust the setting.

## Local commands

The CI workflow is the canonical portable recipe. It does not depend on `/home/user`, a local SDK archive, the phase-handoff tarballs or fixed historical validation directories. Use the commands in that file with the pinned toolchains. Do not run old `validate_phase*.py` campaign writers against preserved artifacts.

See [manual testing](manual-testing.md), [lightweight policy](lightweight.md), and [historical baseline](history.md).

## Failure diagnostics without sharing API credentials

The separate `ci-diagnostics.yml` workflow uses GitHub's short-lived token with `actions: read` and `contents: read` to retrieve failed job logs in this repository. It publishes bounded, escaped diagnostic excerpts as check annotations so failures can be investigated using the SSH-only delivery setup. It never prints its token, never forwards it to signed log-storage URLs, and cannot write tags, commits or releases. Diagnostics of an older failed run are labeled as such; they do not change that run's outcome. Command failures also emit bounded annotations directly.

Android command-line tools are explicitly pinned to `16111833`; the obsolete SDK `tools` package is not requested.

In-flight source revisions now retain their own commit-scoped concurrency groups instead of cancelling the earlier platform campaign on every repair push. This preserves complete diagnostics/caches. Publication still requires the current `main` SHA and all same-attempt evidence; an older or failed campaign cannot release a newer commit.

The Node 22 test entrypoint enumerates the actual test files explicitly and emits TAP plus JUnit. A directory argument that passed with local Node 20 failed under Node 22; the local Node 22 failure is recorded separately, and a zero/partial test selection cannot satisfy the 37-case minimum.

Lint still treats application warnings as errors. `OldTargetApi` is retained as an **informational advisory**, like pinned dependency-update notices: target API 36 is intentional for this development package, and target-37 LAN permission/compatibility work remains an explicit production gate. APK audits still enforce the declared target/minimum SDK. This is not a claim of Play-store eligibility or target-37 qualification. No application finding is hidden in a lint baseline.

## Windows installer evidence and runtime prerequisites

Windows package collection measures the fresh build before the install smoke, retaining a build report even if installation fails. This is not a pass: native installation, exact installed application/agent SHA-256 identity, and the live app/agent bootstrap remain mandatory. Manual-test package upload and publication still require the successful smoke and finished job evidence.

The installer embeds the small Microsoft WebView2 **bootstrapper**, never a full Chromium/WebView runtime. This avoids making NSIS itself download the bootstrapper at installation time; Microsoft still supplies the system runtime over the network when it is absent. The smoke records pre-install WebView2 registry state and a bounded installed-file inventory. It allows a bounded file-settlement interval but never accepts mismatched bytes or a missing agent. Runtime first-install/network behavior is evidenced only if the runner actually lacked the runtime; a bootstrap smoke is not blanket installer/runtime compatibility qualification.

The sixth campaign built an NSIS installer, but its install check found no unique executable matching the built application's SHA-256. That failure is retained. Its limited log does not establish whether files were absent, different or still settling; neither a WebView2 download cause nor an asynchronous-installer cause is claimed as proven.

## Minified instrumentation dependencies

The sixth campaign passed the Android host/JNI/real-peer/Robolectric and strict debug-lint stage, then failed while R8 processed the **instrumentation APK** (not an emulator execution). AndroidX Test Core 1.7.0 declares concurrent-futures-ktx 1.2.0 but its ViewCapture references `SuspendToFutureAdapter`; the class is present in the inspected Google Maven 1.3.0 artifact. The already-used futures runtime is explicitly aligned to 1.3.0 in both app and instrumentation. AGP consistent resolution correctly refused a test-only 1.3.0 pin against the app's locked 1.1.0 runtime; that check was not bypassed. All affected app/test classpath locks are regenerated.

The older transitive Error Prone annotation referenced Android-unavailable `javax.lang.model.element.Modifier`. Test-only error_prone_annotations 2.51.0 has Java 8 class files and its own `Modifier` enum; its inspected `IncompatibleModifiers` class has CLASS retention and no javax.lang.model reference. It is pinned for instrumentation only. No R8 warning suppression, broad keep rule, app de-minification or skipped native case was introduced. Both app and matching instrumentation still have to build; exact minified app bytes must execute all required native cases on both emulators.

The repair regenerates debug/release/manual-test app and test classpath locks and adds seven checksum entries. The 1,254 existing artifact checksum entries are unchanged. Both debug and manual-test dependency graphs passed read-only, strict verification locally after regeneration; this is dependency validation, not a local APK or emulator result. The new bytes were separately fetched from Google Maven/Maven Central and matched against the new SHA-256 pins.

## APK SDK-audit correction

The seventh campaign built minified app/instrumentation APKs but failed the old AAPT-label SDK check. See [the reproduced tool-format mismatch and strict manifest cross-check](apk-audit.md). That run did not execute emulators or publish a release.

## Exact packaged images and instrumentation ABI

The eighth campaign passed Android collection and Windows native Rust/Clippy, but stopped at a Windows payload-reference mismatch and two instrumentation startup crashes. See [Windows package-byte provenance](windows-packaging.md) and [the inferred instrumentation boundary](android-instrumentation.md). Subsequent repairs still require a complete new same-source campaign.
