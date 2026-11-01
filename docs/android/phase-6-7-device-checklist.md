# Phases 6 & 7 — native, independent and release gates

[Measured development evidence](phase-6-7-validation.md) · [Prior Phase 5 checklist](phase-5-device-checklist.md) · [Phase 4 provider gates](phase-4-device-checklist.md)

**Status: OPEN.** There are 13 compiled instrumentation cases and zero device/emulator executions. Linux Rust/JNI/JSSE, software AES-GCM/dev-vault fixtures, Robolectric and static APK/ELF inspection are not substitutions. No remote CI, Windows execution or independent approval was obtained.

## Device and native runtime

- [ ] Execute all instrumentation on disposable API29/31/35/36 devices/emulators and supported ABI/OEM combinations; retain commands, build/signers, logs and failures.
- [ ] Load/execute both adapters on all claimed Android ABIs; test real 16 KiB-page devices, JNI errors, allocation limits, cancellation and low-memory behavior.
- [ ] Verify AndroidKeyStore identity/sealing ownership and reported hardware level; do not describe that report as attestation or require unsupported StrongBox silently.
- [ ] Exercise Conscrypt TLS1.3/mTLS/certificate pinning and network-bound Wi-Fi/Ethernet sockets. Reject VPN/cellular/public/loopback/DNS/IPv6 routes and changed networks; check clock/certificate errors.
- [ ] Run actual Android ↔ Windows approval/reconnect/grant/revoke/attachment/offline flows with DPAPI, filesystem identity and native LAN/router/firewall behavior. Do not reuse Linux dev-vault conclusions.

## Persistence, recovery and provider matrix

- [ ] Real process kill, power loss, ENOSPC, fsync/rename failure and keystore loss at every durable boundary: manifest/ratchet, ciphertext/checkpoint, inbox/receipt, finish, pin/adopt and provider create/URI/readback.
- [ ] Missing/damaged Room anchor, sealed aggregate, chunks and age/Olm state must disable networking without reset, rekey, empty-state fallback or silent repair.
- [ ] Test same-signer v1–v6 protected-payload and Room3 migrations, real backup/restore exclusion, app/OS upgrades and intentional fresh-profile enrollment. The delivered signer differs from Phase5; supplied APKs do not qualify in-place upgrades.
- [ ] Evaluate whole-state rollback resistance separately; current continuity is **not** anti-rollback proof.
- [ ] Real SAF local/cloud/removable/hostile providers: read-only imports, missing/wrong grants, virtual/tree/oversized documents, stalled query/open/read/write/close/readback, duplicate callbacks and process death.
- [ ] Verify uncertain external targets are never silently rewritten/deleted; test worker quarantine/deadline and review UX. Do not promise stoppability/atomic publication/physical durability.
- [ ] Offline retained versions through source deletion, failed refresh, stale scope, quota exhaustion, multiple references and export uncertainty; no silent eviction or destructive mirroring.

## Accessibility, privacy and resources

- [ ] TalkBack semantics/order, switch access, 200%/nonlinear font scaling, small/large screens, rotation, IME/insets and reachable consent/cancel actions on native windows.
- [ ] FLAG_SECURE before sensitive composition and secure owned dialogs; task snapshots, lifecycle/tab/authority clearing, no SavedState/Room/log/backup plaintext and generic opt-in notifications.
- [ ] Camera/scanner and external provider windows have separate privacy/permission boundaries; no misleading guarantee for another app's UI.
- [ ] Measure RAM, JNI copies, CPU/battery, storage reservation, network pacing, slow peers and resource exhaustion on low-end devices; host budgets are not phone performance qualification.

## Independent review and release

- [ ] Independent age 0.12.1/vodozemac0.11.1/JNI/application review, manifest/digest binding, replay/ratchet continuity and cross-language/native format vectors; assess 64-bit Olm MAC and implementation error handling.
- [ ] Complete dependency/advisory/license review and a release-appropriate reachability SBOM. Aggregated notices, HTTPS checksums and inventory declarations are not approval.
- [ ] Review target-37/local-network permission and distribution-policy changes before raising target/distribution scope; this build remains target36.
- [ ] Parser/fuzz/hostile-peer campaigns, platform storage/privacy review and all retained earlier-phase gates.
- [ ] Approve production package identity, controlled signing/rotation, distribution/update/rollback/support/incident procedures and a consented pilot.

Phases **8 (native qualification)** and **9 (release/pilot)** remain. Additional feature completion does not waive any earlier independent/native gate or authorize handling valuable/sensitive data in an unqualified development build.
