# NARADA — Release, Updates, Support and Incident Response

> **Current desktop source 0.7.0:** see [phases 5–9 scope and readiness](desktop-5-9-status.md), [current validation](desktop-5-9-validation.md) and [cryptography decision](crypto-content-decision.md). Original cross-platform/native/security acceptance checkboxes remain open where evidence is missing. Android is still deferred.

> [Documentation index](../README.md) · Release playbook · 30 October 2026

## 1. Release channels

| Channel | Audience | Requirements |
|---|---|---|
| Development | Contributors/test devices | Clearly marked, isolated test identities; no production signing secrets. |
| Transfer Preview | Controlled testers | Secure file-transfer scope only, explicit missing features and known limitations. |
| Beta | Consenting early users | Full proposed 1.0 scope, recovery evidence and controlled feedback. |
| Stable | Qualified supported users | All P0 gates, signed artifacts, honest support matrix and recovery documentation. |

Do not call a visual HTML prototype or incomplete Transfer Preview the complete NARADA 1.0 application.

## 2. Release artifacts

- Signed Windows installer with a qualified WebView2/offline-prerequisite strategy.
- Signed Android APK for an approved direct/offline distribution channel, and AAB if a store channel is adopted. An AAB is not a directly installable APK.
- Versioned protocol/schema contract and compatibility statement.
- Release notes, feature/support matrix, known limitations and migration notes.
- Artifact checksums, dependency licenses, SBOM and build provenance.
- User setup, troubleshooting, privacy and data-recovery instructions.

Signing identity, distribution accounts, app identifiers and license policy must be established by the project owner. They are not provided by this plan.

## 3. Pre-release checklist

- [ ] Clean checkout builds with pinned tools/dependencies.
- [ ] Windows and Android contract/negative tests pass together.
- [ ] Pairing, grant changes, active revocation and no-internet operation pass.
- [ ] Large-file resume, commit recovery, SAF export and disk-full tests pass.
- [ ] Chat outbox/inbox/session transactions and lost-receipt tests pass.
- [ ] Attachment tamper and interrupted-encryption tests pass.
- [ ] Pin refresh, stale cursors, conflicts and cleanup tests pass.
- [ ] Desktop/mobile UI references are implemented with genuine P0 actions and accessible states.
- [ ] Logs, notification defaults and diagnostic exports pass privacy inspection.
- [ ] Install/upgrade/uninstall and interrupted migration tests pass.
- [ ] Supported OS/router/provider matrix and measured limits are published.
- [ ] No known critical/high security finding or silent data-loss/false-delivery defect remains.
- [ ] Support/incident owner and approved contact channel are ready.

## 4. Safe update mechanism

1. Update checks are optional and separate from LAN functionality.
2. Verify trusted update metadata and artifact signatures before installation; checksums alone do not authenticate a malicious replacement.
3. Show publisher/version and relevant compatibility or permission changes.
4. Do not interrupt active transfers or crypto-store transactions without a safe quiesce/checkpoint policy.
5. Stop new work, checkpoint jobs and run preflight storage/version checks.
6. Apply tested forward migrations and recover interrupted upgrades before exposing readiness.
7. Restart, revalidate stores/identity and resume only supported work.
8. Keep a documented recovery path that does not silently erase data or downgrade authentication.

### Rollback boundary

Binary rollback is allowed only when storage/protocol compatibility has been explicitly validated. Prefer roll-forward repair for incompatible schema changes.

Do **not** restore an old cryptographic session database after messages have been sent under newer state. General metadata backups and recoverable content are distinct from active ratchet/identity state. If supported recovery cannot preserve session safety, require explicit session/identity re-establishment and explain history limits.

## 5. Installation and uninstall behavior

- Normal operation is non-elevated; any installer/firewall elevation is narrow and explained.
- Startup-at-sign-in is explicit and reversible.
- Explain that closing the UI and stopping the agent are different actions.
- Android storage grants and notification permission are requested contextually.
- Uninstall must not delete user-selected original shared folders.
- App-private pins, local chat and keys may be removed by uninstall/data-clear; warn and provide an explicit data-retention/export policy where supported.
- Remove obsolete application firewall/startup rules without altering unrelated system security settings.

## 6. Support intake

Request:

- Application, protocol and OS versions.
- Device type/provider/network category, not exact personal network credentials.
- Operation ID and user-visible error code.
- Steps to reproduce and whether original/local copies are safe.
- Optional user-inspected diagnostic export.

Never request passwords, private keys, pairing QR secrets, whole chat databases or unrelated personal documents. Use synthetic reproductions where possible. No public support address is assumed; establish the actual channel before stable release.

## 7. Incident response

| Severity | Example | First response |
|---|---|---|
| Critical | Unauthorized file access, key exposure, unsafe signed update | Stop affected distribution/function, contain exposure, preserve redacted evidence and engage security owner. |
| High | Silent file loss, false delivered state, repeatable session corruption | Halt release promotion, disable affected operation safely and prioritize verified repair. |
| Medium | Widespread discovery/transfer failure without corruption | Publish workaround, identify affected matrix and fix with regression coverage. |
| Low | Cosmetic or nonblocking usability issue | Track, reproduce and schedule without weakening safety controls. |

Process: **triage → contain → assess impact → repair → verify → communicate → prevent recurrence**.

- Assign one response owner and a next-update time.
- Avoid exposing private exploit details or user data in public issue discussions before a safe disclosure plan.
- Explain affected versions, user actions and recovery limitations plainly.
- A compromised signing key needs an explicit trusted-key recovery/distribution plan; merely publishing a new checksum is not enough.
- After resolution, write a short postmortem and add regression tests.

Response-time promises require real staffing. Proposed internal targets must not be advertised as 24/7 support until that service actually exists.

## 8. Post-release maintenance

Review dependency advisories, supported OS policies, signing validity, protocol compatibility and user-reported failure categories regularly. Keep optional new transports/themes separate from urgent security and data-safety maintenance.

Related: [Final checklist](26-final-checklist-glossary.md), [Testing](18-testing-validation.md), [Decision gates](25-decisions-open-questions.md).
