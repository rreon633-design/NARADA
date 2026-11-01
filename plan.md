# NARADA — Desktop Implementation Plan

> **Android now has a separate tracker:** [Android phases 1–9](android-plan.md). Its Phase 1 development foundation is implemented and host-validated; native qualification and phases 2–9 remain open. This page preserves desktop scope and evidence.

> **Current handoff, 31 October 2026:** [Desktop phases 5–9](DESKTOP-5-9-HANDOFF.md). One recorded pass: **342 passed / 6 failed / no retries**. Ten source corrections and four new regression definitions are unrebuilt/untested. Source/tooling supplied is not native, independent-review, signing or pilot acceptance.

> Updated: 30 October 2026 · [Source roadmap](docs/16-phase-wise-plan.md) · [Phase 3 evidence](docs/phase-3-validation.md) · [Run/build/test](docs/desktop-development.md)

This is the desktop phase-wise tracker. Checked boxes below mean the **specified implementation/evidence** exists; they do not waive native security or release gates.

## Phase-wise status

| Phase | Deliverable | Current status |
|---|---|---|
| 0 | Security, cryptography, storage and platform feasibility | Open. No E2EE/pairing/filesystem security qualification is implied. |
| **1** | **Desktop foundations: agent, metadata, local IPC and app shell** | **Implemented and tested on the development host; in progress pending native Windows gates.** |
| **2** | **Trusted identity, real QR pairing, mTLS and LAN discovery** | **Desktop implementation complete; validated with separate real agents. Native Windows/real-LAN/security qualification and Android remain open.** |
| **3** | **Approved roots, file browser, previews and managed operations** | **Desktop development slice implemented and tested. Native Windows/filesystem/security exit gates remain open; Android deferred.** |
| **4** | **Verified resumable transfers and receive offers** | Historical 0.4 failed pass preserved. The new frozen 0.7 pass completed transfer regression successfully; later startup/recovery changes remain unverified. Native qualification is open. |
| 5 | E2EE chat, protected history and durable receipts | Desktop source/test/UI implemented; see the separately recorded current validation. Independent/native/Android gates remain open. |
| 6 | Encrypted attachments | Desktop source/test/UI implemented with fresh-key age ciphertext and explicit export; qualification open. |
| 7 | Pins and one-way selected-content refresh | Desktop source/test/UI implemented: fenced snapshots, scoped journal and preserved versions. |
| 8 | Security, performance, accessibility and native release qualification | Host test/resource/corpus/fuzz tooling supplied; native/external review evidence remains open. |
| 9 | Signed release, pilot and support readiness | Signing/update/inventory/recovery/evidence-gate tooling supplied. Actual signing and pilot are external blockers. |

## Current desktop phases 5–9 batch

Source **0.7.0**, metadata schema **6**. This is implementation completion for the described desktop development slice, not full original P0 or release qualification. Android was outside that desktop batch; its new native foundation has separate source/evidence.

- [Scope/task mapping](docs/desktop-5-9-status.md)
- [Actual new validation](docs/desktop-5-9-validation.md)
- [Crypto decision](docs/crypto-content-decision.md)
- [Native/security matrix](docs/desktop-security-matrix.md)
- [Release gate and support tooling](docs/desktop-release-readiness.md)

The Phase 4 failed pass below is historical and unchanged. Only the new record can establish current regression results.

## Phase 1 — historical desktop foundation handoff

### Implemented foundation

- [x] NAR-009 implementation: Cargo/npm workspaces, vanilla JavaScript/Vite UI, Tauri shell source, pinned toolchain and lockfiles.
- [x] NAR-010 implementation: Rust, JavaScript, browser and separate-process test suites; Linux/Windows CI definition. Remote CI execution remains unobserved.
- [x] NAR-011 implementation: separate user agent, validated configuration, exclusive workspace lock, status inspection and shutdown handling. Linux process behavior tested; native Windows lifecycle remains open.
- [x] NAR-012 implementation: bounded typed RPC, same-user Unix development socket, owner/workspace-scoped Named Pipes. Windows code cross-compiles/lints; native access-control testing remains open.
- [x] NAR-013 implementation: two SQLite migrations, transactional preferences, bounded activity, persisted diagnostics and corruption-preserving errors.
- [x] NAR-014 desktop foundation: monochrome reference-aligned shell, navigation, real settings/setup/activity/diagnostics, keyboard search and responsive layout. Future-phase pages are not implemented product capabilities.
- [x] Narrow native command bridge and capability/CSP configuration supplied; no unrestricted frontend filesystem or shell API.

### Observed validation and handoff

