# Phase 3 validation and remaining gates

[Implementation](phase-3-implementation.md) · [Observed results](../artifacts/phase-3/README.md) · [Windows checklist](windows-validation.md)

## Executable coverage

- Rust protocol/policy/filesystem tests: strict file envelopes, hostile names, root overlap, links, object replacement, preview scope, version/collision checks, trash/restore, durable receipt replay and cancellation retaining its policy lease.
- JavaScript: filename/exclusion/role/layout helpers and actual JPEG envelope/frame validation, plus the existing QR/format helpers.
- Separate-process files suite: two real Rust agents, an independent OpenSSL-backed TLS reference peer, current grants, exclusions, hostile IDs/paths, filesystem changes, root limits, paging, image limits, quotas, restart receipts and journal recovery.
- Real watcher create/rename/delete and a **30-second reconciliation test with native hints disabled**.
- Existing lifecycle/pairing regression suites, including real 120-second invitation expiry, 60-second TLS proof rollover and bounded reconnect timing.
- Browser tests against an isolated real agent: roots/forms, tree/table/inspector, image pixels, actual disk mutations, permissions, offline clearing, resizing, accessibility, and separate-reference grant/revoke flows.

The final counts, times and statuses are recorded in the [machine-readable summary](../artifacts/phase-3/validation-summary.json). Earlier failed runs are retained and labelled as historical debugging evidence, not counted as passes. Tests do not substitute for independent review.

## Qualification matrix

| Area | Evidence class | Still open |
|---|---|---|
| Linux development agent | Build, strict lint, Rust/process/browser execution | Malicious same-user namespace/write race qualification; not a supported production filesystem adapter. |
| Windows agent/files source | GNU target type/lint/link checks | Actual NTFS, Windows share modes, reparse, ACL, flush, watcher and concurrency execution. |
| Native Windows picker/shell | Typed command source and 26-command parity | MSVC Tauri build, picker execution/cancellation, WebView2, tray/ACL/CSP/install tests. |
| Real peers | Independent identities, loopback TLS/mTLS, current-root grants and revoke | Physical Windows Private/Public LANs, routing, firewall, router/hotspot and no-internet operation. |
| Image safety | Bounded source/decoder/output and malformed/frame tests | Decoder fuzzing, sandboxing review, peak process memory and adversarial CPU budgets. |
| Recovery | Real process death/restart, replay, test-only SQLite state fault injection | Exhaustive power-loss/disk-full ordering, hardware flush guarantees, corrupt retained content, owner resolution tool. |
| UI | Browser automation, selected accessibility rules, manual screenshots | Screen-reader/high-contrast/native keyboard/WebView testing; no blanket WCAG certification. |
| CI | Updated Linux + Windows definition | Remote CI execution not observed in this session. |
| Android | None | Explicitly deferred. |

## Reproduce

Use [desktop-development.md](desktop-development.md). Run the full slow Python suites, not just their default fast subsets, when comparing counts. Tests use disposable profiles/folders; do not point them at user data. Browser tests have a dedicated port/profile and do not operate on the ordinary preview profile.

The source ZIP excludes profiles, databases, transport identities, wrapping/private keys, generated dependencies/binaries/caches and browser traces. The manifest verifies packaged source bytes, not a reproducible native binary or a release signature. Original migration files and supplied references are checked against the historical archive when packaging.
