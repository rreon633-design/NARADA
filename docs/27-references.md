# NARADA — References and Requested-content Coverage

> [Documentation index](../README.md) · Reviewed planning sources · 30 October 2026

## 1. User-supplied source material

| Input | Review/use | Location in this pack |
|---|---|---|
| `IFB_Complete_Product_and_Engineering_Plan.md` | Product/engineering foundation, reviewed and reorganized as NARADA documentation | Not duplicated as another giant plan; original remains in the supplied attachments. |
| `file_000000002cb08211a872df64bfe722e4.png` | Detailed desktop Files workspace | [Desktop Files reference](../references/desktop-files-reference.png) |
| `file_000000002a548211b56d49a8c0ec03f0.png` | Sixteen desktop screens/surfaces | [Desktop overview](../references/desktop-screens-reference.png) |
| `IFB_Desktop_All_16_Screens.html` | Static desktop interaction/layout review | [Unchanged desktop HTML](../references/original-desktop-prototype.html) |
| `file_00000000320c82119359f32335a79e84.png` | Mobile visual reference and variants | [Android overview](../references/android-screens-reference.png) |
| `IFB_Android_Mobile_All_Screens.html` | Static mobile review; 17 actual screen sections despite a “16” heading | [Unchanged Android HTML](../references/original-android-prototype.html) |

The original HTML files are preserved for provenance. Their previous branding, decorative QR patterns and simulated data are not endorsed as NARADA production behavior. No application execution, device testing, browser accessibility audit or security audit was performed as part of writing these specifications.

## 2. Official technical references consulted

These references informed specific platform/architecture corrections. Verify current documentation again when selecting actual dependency versions and qualifying a release.

### R1 — Android foreground-service timeouts

[Android Developers: Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)

Relevant to background-execution claims: for apps targeting Android 15 or higher, documented background limits apply to service types such as `dataSync` and `mediaProcessing`, including the stated six-hour-per-24-hour accounting and timeout handling. This is not a universal rule for every foreground-service type; choose the API/type that actually matches NARADA's work and current target OS policy.

### R2 — Android background data-transfer choices

[Android Developers: Data transfer background task options](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options)

Relevant to user-initiated transfer jobs, WorkManager, connected-device scenarios and foreground services. WorkManager can defer/cancel/retry work and is not an always-on socket or exact-time scheduler.

### R3 — Android documents and SAF

[Android Developers: Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files)

Relevant to user-selected document/tree access, provider capability flags, directory restrictions and persistable URI permissions. NARADA must not assume every provider supports a normal local path, random access or atomic replacement.

### R4 — Windows 10 lifecycle

[Microsoft Lifecycle: Windows 10 Home and Pro](https://learn.microsoft.com/en-us/lifecycle/products/windows-10-home-and-pro)

Relevant to avoiding an unconditional “Windows 10/11 supported forever” statement. Standard Home/Pro support ended in October 2025; edition-specific lifecycles and any applicable extended-update arrangements require separate validation.

### R5 — Windows service security context

[Microsoft Learn: Service User Accounts](https://learn.microsoft.com/en-us/windows/win32/services/service-user-accounts)

Relevant to the distinction between a per-user background process and a machine-wide SCM service running under a configured security account. Service account, ACL and key-storage design must be explicit.

### R6 — libsignal integration constraints

[Signal: libsignal upstream repository and documentation](https://github.com/signalapp/libsignal)

Upstream documents that use outside Signal is unsupported and APIs/bindings can change. This supports an early feasibility/license/maintenance gate; it does not select libsignal for NARADA or establish that NARADA's future integration is audited.

### R7 — Tauri capabilities

[Tauri 2: Capabilities](https://v2.tauri.app/security/capabilities/)

Relevant to explicit frontend/native permission boundaries. Capability configuration does not replace safe Rust implementation, command-specific checks or secure dependency/build practices. Review custom-command exposure explicitly.

## 3. Coverage of the user's requested plan

| Requested content | Document |
|---|---|
| README | [README](../README.md) |
| Complete plan and product scope | [Product plan](01-product-plan.md) + full README index |
| Tech stack | [Tech stack](03-tech-stack.md) |
| Architecture | [Architecture](04-architecture.md), [Data model](11-data-model.md) |
| Workflow and process | [Workflows/processes](14-workflows-processes.md) |
| Features list | [Features](02-features.md) |
| Tips and tricks | [Tips and tricks](21-tips-tricks.md) |
| Prevention and precautions | [Prevention/precautions](20-prevention-precautions.md) |
| Tasks | [Task backlog](15-task-backlog.md) |
| Phase-wise plan | [Phase-wise plan](16-phase-wise-plan.md) |
| Success metrics | [Success metrics](17-success-metrics.md) |
| Problems and solutions | [Problems/solutions](19-problems-solutions.md) |
| Mechanisms | [Transfer](08-transfer-mechanism.md), [Sync/offline](09-sync-offline.md), [Chat/attachments](10-chat-attachments.md) |
| End-to-end working | [End-to-end working](07-end-to-end-working.md) |
| Communication process | [Protocol](06-communication-protocol.md), [Team communication](14-workflows-processes.md) |
| Supplied desktop UI | [Desktop UI specification](12-desktop-ui.md) |
| Supplied mobile UI | [Android UI specification](13-android-ui.md) |
| Validation and delivery | [Testing](18-testing-validation.md), [Release/support](24-release-support.md), [Final checklist](26-final-checklist-glossary.md) |
| Multiple `.md` files, not a giant single document | Separate topic files under `docs/` with a linked root README |

## 4. Scope of verification

This work reviews the supplied written plan and static HTML/image references, clarifies design decisions, and checks the resulting documentation structure. It does not validate a built app, select production package versions, measure performance, approve licensing, establish trademark availability or replace a qualified security review.


## Phase 3 Windows file API reference

- [FILE_RENAME_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_rename_info) — a destination directory handle can anchor a relative rename; non-replacement semantics are required. Reviewed for source design only, not proof of native behavior. Native race/ACL/filesystem tests remain mandatory.

## Android Phase 5 — messaging integration references (2026-10-31)

The locked implementation/binding decision is documented in [chat crypto/review gates](android/chat-crypto.md), [actual wire protocol](android/chat-protocol.md) and [measured validation](android/phase-5-validation.md). These references do not replace independent review.

- vodozemac0.11.1 API/release material: <https://docs.rs/vodozemac/0.11.1/vodozemac/> and <https://raw.githubusercontent.com/matrix-org/vodozemac/0.11.1/CHANGELOG.md>. Historical upstream auditing does not certify this release/JNI/application integration.
- Official NDK downloads: <https://developer.android.com/ndk/downloads>. r30/30.0.16248370 was used for four Android ABI compilation; static16 KiB inspection is not execution.
- Official Rust1.99.1 target manifest: <https://static.rust-lang.org/dist/2026-10-15/channel-rust-1.99.1.toml>. Archive hashes checked against HTTPS metadata; no independent publisher approval claimed.
- Platform keystore capabilities: <https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder>. Hardware reporting is not attestation; software Olm state remains encrypted-at-rest software state.

Dependency/advisory/license review, native Android/Windows testing and release-time target-SDK/local-network policy review remain explicit gates.
