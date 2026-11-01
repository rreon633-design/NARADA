# NARADA — Phase-wise Delivery Plan

> **Current desktop source 0.7.0:** see [phases 5–9 scope and readiness](desktop-5-9-status.md), [current validation](desktop-5-9-validation.md) and [cryptography decision](crypto-content-decision.md). Original cross-platform/native/security acceptance checkboxes remain open where evidence is missing. Android Phase 1 now has a separate [implementation plan](../android-plan.md) and [validation record](android/validation.md); later Android features and qualification remain open.

> **Current implementation:** desktop phases 1–3 have a development implementation; Android and native Windows/LAN/filesystem/security qualification remain open. See [Phase 3 implementation](phase-3-implementation.md), [actual file contract](phase-3-protocol.md), and [live tracker](../plan.md). The wider phase roadmap below includes future features, not claims that those features are shipped.

> **Implementation update:** the actual desktop commands and observed status are in [Desktop development](desktop-development.md), [Phase 1 implementation](phase-1-implementation.md) and the [live root tracker](../plan.md). The broader roadmap below is not a completion claim.
> [Documentation index](../README.md) · Planning estimate, not a committed schedule · 30 October 2026

## 1. Estimation assumptions

- Planning team: three implementation roles covering core/Windows, Android and desktop/integration, with fractional QA/product support and a qualified security reviewer.
- One Windows–Android pair, qualified local network, native Android and framework-free desktop UI.
- The supplied UI references reduce design ambiguity but do not replace application implementation or accessibility work.
- No production codebase is assumed. E2EE, encrypted storage, filesystem containment and mobile lifecycle remain feasibility risks.
- Durations below are **sequential elapsed estimates**, not person-week totals. Re-estimate after Phase 0; a solo developer should not inherit this calendar unchanged.

Base estimate: **27–39 weeks**. Add a planning risk reserve of approximately 20–30%: **about 33–51 weeks** overall. Parallel work can shorten the critical path only when staffing and dependencies support it. Do not trade security/data-integrity gates for a fixed launch date.

## 2. Phase summary

| Phase | Goal | Estimate | Tasks | Gate / milestone |
|---|---|---|---|---|
| 0 | Feasibility, scope and contracts | 2–3 weeks | 001–008 | Blocking architecture/security choices proven or scope revised. |
| 1 | Build, agent, app shells and native adapters | 2–3 weeks | 009–016 | Reproducible clients; durable storage and authenticated IPC. |
| 2 | Identity, pairing and LAN connection | 2–3 weeks | 017–024 | Only trusted authorized peers reach protected APIs. |
| 3 | Safe file roots and browsing | 3–4 weeks | 025–032 | Reference-aligned file UI with containment and mutation tests. |
| 4 | Verified resumable transfer | 4–5 weeks | 033–040 | Internal vertical slice, then Transfer Preview gate. |
| 5 | Durable E2EE messaging | 4–6 weeks | 041–046 | Cross-platform encrypted chat with truthful delivery. |
| 6 | Encrypted attachments | 2–3 weeks | 047–050 | Resumable ciphertext and verified safe receipt/export. |
| 7 | Pins and selected-content refresh | 3–4 weeks | 051–056 | Full 1.0 beta scope, not general two-way sync. |
| 8 | Qualification and release hardening | 4–6 weeks | 057–064 | Release candidate meets security/recovery/compatibility gates. |
| 9 | Pilot, release and support readiness | 1–2 weeks | 065–068 | Evidence-based stable release decision. |

Security, testing, UI accessibility and performance checks occur in **every phase**. Phase 8 consolidates them; it does not introduce them for the first time.

## 3. Phase 0 — prove the difficult assumptions

**Deliverables:** scope baseline, threat model, ADRs, protocol draft, design-reference mapping, E2EE/store/path/lifecycle prototypes and hardware matrix.

**Exit criteria:**

- An acceptable licensed cross-platform E2EE path is demonstrated, including transaction-safe state persistence.
- Protected local storage works on both endpoints, including wrong-key and restart cases.
- Windows path containment and Android destination semantics have a supported safe strategy.
- Background work selection is justified against current OS requirements.
- Any failed gate causes an explicit architecture or release-scope change.

**Do not:** spend weeks polishing all screens while the crypto/file-access foundations remain unknown.

## 4. Phase 1 — durable native foundations

**Deliverables:** workspace/CI, Windows agent, scoped Named Pipes, Tauri shell, Compose shell, migrations, lifecycle/storage adapters and redacted logging.

**Exit criteria:** both clients build reproducibly; UI can close without losing agent state; unauthorized IPC fails; Android recreation does not duplicate jobs; sensitive stores never silently fall back to plaintext.

## 5. Phase 2 — secure local connection first

**Deliverables:** real QR and offline decoder, protected identities, approval, mTLS, grants, discovery, manual fallback and revoke.

