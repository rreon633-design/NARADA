# NARADA — Implementation Task Backlog

> **Current desktop source 0.7.0:** see [phases 5–9 scope and readiness](desktop-5-9-status.md), [current validation](desktop-5-9-validation.md) and [cryptography decision](crypto-content-decision.md). Original cross-platform/native/security acceptance checkboxes remain open where evidence is missing. Android Phase 1 now has a separate [implementation plan](../android-plan.md) and [validation record](android/validation.md); later Android features and qualification remain open.

> **Current implementation:** desktop phases 1–3 have a development implementation. Phase 4 source is written, but its single pass failed to build the backend; targeted corrections are unverified (see [results](phase-4-validation.md)); Android and native Windows/LAN/filesystem/security qualification remain open. See [Phase 3 implementation](phase-3-implementation.md), [actual file contract](phase-3-protocol.md), and [live tracker](../plan.md). The wider backlog below includes future features, not claims that those features are shipped.

> [Documentation index](../README.md) · Proposed tasks; all unchecked · 30 October 2026  
> Owners below are roles, not assigned people. Dependency numbers refer to `NAR-xxx` tasks.

## 1. Phase 0 — feasibility and contracts

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-001 — Freeze product/release boundaries | PM | — | P0, preview, deferred features and unsupported claims approved. |
| [ ] NAR-002 — Threat model and deployment ADR | CORE/SEC | 001 | Per-user agent, trust boundaries and local-only policy documented. |
| [ ] NAR-003 — E2EE library feasibility | CORE/AND/SEC | 002 | Cross-platform encrypted exchange, licensing and durable session integration demonstrated. |
| [ ] NAR-004 — Encrypted-store feasibility | CORE/AND | 003 | Wrong-key, restart, WAL, transaction and migration tests pass. |
| [ ] NAR-005 — Windows safe-path prototype | CORE/SEC | 002 | Traversal/reparse/race tests prove supported containment or reject unsupported cases. |
| [ ] NAR-006 — Platform/lifecycle/SAF matrix | AND/CORE/QA | 001 | Qualified OS/device/provider baseline and interruption strategy selected. |
| [ ] NAR-007 — Protocol and error contracts | CORE/AND | 002,003,005 | Versioned schemas, limits, IDs and shared positive/negative fixtures. |
| [ ] NAR-008 — Desktop/mobile UI baseline | DESK/AND/PM | 001 | Supplied references mapped to real routes, states, permissions and release scope. |

## 2. Phase 1 — build and platform foundation

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-009 — Repository/toolchain scaffold | CORE/AND | 007 | Windows and Android build from a clean documented environment. |
| [ ] NAR-010 — CI and dependency controls | REL/QA | 009 | Lint, tests, license/advisory reports and lockfiles enforced. |
| [ ] NAR-011 — Windows user-agent lifecycle | CORE | 002,009 | Single instance, safe startup/shutdown and UI-independent execution. |
| [ ] NAR-012 — Authenticated local IPC | CORE/DESK | 011 | Typed commands, DACL/peer checks and no remote admin interface. |
| [ ] NAR-013 — Repositories and migrations | CORE/AND | 004,009 | Transactions, schema upgrades and interrupted migration recovery. |
| [ ] NAR-014 — Desktop shell/components | DESK | 008,012 | Monochrome shell, real navigation, semantic controls and UI fixture adapter. |
| [ ] NAR-015 — Compose shell/navigation | AND | 008,009 | Five tabs, More destinations, nested Back and lifecycle-aware state. |
| [ ] NAR-016 — Background jobs/notifications/storage adapters | AND/CORE | 006,013,015 | Job ownership, cancellation, private files, URI grants and native notifications. |

## 3. Phase 2 — secure connection

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-017 — Transport identities and key storage | CORE/AND | 004,013 | Protected identity survives restart; key loss fails visibly. |
| [ ] NAR-018 — Real QR bootstrap/approval | CORE/AND/DESK | 007,014,015,017 | Offline scanner, one-time invitation, expiry and local approval work. |
| [ ] NAR-019 — mTLS and capability negotiation | CORE/AND/SEC | 017,018 | Paired identities verified; unknown/changed/revoked peers fail. |
| [ ] NAR-020 — Per-root/device authorization | CORE | 007,019 | All routes/events/IPC operations enforce the correct scope. |
| [ ] NAR-021 — NSD/mDNS and interface selection | CORE/AND | 019 | Candidate discovery without identity trust; no-internet LAN works. |
| [ ] NAR-022 — Manual endpoint/reconnect diagnostics | CORE/AND | 021 | Backoff, network changes and clear routing/firewall/identity errors. |
| [ ] NAR-023 — Revocation and active-session invalidation | CORE/AND | 020 | Connections close; new chunks/commits denied; audit state persists. |
| [ ] NAR-024 — Connection contract/security tests | QA/SEC | 018–023 | Reuse, expiry, replay, malformed handshake and downgrade tests pass. |

