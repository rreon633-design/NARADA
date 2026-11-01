# Phase 1 — desktop implementation and evidence

[README](../README.md) · [Live phase tracker](../plan.md) · [Run/build/test](desktop-development.md) · [Windows qualification](windows-validation.md)

**Date:** 30 October 2026 · **Application:** 0.1.0 · **Status:** working desktop foundation in development; native Windows release gates open.

## What is implemented

| Layer | Checked-in implementation |
|---|---|
| Workspace | Cargo workspace, npm workspace, lockfiles, Rust toolchain pin, formatting, Linux/Windows CI definition. |
| Protocol | Version 1, UUID correlation, strict typed commands/settings, rejected unknown fields, 65,536-byte framed IPC limit. |
| Core | Real SQLx SQLite, two embedded migrations, WAL, synchronous FULL, foreign keys, transactions and a four-connection pool. |
| Metadata | Validated local preferences, setup completion, bounded activity, persisted diagnostics. No chat, identity keys or user-file content. |
| Agent | Separate user process, validated TOML config, exclusive workspace lock, local status command, bounded RPC and shutdown handling. |
| Windows IPC | SID-scoped protected pipe DACL, remote rejection, first-instance protection, peer process-token checks, distinct workspace hash. Cross-compiled/linted; native enforcement not yet qualified. |
| Unix development IPC | Same-effective-user credential checks, 0700 metadata directory, 0600 socket, bounded connections/frames/timeouts and endpoint cleanup. |
| Tauri shell | Four frontend commands, capability manifest, bundled CSP, tray/open/quit handlers, independent sidecar launch and persisted close-to-tray preference. Source supplied; full native build/runtime unqualified. |
| UI | Reference-aligned light monochrome shell, Home, Settings, setup wizard, activity drawer, real diagnostics/export, keyboard search, navigation and help. |
| Future UI | Files preserves tree/table/inspector layout. Devices, Chat, Transfers and Sync state their future phases; no fake connections or data. |

### Actual source map

```text
NARADA/
├── plan.md                         live progress, not a replacement for the roadmap
├── crates/
│   ├── narada-protocol/             DTOs, validation and protocol limits
│   ├── narada-core/                 SQLite state, migrations, diagnostics
│   └── narada-ipc/                  Named Pipes + Unix development transport
├── apps/
│   ├── windows-agent/              separate Rust executable
│   └── desktop-shell/src-tauri/    native Tauri entry/config/capabilities
├── desktop-ui/src/
│   ├── components/                 accessible controls, dialogs, icons
│   ├── pages/                      actual and explicitly future-phase views
│   ├── services/                   native/development bridges
│   ├── lib/                        safe DOM construction and formatting
│   └── styles/                     responsive, self-contained styles
├── tests/                          browser and process integration
├── scripts/                        development and native build supervisors
├── config/                         non-secret validated configuration
├── .github/workflows/              CI definition, not an observed CI run
├── artifacts/                      test results and representative screenshots
└── docs/ + references/             original linked planning pack and supplied assets
```

## Real end-to-end flow

1. The agent locks its user workspace, migrates SQLite and validates existing preferences.
2. Native UI commands cross OS-authenticated local IPC. The development browser instead uses the explicitly feature-gated, token-protected loopback adapter through Vite's same-origin proxy.
3. Reads return actual preferences, activity, migration status and metadata size from that host.
4. Saves are validated again by Rust and committed transactionally with a metadata-only activity event. Reload/restart reads persisted values, not browser local storage.
5. Health checks inspect SQLite integrity and connection durability settings. The report distinguishes build-level peer-network absence and **not-applicable sensitive storage** from implemented encrypted storage.
6. Export uses an explicit field allowlist: no workspace name, host paths, activity, content or keys. Clearing activity preserves preferences/diagnostics and leaves one audit event recording the clear.
7. Invalid inputs, corrupted metadata, failed binding or protocol mismatches surface errors; they do not silently reset data or fabricate a connected state.

The local OS identity is the security principal. A process with full access to the same user account is not an independently isolated attacker. No bulk data or unrestricted shell/filesystem command is exposed to the frontend.

## Evidence observed in this session

Environment: Linux x86_64, Rust 1.99.1, Node 20.20.2, Chromium through Playwright. These are development-host results, not Windows runtime measurements.

- **22 Rust tests:** protocol/settings validation; migration idempotency; settings/diagnostics persistence; invalid-input nonmutation; bounded and malformed frames; timeout; response-correlation rejection; Unix RPC and cleanup.
- **6 JavaScript unit tests:** formatting, Unicode/name validation, semantic preference comparison and honest runtime labels.
- **10 separate-process tests:** clean restart, confirmed-write recovery after forced process termination, exclusive ownership, client exit without agent exit, corrupt-database preservation, schema/permissions, signal shutdown, token enforcement and surfaced preview bind failure.
- **21 browser tests:** real-agent setup/save/reload, validation/discard guard, stored-text injection prevention, persisted diagnostics/redacted download, confirmed history clearing, keyboard/modal interaction, future-feature honesty, offline/retry, development HTTP rejection, eight page accessibility scans, compact layout and dialog checks. The current count and results are recorded in [validation summary](../artifacts/validation-summary.json) and [browser results](../artifacts/browser-results.json).
- Rust formatting and strict Clippy passed for the non-GUI workspace, including the development feature. Windows-target IPC Clippy, including its Windows-only tests, passed as a **cross-check**, not execution.
- The **default-feature Windows agent cross-build** completed for `x86_64-pc-windows-gnu`. That executable was not run on Windows and is not delivered as a qualified installer.
- The full Tauri Windows cross-check did not finish in this constrained Linux environment; it stalled compiling the large Windows API dependency and was stopped. Do not infer a successful shell build from the agent build.
- npm audit reported zero known vulnerabilities in the installed lockfile at the time of testing. This is not a complete supply-chain or security audit.

Automated accessibility results cover the tested states and WCAG 2 A/AA / 2.1 AA axe rules. They are not certification, full WCAG 2.2 coverage, or Windows screen-reader qualification. Timings are test execution durations, not product performance guarantees.

## Deliberate non-features and remaining gates

- No peer discovery, QR pairing, trusted-device identity, mTLS, user-file browsing, transfer, sync, chat or E2EE implementation.
- Notification switches persist preferences; OS notification delivery is not implemented.
- Login autostart is not implemented. Native tray, close/reopen, sidecar placement, installer behavior, WebView2 export and different-user/remote Named Pipe rejection need Windows tests.
- No claim of encrypted sensitive storage. Ordinary metadata SQLite is not a key store; the diagnostics screen explicitly says so.
- CI configuration exists but was not executed on a remote runner in this session.
- No code signing, auto-update service, release installer, Android Compose implementation, cross-user security qualification or release approval.

**Next action:** execute [the Windows checklist](windows-validation.md), fix native findings, and attach evidence before closing desktop Phase 1. Phase 0 security/crypto decisions and the roadmap's Android work remain open. Pairing is the next product phase, not a feature silently enabled by this shell.
