# NARADA

**Private, local-network desktop ↔ Android communication and file workflows.**

Native Windows desktop (Rust + Tauri) and an outbound-only Android companion (Kotlin + Compose), with explicit pairing/permissions, verified file transfers, encrypted chat/attachments and scoped offline copies.

> **Development/manual testing only.** This is not a production/security-qualified release. Read the installation and remaining-gates notes before using it; use disposable data.

## Download and test

- [GitHub Actions — fresh test/build runs](https://github.com/rreon633-design/NARADA/actions/workflows/build-test-release.yml)
- [v0.0.2 development release](https://github.com/rreon633-design/NARADA/releases/tag/v0.0.2) — published only after the required same-commit GitHub gates pass. The existing v0.0.1 release stays unchanged.
- [v0.0.2 changes and limits](docs/github/v0.0.2.md) — connection/chat/workspace repairs, simpler pairing, interface cleanup and sharing Stage 1. **Automatic two-way folder sync is not yet enabled.**
- [Installation, APK selection and manual-test checklist](docs/github/manual-testing.md)

Successful platform jobs provide a **Windows x64 installer** and **four separate Android APKs**, one per ABI. Check the entire workflow status, not just the presence of a platform artifact. The gated prerelease includes SHA-256 checksums, actual byte-size reports and same-commit CI evidence.

**Android:** Android 10/API 29+, test package `com.narada.mobile.dev.ci`, launcher **NARADA (Test)**. Non-debuggable/R8-minified but temporarily test-signed; no production signing or in-place-upgrade promise. **Windows:** unsigned Tauri/NSIS x64 installer using system WebView2, not Electron.

## Lightweight by design

- Per-ABI Android APKs, R8/resource shrinking and size-optimized JNI libraries.
- Tauri + system WebView2, stripped/size-optimized Rust and LZMA installer.
- Enforced guardrails: **20 MiB per APK**, **30 MiB Windows installer**, **1.5 MiB frontend**. These are limits, not claimed measured sizes; successful build reports provide actual values.
- No removal of encryption, integrity, storage-continuity checks or license notices to reduce size.

See [lightweight policy and measurement gaps](docs/github/lightweight.md).

## Build and architecture

- [`apps/android/`](apps/android/) — Kotlin/Compose/Hilt app, JVM core, Room/Keystore boundaries, age/Olm Rust JNI adapters and a separate small filesystem-only C JNI publication bridge.
- [`apps/desktop-shell/`](apps/desktop-shell/), [`desktop-ui/`](desktop-ui/) — Tauri shell and production frontend.
- [`apps/windows-agent/`](apps/windows-agent/), [`crates/`](crates/) — native agent, same-owner IPC, storage, pairing/content protocols and file engine.
- [`tests/`](tests/) — browser, native-agent process, unit and CI-tool tests.
- [CI/toolchains/release procedure](docs/github/ci.md) — portable, pinned build recipe and release gates.
- [Android roadmap](android-plan.md), [desktop roadmap](plan.md), [`docs/`](docs/) — focused architecture, feature, security, workflow and phase documents.
- [`references/`](references/) — supplied visual design references, not runtime browser bundles.

The v0.0.2 application metadata is Android/desktop/frontend **0.7.1**, Android versionCode **8**; Rust remains **0.7.1**. Protocol, trust-store and database versions are not reset. Higher version numbers do not establish upgrade/signing continuity. Never uninstall or clear valued data to force an update; use isolated disposable test profiles.

## Validation boundaries

The workflow runs fresh Linux desktop/process/browser checks, Windows native tests and installer/bootstrap, Android host/real-peer/lint checks, and native instrumentation against the same minified x86_64 APK on API 29/35 emulators. A release is published as a **prerelease only after every required job succeeds**.

Historical phase results are not new CI passes. Original archives and failed campaigns remain in the earlier handoff; large binary/evidence dumps and private runtime state are intentionally not stored in Git. See [historical baseline](docs/github/history.md).

Physical-device/hardware proof, Windows LAN/tray/accessibility scenarios, 16 KiB runtime, whole-state rollback, power-loss/hostile-provider handling, independent security/license review, production signing/upgrades and real-hardware performance remain separate qualifications. A green development workflow does not mark every roadmap gate complete.