- [x] 22 Rust tests pass on Linux; formatting and strict Clippy pass.
- [x] 6 JavaScript unit tests pass.
- [x] 10 real-process tests pass, including clean/crash restart, exclusive lock and preserved corrupt metadata.
- [x] 21 browser end-to-end/accessibility tests pass against an isolated real agent; historical count in [validation summary](artifacts/validation-summary.json).
- [x] Windows-target IPC cross-Clippy/type checks pass; default-feature Windows agent GNU cross-build succeeds. Neither is Windows runtime qualification.
- [x] Runnable development preview, source, commands and known limitations supplied.
- [x] Original focused documentation and five supplied reference assets preserved.

### Open native and phase-exit gates

- [ ] Complete a Windows MSVC Tauri build and execute the configured Windows CI job. The Linux shell cross-check did not finish.
- [ ] Run different-user/remote Named Pipe denial tests and inspect native app-data ACLs.
- [ ] Qualify tray, UI close/reopen/quit, sleep/wake, sign-out and independent-agent continuity.
- [ ] Implement/qualify login startup registration and uninstall cleanup policy.
- [ ] Verify native WebView2 diagnostics export, CSP, focus/accessibility and multiple-UI behavior.
- [ ] Validate sidecar packaging and current-user installation. Signing and updates remain release-phase work.
- [ ] NAR-015 and Android-specific NAR-016: native Android foundation is outside this desktop slice and remains open.

## Phase 2 — completed desktop development slice

The user explicitly selected **desktop only**, tested with a separate reference agent. This does not complete the Android or native release gates in the original multi-platform roadmap.

### Implemented

- [x] NAR-017 desktop: P-256 identity, DPAPI implementation, explicit development vault, marker-bound persistence and visible failure on missing/corrupt/swapped keys.
- [x] NAR-018 desktop: real QR, bounded offline PNG/JPEG decoder, single-use 120-second invitations, mTLS proof, owner comparison/approval and durable final acknowledgement.
- [x] NAR-019 desktop: TLS 1.3, exact pins plus WebPKI/signature checks, required capabilities, no downgrade/resumption/0-RTT.
- [x] NAR-020 desktop connection scope: separate per-device/root capability policy, local versus remote grants, expected revisions/epochs; owner commands are not LAN APIs. Future content routes still need integration.
- [x] NAR-021 source: selected-interface listener/outbound binding, Windows Private/Domain profile guard and opt-in anonymous mDNS candidates. Actual native multicast/LAN qualification remains unchecked below.
- [x] NAR-022 desktop: manual pinned endpoints, bounded jittered reconnect and actionable identity/routing/firewall failures.
- [x] NAR-023 desktop connection scope: durable revoked state, cleared grants, counter invalidation and active/finishing-session cancellation. No chunk/commit implementation is claimed.
- [x] NAR-024 development evidence: independent OpenSSL peer, real separate-agent tests, QR/browser flows and original foundation regressions.
- [x] Devices UI, 16 narrowly declared native frontend commands, shared agent dispatcher, third SQLite migration and updated focused documentation.
- [x] **126 passing automated tests:** 49 Rust, 11 JavaScript, 10 lifecycle, 30 peer-process/security and 26 browser/accessibility. The peer suite includes real-time expiry, session rollover and retry limits.
- [x] Linux strict Clippy/rustfmt and Windows GNU agent/connection/IPC strict cross-Clippy; default-feature Windows agent cross-build. Not Windows runtime evidence.

### Still-open qualification, not hidden completion claims

- [ ] Execute Windows MSVC Tauri build and native DPAPI, owner/SID/app-data ACL, tray/lifecycle, WebView2/CSP/QR/export and installer tests.
- [ ] Exercise actual Private/Public network transitions, selected NICs, firewall, DHCP, mDNS/multicast/router/hotspot and no-internet LAN behavior on Windows hardware/VMs.
- [ ] Complete independent cryptographic/protocol/security review, fuzz/resource-adversary tests and dependency/license review.
- [ ] Implement Android identity, scanner, NSD, lifecycle and platform storage; it is outside the user's selected scope.
- [x] Integrate current authorization with the Phase 3 file handlers; tested with real separate agents.
- [ ] Phase 4 transfer/commit authorization is now implemented; single-pass development results and native qualification must be reviewed separately.

## Scope and next step

No fake paired phone, shared files, transfer progress or delivered-message state is presented. QR images are generated from real ephemeral invitations, and Connected requires an authenticated exchange. SQLite contains operational metadata, not private transport keys or content; labels/endpoints are not encrypted. The browser adapter remains development-only.

