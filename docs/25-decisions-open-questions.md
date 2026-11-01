# NARADA — Architecture Decisions and Open Questions

> **Current desktop source 0.7.0:** see [phases 5–9 scope and readiness](desktop-5-9-status.md), [current validation](desktop-5-9-validation.md) and [cryptography decision](crypto-content-decision.md). Original cross-platform/native/security acceptance checkboxes remain open where evidence is missing. Android Phase 1 now has a separate [implementation plan](../android-plan.md) and [validation record](android/validation.md); later Android features and qualification remain open.

> [Documentation index](../README.md) · Decision register, not approval history · 30 October 2026

## 1. Established planning constraints

- Product name: **NARADA**.
- Deliverable format: multiple focused Markdown documents with README navigation.
- Use the supplied desktop and mobile visual references.
- Preserve the source plan's local-first Windows–Android direction.
- Preserve framework-free HTML/CSS/JavaScript desktop UI with a native shell; Android remains Kotlin/Compose.
- Core operation does not require a vendor cloud/account.
- No release claim overrides the safety requirements for authentication, file integrity, conflict preservation or truthful delivery.

These constraints do not imply the code, support matrix or cryptography integration is implemented.

## 2. ADR register

| ADR | Decision | Recommended direction | Status / owner / gate |
|---|---|---|---|
| ADR-01 | Windows process/security context | Non-elevated per-user agent; UI can close independently | Proposed; CORE/SEC; prototype lifecycle and ACL behavior in Phase 0–1. |
| ADR-02 | UI architecture and references | Tauri + vanilla desktop; native Compose mobile; monochrome shared vocabulary | Planning baseline; DESK/AND/PM; reference/state/accessibility review. |
| ADR-03 | Pairing and transport trust | Temporary pinned TLS bootstrap, local approval, paired mTLS and explicit key-change handling | Release blocker; CORE/AND/SEC; proof-of-possession/expiry/replay tests. |
| ADR-04 | E2EE protocol implementation | Maintained reviewed library, not custom crypto | Release blocker; CORE/AND/SEC; interoperability, licensing, support and persistence proof. |
| ADR-05 | Sensitive local storage | Tested encrypted transactional store with OS-protected key wrapping | Release blocker; CORE/AND; driver compatibility, WAL, wrong-key and crash tests. |
| ADR-06 | Filesystem containment/publication | Supported local roots, handle-based resolver, immutable source and commit intents | Release blocker; CORE/SEC; race/reparse/collision/power-loss qualification. |
| ADR-07 | Android storage and background work | SAF + persistent private staging; task/OS-appropriate lifecycle API | Release blocker; AND/QA; provider/OS/process-death matrix. |
| ADR-08 | 1.0 sync semantics | Metadata sync + one-way pin refresh; preserve conflicts and last good copy | Proposed baseline; CORE/AND/PM; cursor/snapshot/delete tests. |
| ADR-09 | Protocol/retry/compatibility | Versioned schemas, bounded payloads, idempotent effects and explicit expiry | Proposed; CORE/AND; cross-language contracts and old/new version tests. |
| ADR-10 | Distribution, license and updates | Signed offline-capable artifacts; verified optional update path | Release blocker; REL/SEC/project owner; legal/signing/migration readiness. |
| ADR-11 | Supported matrix and performance | Qualified Windows/Android/router/provider combinations and measured budgets | Open until prototypes; QA/CORE/AND; publish actual evidence. |
| ADR-12 | Quotas, retention and recovery | Reference-aware cleanup; explicit key/identity recovery limits | Proposed defaults; CORE/AND/PM; disk-pressure and stale-state tests. |

## 3. Release-blocking experiments

| Gate | Experiment | Required evidence | If it fails |
|---|---|---|---|
| G1 — E2EE | Windows ↔ Android session with crash/retry/reorder | Library/version/license review and transaction-safe test results | Select a reviewed alternative or formally limit release to Transfer Preview. |
| G2 — Mutual trust | QR bootstrap, pending activation, mTLS and key change | Invalid/unpaired/reused/revoked identity tests | Redesign bootstrap; do not disable validation. |
| G3 — At-rest protection | Both platform stores, backup/WAL behavior, key invalidation | No plaintext leakage; no silent fallback/reset | Change supported adapter/storage architecture before feature implementation. |
| G4 — Safe filesystem | Adversarial paths, junction changes and commit races | Supported handles/containment proof and recovery report | Narrow supported roots/operations or redesign. |
| G5 — Mobile execution | Long user transfer, job stop/timeout, force-stop/reopen and provider loss | Safe pause/resume and truthful UI on qualified devices | Narrow guarantee; select correct API; do not promise always-on work. |
| G6 — Offline first use | Fresh qualified install on internet-disconnected LAN | Scanner, runtime, UI assets, pairing and transfer all work | Bundle/prepare allowed prerequisites and document installation requirements. |
| G7 — Upgrade/recovery | Interrupted migration and incompatible rollback | Preserved original data and safe crypto state | Block release/update until recoverable. |
| G8 — UI honesty | Audit prototype-derived controls against actual domain state | No fake QR/receipt/sync/completion; denied/offline cases tested | Remove/defer unimplemented controls and correct claims. |

## 4. Open questions to answer with evidence

1. Which exact E2EE library/version and license fit Windows, Android and the intended distribution model?
2. Can its session store integrate atomically with both message stores without unsupported bindings?
3. Which encrypted database driver is supportable on both platforms, and should the general metadata driver be consolidated to reduce complexity?
4. What certificate/key storage and verifier implementation provides the intended mTLS/pin semantics on all qualified devices?
5. Which Windows root/filesystem features are safely supported in the first release, including links, removable media and busy files?
6. Which Android minimum/target SDK and OEM devices will be supported at launch?
7. Which background-transfer API/service type satisfies the actual use case on each supported Android version?
8. Which SAF providers support reliable re-read/export and which must be treated as best-effort external destinations?
9. What file-size, queue, cache and retention defaults fit representative devices without surprising storage pressure?
10. Is a real app-lock/content-backup feature needed later, and what threat/recovery model would it enforce?
11. Are multiple phones, two-way sync, Wi-Fi Direct, rich media and extended themes worth the additional maintenance burden?
12. Are the NARADA name, logo assets, application identifiers, distribution rights and signing arrangements ready for public release?

## 5. ADR template

```markdown
# ADR-XX: Decision title
Status: Proposed | Accepted | Replaced
Owner:
Date:

## Context and user/security requirement
## Options considered
## Decision and rationale
## Compatibility, licensing and operational impact
## Risks and failure behavior
## Prototype/test evidence
## Rollback or replacement strategy
## Affected tasks, documents and release claims
```

## 6. Scope-change rule

When a decision changes, update the feature catalogue, UI specs, contract, data model, backlog, estimate, tests and public wording together. A feature cannot be considered done under one document and deferred under another.

If a blocking question remains unanswered, record the uncertainty and do not describe the capability as production-ready.
