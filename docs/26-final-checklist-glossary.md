# NARADA — Final Acceptance Checklist and Glossary

> [Documentation index](../README.md) · Go/no-go reference · 30 October 2026

## 1. Product acceptance

A qualified user must be able to:

- [ ] Install signed Windows and Android applications using the documented channel.
- [ ] Understand the local-only operating model and laptop/phone availability limits.
- [ ] Select an approved laptop folder and review least-privilege grants.
- [ ] Pair securely using a real short-lived QR invitation and explicit approval.
- [ ] Discover or manually reach the paired endpoint without bypassing identity checks.
- [ ] Browse/search only permitted content and see clear metadata/freshness.
- [ ] Upload/download without whole-file memory buffering.
- [ ] Pause/retry/resume supported interrupted transfers without mixing versions.
- [ ] Receive verified completed files without silent overwrite or false completion.
- [ ] Understand the difference between private download and external provider export.
- [ ] Exchange one-to-one E2EE messages with protected local state on both devices.
- [ ] Distinguish pending, delivered and optional read states.
- [ ] Send and receive encrypted immutable attachments with separate availability state.
- [ ] Pin selected files, open them offline and safely refresh them later.
- [ ] Preserve conflicts and the last good pinned copy when a source changes/disappears.
- [ ] Revoke a lost peer and understand that received copies cannot be recalled.
- [ ] Export redacted diagnostics and follow safe troubleshooting/recovery instructions.

## 2. Desktop/mobile design acceptance

- [ ] Desktop follows the supplied monochrome shell and detailed tree/table/inspector reference.
- [ ] All 16 desktop reference surfaces are mapped to P0/P1 scope, native surfaces or contextual views.
- [ ] Android follows the supplied mobile visual direction in native Kotlin/Compose.
- [ ] Home/Files/Chat/Transfers/More navigation and native Back/IME behavior work.
- [ ] Original prototype fake QR patterns, static device statistics, simulated actions and hard-coded receipts do not ship as functionality.
- [ ] File operations identify the correct device/copy and current permission.
- [ ] UI is usable with desktop keyboard/screen reader and Android TalkBack/large text.
- [ ] Unpaired, offline, denied, loading, empty, failed, partial, conflict and recovery states are designed and tested.
- [ ] NARADA branding is consistent; old branding remains only in preserved source references.

## 3. Engineering and release acceptance

- [ ] ADR release blockers have evidence and approval.
- [ ] Shared contract and cross-platform tests pass.
- [ ] Path/permission/replay/resource-abuse negative tests pass.
- [ ] File/DB commit boundaries and chat/session transactions survive supported fault tests.
- [ ] No critical/high security issue, silent corruption, unsafe migration or false-delivery defect is knowingly unresolved.
- [ ] Source snapshots, caches, pins, conflicts and attachment blobs have safe quota/retention rules.
- [ ] Supported OS/router/filesystem/provider combinations and tested file sizes are published.
- [ ] CPU/memory/battery/throughput results are measured and deviations documented.
- [ ] Runtime/model/UI dependencies support the documented offline first use.
- [ ] Installers, update verification, signing, SBOM/license notices and support ownership are ready.
- [ ] Recovery does not silently restore stale crypto sessions or delete original shared content.
- [ ] Release wording matches the build and explicitly defers non-P0 capabilities.

## 4. Definition of NARADA 1.0 success

NARADA succeeds when a user can connect a Windows laptop and Android phone, safely work with explicitly shared files, recover interrupted transfers, keep selected content offline and exchange private messages/attachments—without a mandatory cloud account or internet connection, and without misleading security or completion claims.

**Engineering order:** secure identity and authorization → durable safe file operations → reliable transfers → E2EE messaging and attachments → selected-content refresh → measured qualification.

## 5. Glossary

| Term | Meaning in NARADA |
|---|---|
| Agent | Non-elevated Windows per-user background process; not automatically a machine-wide service. |
| Endpoint | A device/application that legitimately owns keys and can read its intended chat content. |
| Peer | A paired device, still limited by explicit grants. |
| Root | A locally approved shared folder with its own identity and permissions. |
| Capability | A specific permitted operation such as read, create, modify or trash. |
| mTLS | TLS where both sides authenticate with the configured trusted identity mechanism. |
| Pin / key pin | A trusted cryptographic key binding. Distinct from an offline file pin. |
| Offline pin | A managed persistent local copy of selected content. |
| E2EE | Content encrypted between intended endpoints; not protection from those endpoints themselves. |
| Ratchet/session state | Cryptographic protocol state that must be advanced and stored safely. |
| Manifest | Immutable transfer definition including version, length, chunks and integrity data. |
| Snapshot | A stable content copy or stable indexed metadata view; the two must not be conflated. |
| Checkpoint | Durable progress known to survive the qualified failure boundary. |
| Commit intent | Recovery record coordinating filesystem publication with database state. |
| Idempotency | Repeating the same logical operation safely yields the same effect/result reference. |
| Deduplication | Recognition of already accepted messages/events so retries do not duplicate visible effects. |
| Delivery receipt | Authenticated acknowledgment of the intended recipient's durable message acceptance. |
| Journal | Ordered normalized metadata changes used for incremental synchronization. |
| Cursor | Position/scope/epoch token for replaying an authorized journal. |
| Tombstone | Retained record that an object was deleted/disappeared. |
| Reconciliation | Comparing observed state after gaps to restore consistency safely. |
| SAF | Android Storage Access Framework; provider URIs are not necessarily local filesystem paths. |
| WAL | SQLite write-ahead log; useful for transaction behavior but not a standalone durability guarantee. |
| ADR | Architecture Decision Record with alternatives, risks and evidence. |
| P0/P1/P2/P3 | Required release scope, next-release candidate, advanced/experimental and long-term priority. |

## 6. Documentation versus implementation

Unchecked checklists and target metrics describe work to do. This pack is a complete planning baseline, **not** a built Windows application, Android APK, security certification or guarantee of observed performance.
