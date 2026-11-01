# NARADA — Remaining desktop phases: scope and readiness

> Handoff updated 31 October 2026 · Source 0.7.0 · Android remains deferred by the user's explicit choice

**Verification status:** one host pass completed with **342 passed and 6 failed**. Ten later source corrections, including whole-private-store-loss handling, were not rebuilt or retested. Four new recovery regressions are source only. Read [actual results](desktop-5-9-validation.md) and [corrections](desktop-5-9-corrections.md); no phase-exit or release approval is inferred from source availability.

This batch implements the remaining **desktop** development source and test/tooling surface. It does not declare original multi-platform P0, independent security review, native Windows qualification, signing or pilot approval complete.

| Phase / tasks | Supplied development work | External or qualification boundary |
|---|---|---|
| 5 / NAR-041–046 | Olm messages, protected inbox/outbox/history/drafts, receipts, retry, unread and consent UI | Crypto integration review, native store/notification behavior, Android |
| 6 / NAR-047–050 | Fresh-key age snapshots, ciphertext chunks/checkpoints, full verification, explicit inert export | age beta risk, independent interoperability/review, native publication tests, Android |
| 7 / NAR-051–056 | Transactional journal, fenced snapshots, scoped cursors, persistent version-preserving selected-file pins | Native/Android offline behavior, full physical-device failure matrix |
| 8 / NAR-057–062 | Deterministic corpus, fuzz target/workflow, regression/recovery/privacy/accessibility tests, resource probe, compatibility/security checklist | Actual extended fuzz campaign, independent audit and physical/native evidence |
| 9 / NAR-063–068 | Default-feature installer source, fail-closed evidence gate, signing script, offline signed-update verifier, SBOM/license/provenance tooling, recovery/support/runbooks | Real signing identity, audited artifacts, assigned support/pilot owners and go/no-go |

## Read these separately

- [Chat implementation](phase-5-chat.md)
- [Attachment implementation](phase-6-attachments.md)
- [Sync/offline implementation](phase-7-offline.md)
- [Cryptographic decision and caveats](crypto-content-decision.md)
- [Owner and peer protocol](content-offline-protocol.md)
- [Actual validation](desktop-5-9-validation.md)
- [Unverified post-pass corrections](desktop-5-9-corrections.md)
- [Resource/inventory observations](desktop-5-9-resources.md)
- [Security/compatibility matrix](desktop-security-matrix.md)
- [Release tooling](desktop-release-readiness.md)
- [Recovery](content-recovery.md)

The existing Phase 4 failed pass, marker, logs, archive and unverified-correction record are historical evidence and are not rewritten. The new batch uses `artifacts/desktop-5-9/` and its own one-pass marker.