**Exit criteria:** unknown/expired/reused/changed/revoked identities cannot access content; pairing works without internet on supported LANs; discovery failures have actionable diagnostics; revocation affects active sessions.

Only authenticated APIs are exposed to the LAN. Development convenience must not leave a public unauthenticated file endpoint behind.

## 6. Phase 3 — approved files and useful interfaces

**Current desktop slice:** approved-root registry, real file UI, bounded previews and file/empty-directory managed operations are implemented/tested. Native picker/filesystem security execution and Android remain open; the original phase exit below is not fully satisfied. See [Phase 3 scope](phase-3-implementation.md).

**Deliverables:** root selection, safe listing/search, real desktop tree/table/inspector, native mobile browser, image preview, rename and managed trash.

**Exit criteria:** only allowed roots and entries appear; hostile-path tests pass; native pickers work; UI handles permission/offline/loading/empty states; screenshot-inspired controls reflect actual selected objects and grants.

## 7. Phase 4 — reliable transfer and Transfer Preview

**Current desktop slice:** source/test code now implements a 64 MiB/file, quota-bound transfer engine and real queue. The one user-requested pass ran: 27 JavaScript tests passed; the Rust build failed, so dependent suites did not run. Reported visibility/formatting issues were corrected without a rebuild/retest; the corrected backend remains unverified. Android SAF, native/large-file/actual power-loss/disk-full qualification remain open; do not mark the original exit below complete. See [Phase 4 scope](phase-4-implementation.md).

**Deliverables:** stable sources, manifests, streaming, durable resume, commit recovery, SAF export, queue/controls, receive offers and quotas.

**Exit criteria:** interrupted large transfers recover without silently mixing versions; hash mismatch blocks publication; duplicate commit produces one outcome; disk-full and provider failures preserve safe existing content; memory is bounded.

**Milestones:**

- M0 internal vertical slice is reached during this phase: pair → browse → verified file → interrupted transfer recovery.
- M1 Transfer Preview may ship to controlled testers only after all its applicable security/recovery gates pass. Label missing chat/pin capabilities clearly.

## 8. Phase 5 — encrypted chat

**Deliverables:** selected E2EE integration, protected history/outbox on both devices, session-safe retries, deduplication, durable receipts and notification privacy.

**Exit criteria:** duplicate/lost-receipt tests pass; a message cannot be delivered before receiver commit; key-change/loss behavior is clear; both endpoints can restart safely; no plaintext appears in prohibited stores/logs.

Do not market TLS-only chat as E2EE if the selected implementation is blocked.

## 9. Phase 6 — encrypted attachment reuse

**Deliverables:** streaming content encryption, immutable blobs, protected key/manifest messages, resume, decrypt/verify and safe export.

**Exit criteria:** tamper/truncation/reorder tests fail safely; keys never appear in file-transfer metadata; interruption cannot cause unsafe nonce/state reuse; message receipt and attachment availability remain distinct.

## 10. Phase 7 — offline value without unsafe sync

**Deliverables:** ordered journal, stable snapshots, scoped cursors, persistent pins, one-way refresh, conflicts, tombstones and retention cleanup.

**Exit criteria:** pins open offline; failed refresh keeps the previous usable copy; expired cursors recover through snapshots; deletions and conflicts never silently destroy the only retained version.

**Milestone:** M2 full NARADA 1.0 beta. Automatic bidirectional edit/deletion propagation stays out of scope.

## 11. Phase 8 — qualify what will be promised

**Deliverables:** fuzz/recovery reports, device/router/provider compatibility, accessibility audit, performance report, signed installer/update path, diagnostic export, migration drills and independent security review.

**Exit criteria:** no unresolved critical/high security finding; no known silent corruption or false-delivery defect; all P0 acceptance cases pass on the declared matrix; unsupported cases and measured limits are published.

A resource-target exception may be explicitly reviewed. Authentication bypass or silent data loss is not an acceptable exception.

## 12. Phase 9 — release and learn

**Deliverables:** signed artifacts, checksums/SBOM, release notes, complete support/recovery instructions, pilot evidence and go/no-go record.

**Exit criteria:** clean installation, no-internet operation, update signature verification and uninstall data boundaries are tested; response owners are assigned; published claims match the qualified build.

## 13. Critical path and scope controls

```text
Crypto/storage/path/lifecycle feasibility
    → durable platform foundations
    → authenticated pairing and grants
    → safe file access
    → resumable transfer
    → E2EE chat → encrypted attachments
    → pins/reconciliation
    → qualification → release
```

- UI components and test harnesses can proceed in parallel using explicit mock adapters.
- Sync journal work can start after the index/transfer contracts stabilize, but integration waits for durable storage and conflict rules.
- Defer dark mode, rich media, multi-phone support and new transports before cutting safety work.
- At each phase exit, update remaining estimates from measured integration work and unresolved risk—not percentage-complete guesses.

Related: [Task backlog](15-task-backlog.md), [Success metrics](17-success-metrics.md), [Decision gates](25-decisions-open-questions.md).