## 4. Phase 3 — approved file access and UI

> Desktop development implementation for NAR-025/026/027/029/030/031/032 is now supplied; see [task mapping and limits](phase-3-implementation.md) and [observed validation](phase-3-validation.md). The original acceptance checkboxes remain open where native/security or cross-platform gates are unmet. NAR-028 remains deferred.


| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-025 — Root picker/registry/exclusions | CORE/DESK | 005,012,020 | Explicit safe root selection and removal; no whole-disk default share. |
| [ ] NAR-026 — Safe read/mutation resolver | CORE/SEC | 005,025 | Handle-based checks, collision rules and hostile-name tests pass. |
| [ ] NAR-027 — Index, listing and watcher | CORE | 013,026 | Paginated metadata and reconciliation after missed watcher events. |
| [ ] NAR-028 — Android file browser | AND | 015,027 | Real folder/search/selection state, relative paths and denied/offline states. |
| [ ] NAR-029 — Desktop Files workspace | DESK | 014,027 | Reference-aligned tree/table/inspector with real selection and resize behavior. |
| [ ] NAR-030 — Safe image preview | CORE/AND/DESK | 026,028,029 | Bounded decoding, unsupported-format handling and content permissions. |
| [ ] NAR-031 — Authorized search/sort/details | CORE/AND/DESK | 027–029 | Stable pagination and no revoked/excluded content leakage. |
| [ ] NAR-032 — Rename/trash/restore | CORE/DESK/AND | 020,026,013 | Version checks, idempotency, confirmation, retention and safe restore. |

## 5. Phase 4 — verified resumable transfer

**Desktop status:** bounded implementation and test code are written for NAR-033–036/038–040; the single pass ran with 27 JavaScript passes, but failed to build the backend. Targeted visibility/formatting corrections remain unverified; dependent suites did not run. NAR-037 remains Android-deferred. The full original exit, native/large-file/physical failure matrix is not claimed complete. See [current implementation](phase-4-implementation.md) and [validation](phase-4-validation.md).

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-033 — Stable snapshots/manifests | CORE/AND | 006,026 | Mutable/unknown-length sources handled with quotas and immutable digests. |
| [ ] NAR-034 — Bounded chunk transport | CORE/AND | 007,033 | Upload/download chunks with index/length/hash limits and backpressure. |
| [ ] NAR-035 — Durable checkpoints/resume | CORE/AND | 013,034 | Restart requests only missing/uncertain chunks from the same manifest. |
| [ ] NAR-036 — Commit-intent recovery | CORE/AND | 026,035 | Crash at each publish boundary yields one safe result or explicit recovery. |
| [ ] NAR-037 — SAF export/retry | AND | 016,035,036 | Private verified copy retained on provider failure; export guarantees truthful. |
| [ ] NAR-038 — Queue/progress/controls/receive offers | DESK/AND/CORE | 034–037 | Pause/cancel/retry and desktop-to-phone offers use durable state. |
| [ ] NAR-039 — Quotas/fairness/cleanup | CORE/AND | 033–038 | Bounded disk/RAM/jobs and protected control traffic. |
| [ ] NAR-040 — Transfer failure matrix | QA/SEC | 033–039 | Integrity, resume, duplicate-commit, disk-full and revocation tests pass. |

## 6. Phase 5 — private messaging

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-041 — E2EE adapter/session lifecycle | CORE/AND/SEC | 003,004,019 | Paired key binding, session changes and bounded protocol state. |
| [ ] NAR-042 — Transactional protected outbox | CORE/AND | 041 | Commit envelope + session state before send; retry same ciphertext. |
| [ ] NAR-043 — Inbox/dedup/session transaction | CORE/AND | 041 | Same message displays once; same-ID/different-content is rejected. |
| [ ] NAR-044 — Durable receipts and status | CORE/AND | 042,043 | No delivered state before recipient commit; lost receipt recovers. |
| [ ] NAR-045 — Chat/history UI on both devices | DESK/AND | 014,015,044 | Real counterpart, pagination, pending/failed/unread and offline behavior. |
| [ ] NAR-046 — Privacy/notification/key-loss UX | DESK/AND/SEC | 045,016 | Private previews, honest session errors and documented recovery limits. |