## Phase 3 — desktop development handoff

Scope remains desktop with a separate real reference agent. This is not a blanket completion of the original Windows–Android/native phase exit.

### Implemented and exercised

- [x] NAR-025 development: explicit root registry, protected/overlap checks, exclusions and removal without deleting originals. Windows picker source provided; native execution remains open.
- [x] NAR-026 development: opaque IDs, strict path/name policy, handle-based adapters, link/hardlink refusal, no-overwrite and version/collision tests. No exhaustive native race-safety claim.
- [x] NAR-027: persistent bounded index, pagination, watcher hints, explicit rescan and a tested 30-second reconciliation fallback.
- [x] NAR-029: reference-aligned actual tree/list/grid/inspector, breadcrumbs, metadata, selection and pane resizing.
- [x] NAR-030: bounded PNG/JPEG decoder, stripped JPEG output and client encoded-frame checks.
- [x] NAR-031: root-scoped search/sort/details, scope/epoch-bound cursors, current grants/exclusions and reauthorization failure clearing.
- [x] NAR-032 development: files/empty-folder rename, managed trash/restore, durable actor-bound receipts, same-operation retry and ambiguous-state blocking. Recursive/nonempty-folder operations and purge are not implemented.
- [x] 200 automated tests pass: 70 Rust, 19 JavaScript, 10 lifecycle, 30 full pairing-process, 32 full file-process and 39 browser/accessibility tests.
- [x] Production frontend build, strict Linux/GNU Clippy, GNU agent link, formatting, 26-command parity and source/evidence handoff.
- [x] Focused [implementation](docs/phase-3-implementation.md), [wire contract](docs/phase-3-protocol.md), [usage/recovery](docs/files-and-recovery.md) and [validation](docs/phase-3-validation.md) guides.

### Unfinished phase-exit gates

- [ ] Execute the native Windows folder picker and complete MSVC Tauri build/runtime qualification.
- [ ] Qualify NTFS handle/namespace/version semantics, reparse/short-name aliases, hardlinks, ACL/share modes, hostile concurrent writers and root changes.
- [ ] Execute disk-full/power-loss/recovery cases beyond the current disposable state fault injections; supply a qualified owner recovery-resolution tool.
- [ ] Perform independent containment/decoder review, fuzzing, resource/large-root and physical LAN tests.
- [ ] Implement NAR-028 / Android only when its deferred scope is resumed.

**Historical Phase 3 next step:** Phase 4 verified transfers. The current Phase 4 implementation is described below; chat, encrypted attachments and sync remain unavailable.

See the current [Phase 3 implementation](docs/phase-3-implementation.md), [file protocol](docs/phase-3-protocol.md), [files/recovery](docs/files-and-recovery.md) and [validation evidence](artifacts/phase-3/README.md). Historical documentation and Phase 1/2 ZIPs remain preserved; Phase 3 has a separate source handoff.


## Phase 4 — source written; validation incomplete

The checked items in this section record source implementation only. The single pass did not establish backend behavior. See the [failed-pass results and unverified corrections](docs/phase-4-validation.md).

- [x] NAR-033 bounded immutable snapshot/manifest source; originals are never moved.
- [x] NAR-034 separate bounded binary chunks, index/length/hash checks and backpressure.
- [x] NAR-035 durable receive bitmap and restart rehash; same-manifest resume.
- [x] NAR-036 no-overwrite commit intent, typed receipt and conservative reconciliation source.
- [ ] NAR-037 Android SAF export — deferred with Android.
- [x] NAR-038 JavaScript/CSS queue, file actions, approval, offers, pause/cancel/retry and real progress.
- [x] NAR-039 explicit file/disk/job/data-lane quotas and identity-matched managed cleanup.
- [x] NAR-040 Rust/JS/independent-peer/separate-agent/browser test **source written**.
- [x] Executed and preserved the requested **single consolidated validation pass**: 27 JavaScript passes, two failed checks, dependent suites not run.
- [x] Applied the reported visibility/formatting corrections without a rebuild or retest; recorded before/after hashes.
- [ ] Obtain a successful corrected backend build and execute backend/process/browser/Windows/default-boundary suites in a separately authorized pass. Both previews remain stopped.
- [ ] Native Windows/physical-LAN/actual disk-full/power-loss/security and large-file gates remain open; this is not release qualification.

Current guides: [implementation](docs/phase-4-implementation.md), [protocol](docs/phase-4-protocol.md), [workflow/recovery](docs/transfers-and-recovery.md), [validation](docs/phase-4-validation.md). Historical ZIPs/evidence and migrations 0001–0004 are preserved.
