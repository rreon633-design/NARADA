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
