# Phase 3 — approved files, desktop development slice

[README](../README.md) · [Live plan](../plan.md) · [File protocol](phase-3-protocol.md) · [Walkthrough](files-and-recovery.md) · [Evidence](../artifacts/phase-3/README.md)

**Version 0.3.0 · 30 October 2026.** Desktop implementation and development-host testing, not a release-qualified Windows application. Android, transfer/upload/download, chat, attachments and sync remain deferred. The original phase exit is still open: native Windows picker/filesystem execution and independent safety review have not been performed.

## Implemented task mapping

| Backlog | Actual implementation | Remaining gate |
|---|---|---|
| NAR-025 | Explicit root registry, exclusions, overlap/protected-path rejection, removal that withdraws grants. Native Windows folder-picker source and a labelled agent-host development form. | Execute native picker, cancellation, protected-path and ACL cases on Windows. |
| NAR-026 | Opaque object IDs, component/name policy, pinned filesystem identities, handle-based reads, link/reparse and hardlink rejection, no-overwrite moves. | Windows race/alias/share-mode qualification and independent containment review; Linux adapter is development-only. |
| NAR-027 | Migration 0004, persistent metadata index, 16-item pages, watcher hints, periodic reconciliation and explicit rescan. | Large/slow-device and real NTFS watcher/performance qualification. |
| NAR-028 | Not implemented. | Android remains outside the explicitly selected desktop scope. |
| NAR-029 | Actual tree/list/grid/inspector, breadcrumbs, selection, search/sort, permission-aware actions and keyboard/pointer pane resizing. | Native WebView2, assistive technology and high-contrast execution. |
| NAR-030 | Bounded PNG/JPEG decoding and metadata-stripped JPEG previews; unsupported/active formats fail closed. Client checks actual baseline JPEG frame dimensions. | Decoder fuzzing, independent review and whole-process resource measurement. |
| NAR-031 | Current grants, live object/version validation, root exclusions and actor/root/query/epoch-bound cursors. | Physical LAN and adversarial native concurrency qualification. |
| NAR-032 | Versioned rename, managed trash and restore; actor-bound durable receipts, confirmations, recovery and retention. | **Files and empty directories only.** Recursive/nonempty directory operations, purge and owner recovery-resolution UI are not implemented. |

## Boundaries and architecture

`desktop-ui → typed owner RPC → narada-connection → narada-files → SQLite + OS filesystem handles`

For a peer: `saved endpoint + pinned TLS 1.3/mTLS identity → current trust/session epoch → current root/capability grant → file resolver`. The browser never calls a peer address directly. Owner registration, policy changes and local absolute paths are not peer commands. No broad filesystem or shell permission was added to Tauri.

The Windows shell has **26 explicit commands**: 25 agent wrappers and native-only `choose_root`. Static command parity is evidence of source consistency, not a native build or capability-runtime proof. `rfd` is Windows-only. The Windows GNU checks cover the agent/files backend, not the Tauri shell.

## Root and index model

- No root is selected or shared automatically. Registering a folder is separate from granting a device access.
- At most eight active, non-overlapping roots. Whole disks, the agent profile/ancestors, fixed sensitive/system paths, unsafe components and unsupported filesystems are refused.
- Hidden, excluded, linked, ambiguous case/Unicode names and unsafe objects do not enter the visible catalog. Archive files are inert: they are never extracted.
- Exclusions are relative subtree prefixes, not glob patterns. A prefix matches component boundaries and descendants.
- Enumeration is limited to 5,000 visited entries and a three-second scan budget. Exceeding the limit blocks the root index rather than publishing an unmarked partial result.
- Watcher events are hints, not authority. Access/read events are ignored. Reconciliation runs on a nominal 30-second cadence; a two-second loop processes hints. Slow work can extend these timings.
- `NARADA_FILE_WATCHER=poll` disables native hints for the missed-event/fallback test. Queries revalidate objects even between scans.
- Sixteen-entry pages have bounded cursors. Tree branches reflect fetched directory pages; wide directories use the complete paginated table rather than an invented full tree.
- The index, root paths, names and operation journal are **sensitive, unencrypted local metadata**. Original content and generated preview blobs are not stored in SQLite. Logs and diagnostic exports omit paths/names.

## Filesystem adapters: what is and is not established

**Windows source:** local fixed NTFS, no UNC/device/reparse traversal, pinned ancestor handles without delete sharing, file identities/change stamps, single-link regular files, destination-directory handles and no-replace rename. Canonical OS handle paths are rechecked against protected locations. Hidden/system ancestors are refused. The source cross-compiles and is subject to the [native checklist](windows-validation.md).

**Linux development:** `openat2` with BENEATH/NO_SYMLINKS/NO_MAGICLINKS/NO_XDEV beneath a selected root, file-descriptor enumeration, a local-filesystem allowlist and `renameat2` NOREPLACE. Roots cannot be group/world writable. Pre/post identity checks do **not** make namespace or content changes atomic against a malicious same-user writer. Linux is a disposable-folder development adapter, not a qualified filesystem security boundary.

On neither platform does the passing test suite establish exhaustive race freedom, power-loss durability, antivirus/cloud-provider behavior or atomic compare-and-rename against every external writer. Use disposable data until those gates pass.

## Mutations and recovery

Each request carries an operation UUID, object UUID and expected opaque version. An intent is committed before the filesystem move. The resulting metadata, retained-item record and receipt are transactionally recorded afterward. No destination overwrite or cross-filesystem copy/delete fallback exists.

- The receipt namespace includes the authenticated actor and request-body hash. Replays use current capabilities and exclusions; changing the body under a reused ID is rejected.
- A detached worker owns its concurrency permit and policy read lease. A disconnected/cancelled waiter does not abandon the operation or let a policy update overtake it. An authorization writer waits for already-admitted work; changes are not retroactive deletion of previously delivered bytes.
- Startup inspects prepared intents (up to 64). A verified moved object can be finalized; an intact unmoved source can be marked not applied. Ambiguous state is preserved and blocks further root mutations.
- Unregistering keeps files, trash and history; it removes all grants. Re-registering the same filesystem identity restores the root ID, **not device grants**.
- Managed trash retains at least 14 days, has a 200-item / 100 MiB quota, and is never automatically purged in this version. Missing/changed retained objects remain recorded and consume quota; the listing reports unavailable items.
- A 5,000-operation history cap stops new mutations rather than forgetting idempotency receipts.

See [recovery rules](files-and-recovery.md). There is no “reset broken data” or automatic permanent-delete action.

## Scope limits that matter

Remote file sources currently require an active **outbound** peer with a saved, pinned endpoint. The inviter has no automatically discovered reverse endpoint for an inbound peer. Reverse source browsing is not inferred from a socket or an address advertisement. Local grants remain directional and separately owned.

Preview is not download. `read` authorizes image preview in this slice; `create` is used only with `trash` to restore. There are no upload, recursive move, arbitrary open, external viewer, archive extraction or permanent-delete routes. Health accepts the reviewed Phase 2 operation list and the Phase 3 list with `files.v1`; unknown file versions are not silently downgraded.

## Evidence and handoff

The [Phase 3 evidence directory](../artifacts/phase-3/README.md) distinguishes final passing runs from earlier failed integration checks. Real independent TLS clients and separate Rust agents are used, not mocked peers. Recovery fault injection is explicitly identified and is not exhaustive power-cut testing. Historical Phase 1/2 evidence and all five supplied references are preserved.

Continue to Phase 4 only as a development task. Do not market this source handoff as a production-safe filesystem implementation or a signed Windows installer.
