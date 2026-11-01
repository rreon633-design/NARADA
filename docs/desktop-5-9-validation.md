# NARADA — Desktop phases 5–9 validation

> Recorded pass: 30 October 2026 · Handoff update: 31 October · Source 0.7.0 / metadata schema 6

## Actual result — not an all-green pass

**342 tests passed, 6 failed, 0 skipped, 0 retries.** The single consolidated runner completed with **23 checks passed and 3 checks failed**. It did not stop at the first failing suite. No test suite has been rerun.

The report describes the **frozen pre-correction source**, not the final corrected source. Ten source files were subsequently changed; the backend and frontend were **not rebuilt or retested**. Four additional recovery regression tests were written but not executed. See [post-pass corrections](desktop-5-9-corrections.md).

| Suite | Passed | Failed | Evidence |
|---|---:|---:|---|
| Rust workspace tests | 133 | 0 | [Log](../artifacts/desktop-5-9/rust-tests.txt) |
| JavaScript unit tests | 37 | 0 | [Log](../artifacts/desktop-5-9/javascript-tests.txt) |
| Agent lifecycle | 9 | 1 | [Log](../artifacts/desktop-5-9/lifecycle.txt) |
| Peer/pairing security | 30 | 0 | [Log](../artifacts/desktop-5-9/peer-security.txt) |
| File security | 32 | 0 | [Log](../artifacts/desktop-5-9/file-security.txt) |
| Transfer security | 24 | 0 | [Log](../artifacts/desktop-5-9/transfer-security.txt) |
| Real-process content/offline | 13 | 3 | [Log](../artifacts/desktop-5-9/content-offline.txt) |
| Synthetic release tools | 10 | 0 | [Log](../artifacts/desktop-5-9/release-tool-tests.txt) |
| Browser / accessibility | 54 | 2 | [Log](../artifacts/desktop-5-9/browser-tests.txt) |
| **Total** | **342** | **6** | [Machine-readable summary](../artifacts/desktop-5-9/validation-summary.json) |

## What failed

1. Lifecycle migration-count assertion expected **4**; the actual database contained **6** successfully applied migrations.
2. The chat/attachment permission test expected `ACCESS_DENIED`; the actual authorization layer returned `PERMISSION_DENIED`.
3. The independent peer consent test had the same error-code expectation mismatch. Subsequent assertions in that test were not reached.
4. The malformed-owner-payload test used a normal-request helper that required a matching request ID. Strict parse rejection intentionally returns an empty ID with `INVALID_REQUEST`.
5. A direct browser security probe still targeted port **4320**, while this isolated run used **4322**. It failed to connect; this was not a successful authorization probe.
6. Real incoming chat text and attachment status had **4.34:1** contrast against the bubble background, below the automated **4.5:1** threshold. This was a genuine UI accessibility defect, not merely an assertion mismatch.

Source corrections do **not** turn these failures into passes. Both logs and original browser results remain unchanged.

## Positive coverage observed in this pass

- Real two-agent Olm message delivery, stable-ID replay, inbound-only pull and restart-retained history.
- Protected drafts; metadata/WAL/log/support/browser-persistence sentinel checks within the supplied fixtures; individual content-key loss locked the store.
- Attachment delivery before explicit download, authenticated ciphertext download, explicit export and resume from the original immutable snapshot.
- Actual pin publication, refresh to a separate version, preservation of local edits, source deletion, unpin/cleanup preservation and rejection of read-only pin permissions.
- Existing peer/file/transfer process regression; real browser download/receive approval, consent controls, drafts, attachment export, pins and compact layouts.

These are bounded development-host observations, not proof of every crash window, namespace race, possible malicious peer or native OS behavior.

## Other checks that passed on the frozen source

Rust/JavaScript formatting; development agent build; strict Linux Clippy; **50-command** static native/bridge/capability parity; frontend production build; Windows GNU Clippy and executable link; default-feature agent build and development-feature boundary; restoration of the development build; npm audit with **0 reported vulnerabilities**; source-lockfile/license inventory; narrow Linux resource observation; and a negative check that missing release evidence blocks release.

The runner also recorded that its source remained unchanged during the pass and that **16 historical Phase 4 evidence files** remained unchanged. That source-integrity result is time-bound: the later corrections are separately recorded, not hidden.

## Preparation, evidence and limitations

Compiler preparation preceded the behavioral pass: one SQLx test expression needed a borrow, and adjacent Rust control blocks needed formatting to satisfy strict Clippy. Those initial diagnostics and subsequent successful Linux/GNU compiler-only checks are preserved in [preparation summary](../artifacts/desktop-5-9/preparation/preparation-summary.json). Preparation did not execute test suites.

- [Pass marker](../artifacts/desktop-5-9/pass-started.json)
- [Pre-pass source hashes](../artifacts/desktop-5-9/tested-source-hashes.json)
- [Browser results](../artifacts/desktop-5-9/browser-results.json)
- [Evidence index and screenshots](../artifacts/desktop-5-9/README.md)
- [Resources and dependency inventory](desktop-5-9-resources.md)

Failure trace paths in browser logs refer to ephemeral Playwright cache files; those traces are not part of the source package. The logs, JSON results and explicitly saved screenshots are retained.

Extended fuzzing, RustSec/advisory review, actual Windows/MSVC/Tauri/DPAPI/ACL/share-mode/power-loss/LAN/screen-reader qualification, independent review, signing and pilot approval remain open. CI and fuzz workflows were supplied, not remotely executed. Android remains deferred.

The current runner refuses to overwrite this pass. Any future revalidation needs a separately authorized evidence record; do not delete its marker or edit its results. The historical [Phase 4 failed pass](phase-4-validation.md) remains separate and unchanged.