## 7. Phase 6 — encrypted attachments

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-047 — Reviewed streaming attachment encryption | CORE/AND/SEC | 041,033 | Cross-platform vectors, tamper detection and safe interrupted-encryption policy. |
| [ ] NAR-048 — Ciphertext blob transport/retention | CORE/AND | 039,047 | Immutable resumable blobs, peer ACL and reference-aware cleanup. |
| [ ] NAR-049 — Protected manifest/key messages | CORE/AND | 044,048 | Content keys never appear in transport metadata or logs. |
| [ ] NAR-050 — Attachment receive/preview/export UI | DESK/AND | 037,049 | Message receipt separate from verified attachment availability. |

## 8. Phase 7 — pinning and selected-content refresh

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-051 — Ordered metadata journal | CORE | 027,013 | Index/journal transaction, exclusions and overflow reconciliation. |
| [ ] NAR-052 — Scoped cursors/snapshots | CORE/AND | 051,020 | Stable snapshot fence and recovery after expiry or grant changes. |
| [ ] NAR-053 — Persistent pin store and quota | AND | 035,037,039 | Verified pins survive cache cleanup and ordinary restart. |
| [ ] NAR-054 — One-way refresh and Sync UI | CORE/AND/DESK | 052,053 | Old pin remains usable until replacement verifies; metadata/content statuses differ. |
| [ ] NAR-055 — Conflict/tombstone safety | CORE/AND | 032,054 | No silent overwrite or deletion of last pinned/edited copy. |
| [ ] NAR-056 — Retention/sweeper/repair | CORE/AND | 048,055 | No removal of active jobs, unsent attachments or unresolved conflicts. |

## 9. Phase 8 — qualification and hardening

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-057 — Parser/path/protocol fuzzing | QA/SEC | 024,040,055 | Reproducible corpus; crashes and unsafe paths fixed. |
| [ ] NAR-058 — Desktop/mobile UX/accessibility audit | QA/PM/DESK/AND | 008,029,045,050,054 | P0 surfaces, keyboard/TalkBack, scaling, IME and honest states pass. |
| [ ] NAR-059 — Performance/capacity report | QA/CORE/AND | 039,050,056 | Defined targets measured on declared hardware; regressions explained. |
| [ ] NAR-060 — OS/router/provider compatibility | QA | 006,022,040,056 | Supported matrix and unsupported cases published. |
| [ ] NAR-061 — Installer/update/signing path | REL/CORE/AND | 010,016 | Clean install, offline prerequisites, signature verification and deferred updates. |
| [ ] NAR-062 — Redacted diagnostics/support bundle | CORE/AND/REL | 013,046 | User-inspectable export with secret/content leakage tests. |
| [ ] NAR-063 — Migration/repair/restore drills | QA/CORE/AND | 004,013,056 | Interrupted upgrades and crypto-state rollback handled safely. |
| [ ] NAR-064 — Independent release security review | SEC | 057,060–063 | No unresolved critical/high findings; claims match evidence. |

## 10. Phase 9 — release and feedback

| Task | Owner | Depends on | Acceptance result |
|---|---|---|---|
| [ ] NAR-065 — Signed release artifacts/provenance | REL | 061,064 | Reproducible release process, checksums, licenses and SBOM. |
| [ ] NAR-066 — Support/incident readiness | REL/PM/SEC | 062,064 | Named response owner, issue template and containment drill. |
| [ ] NAR-067 — Documentation/claim freeze | PM/QA | 058–065 | Feature matrix, limitations, setup and recovery reflect shipped behavior. |
| [ ] NAR-068 — Consent-based pilot and go/no-go | PM/QA/REL | 065–067 | Metrics and failure evidence reviewed; release or delay decision recorded. |

## 11. Definition of ready

- User outcome, release priority and owner exist.
- Dependencies and unanswered decisions are visible.
- API/UI/state/data changes are specified.
- Positive and failure acceptance cases exist.
- Platform hardware and security-review requirements are known.

## 12. Definition of done

- Acceptance criteria pass on both affected platforms.
- Correct authorization, validation, idempotency and persistence are implemented.
- Offline, cancellation, restart, disk-full and permission-loss paths are covered where relevant.
- Unit/integration/contract tests and appropriate real-device evidence are attached.
- UI follows the supplied references without fake security/status behavior.
- Logs, diagnostics, backups and notifications do not leak prohibited data.
- Migrations, compatibility and recovery are documented and tested.
- Relevant performance/accessibility budgets pass.
- Documentation and known limitations are updated; required reviewers approve.

P1/P2 features from the [feature catalogue](02-features.md) become separate estimated tasks after 1.0 gates are stable. Do not quietly bundle them into these P0 tasks.
