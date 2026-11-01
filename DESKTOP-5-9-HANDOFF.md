# NARADA — Desktop phases 5–9 development handoff

> 31 October 2026 · Source 0.7.0 · Metadata schema 6 · Android deferred

## Status in one paragraph

Desktop implementation and test/tooling source is supplied for phases 5–9. The single recorded host pass completed with **342 tests passed and 6 failed**. Ten source files were then corrected, including additional whole-private-store-loss hardening; these changes were **not rebuilt or retested**. This is an **incompletely verified development handoff**, not a signed installer, independently reviewed system or completed cross-platform P0 release.

## Implemented source

| Phase | Supplied work |
|---|---|
| 5 — Chat | vodozemac Olm messaging; protected transactional inbox/outbox, history and drafts; stable IDs, receipts, retry/cancel and consent UI |
| 6 — Attachments | Fresh-key age snapshots; ciphertext-only chunk/checkpoint flow; verification before availability; explicit no-overwrite export |
| 7 — Offline | Ordered transactional journal, stable snapshots and scoped cursors; quota-bound selected-file pins with preserved versions, edits and deletion conflicts |
| 8 — Hardening | Rust/Node/process/browser tests, deterministic corpus, fuzz/CI source, accessibility checks, recovery guidance and narrow resource observations |
| 9 — Release/support | Fail-closed approval gate, native signing script, offline signed-update verifier, dependency/license inventory, source provenance and support/recovery runbooks |

The desktop UI follows the supplied references. Real-agent fixture screenshots are included; no sample delivery state is used as evidence. All five supplied design assets remain preserved.

## Read next

- [Actual validation and six failures](docs/desktop-5-9-validation.md)
- [Unverified corrections and recovery finding](docs/desktop-5-9-corrections.md)
- [Scope/task map](docs/desktop-5-9-status.md)
- [Cryptography choices and limits](docs/crypto-content-decision.md)
- [Recovery and old-profile enrollment](docs/content-recovery.md)
- [Native/security matrix](docs/desktop-security-matrix.md)
- [Release tooling and pending approvals](docs/desktop-release-readiness.md)
- [Resources and supply chain](docs/desktop-5-9-resources.md)
- [Evidence/screenshots](artifacts/desktop-5-9/README.md)
- [Current source manifest](SOURCE-MANIFEST-desktop-5-9.json)
- [Package integrity record](artifacts/desktop-5-9/source-qa.json)

## What is still required

1. Explicitly authorize a new, separately recorded validation of the corrected source. Do not overwrite the existing pass or its marker.
2. Complete real Windows/MSVC/Tauri/DPAPI/ACL/share-mode/power-loss/LAN/keyboard/screen-reader testing and extended fuzz/advisory work.
3. Obtain independent cryptographic/security review and resolve dependency/license gaps. Upstream audits are not NARADA audits; age's pre-1.0 warning remains a gate.
4. Supply real approved signing credentials, artifact-bound installer/update evidence, support owners and pilot/product approval.
5. Complete Android separately before claiming the original cross-platform P0 acceptance criteria.

No live preview remains running. No corrected-source binary or signed installer is included. Profiles, private keys, identity blobs, caches, toolchains, dependencies and generated binaries are excluded.

The historical [Phase 4 handoff](PHASE-4-HANDOFF.md), its failed evidence, applied migrations and original archive are unchanged. The root `SOURCE-MANIFEST.json` belongs to that historical handoff; use `SOURCE-MANIFEST-desktop-5-9.json` for this package.
