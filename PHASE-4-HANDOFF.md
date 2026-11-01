# NARADA — desktop Phase 4 source handoff

**Version 0.4.0 · 30 October 2026 · Validation incomplete**

## Code supplied

- **Rust:** transfer DTOs, SQLite migration 0005, immutable source snapshots, SHA-256 manifests/chunks, durable checkpoints, receive approval, no-overwrite publication/recovery, bounded workers/quotas, queue controls and explicit managed cleanup.
- **JavaScript/CSS:** reference-aligned Transfers workspace, Files download/send/offer actions, real backend-driven progress, incoming approval, pause/cancel/retry, details and storage cleanup. No simulated transfer progress or automatic archive opening.
- **Native source:** seven new commands and their typed wrappers/capabilities; 33-entry static bridge parity passed.
- **Coverage/documentation:** new Rust, JavaScript, separate-agent and browser test source; focused protocol, implementation, workflow/recovery and validation Markdown files. Supplied desktop/mobile design references remain preserved. Android is not implemented.

This is a bounded development implementation: **64 MiB/file, 256 KiB/chunk, 128 MiB source and receive reservations per profile**. Described safety behaviors still need backend execution/qualification.

## Exactly one validation pass

| Result | Outcome |
|---|---|
| JavaScript unit tests | **27 passed** |
| Production UI build | **Passed** |
| 33-command static parity | **Passed** |
| npm audit | **0 reported vulnerabilities** |
| Rust format check | **Passed on pass-start source** |
| Agent compilation | **Failed: 5 private-method visibility errors** |
| UI/test format check | **Failed: one browser spec needed formatting** |
| Rust/process/browser/Windows/default-feature suites | **Not run because the backend prerequisite failed** |

The five visibility declarations and flagged formatting were corrected afterward, **without rebuilding or retesting**. The corrected backend is therefore unverified; more issues may remain. Original failure evidence is retained. Both live previews remain stopped; no stale 0.3 agent is presented as Phase 4.

## Read next

1. [Full results and next gate](docs/phase-4-validation.md)
2. [Implementation map and limits](docs/phase-4-implementation.md)
3. [Transfer workflow and recovery](docs/transfers-and-recovery.md)
4. [Actual transfer protocol source contract](docs/phase-4-protocol.md)
5. [Phase-wise tracker](plan.md)
6. [Unverified correction hashes](artifacts/phase-4/post-pass-corrections.json)

A separately authorized, separately recorded validation of the corrected source is the next step. Do not mark Phase 4 complete, ship a Transfer Preview, or treat this archive as a release-qualified installer. Native Windows, physical LAN, realistic disk-full/power-loss and independent security gates remain open.
