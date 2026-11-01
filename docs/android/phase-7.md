# Phase 7 — scoped metadata and retained offline versions

[Handoff](../../ANDROID-PHASE6-7-HANDOFF.md) · [Protocol/mechanisms](offline-protocol.md) · [Recovery](offline-recovery.md) · [Workflow](phase-6-7-workflow.md)

## Delivered development scope

- Explicit, scoped snapshot/changes client over the existing outbound authenticated connection. No owner API, reindex command or phone filesystem server.
- A separately staged partial snapshot; only a complete validated page chain publishes metadata and its changes cursor. Failure preserves the last completed view.
- Protected persistent caches and pins: two roots, 128 entries per root, 393,216 bytes across committed/staged metadata and pins, four pins.
- An exact object/version download candidate with `syncRequired`; preparation checks Read + SyncRead again. Metadata alone never creates verified availability.
- Explicit full private re-verification before adoption; current/candidate/one previous references. Refresh does not overwrite a retained version or silently adopt bytes.
- Tombstones and same-path/new-object conflicts retain old copies. Failed refresh and process restart do not erase a verified receipt.
- Reference-aware release/unpin/removal. Releasing a reference retains the download; removing bytes is a separate reviewed action and fails while referenced.
- Work-ID/scope fences reject late results/errors, including an old cursor-expiry restart racing a newer refresh or explicit cache removal.

## Task map and success criteria

| Task | Implemented / measured |
|---|---|
| AND-701: scoped snapshot/changes | `OfflineProtocol`, `SyncClient`; bounded opaque cursors, permissions and pre/post-health fences |
| AND-702: atomic metadata publication | `OfflineEngine`; partial snapshots do not replace committed metadata |
| AND-703: exact-version pins | Pin and candidate intent commit together; no networking in metadata adoption/UI observers |
| AND-704: retained refresh/conflicts | Explicit verification/adoption, tombstones, object identity, current + one previous version |
| AND-705: quota/reference cleanup | Shared 128 MiB with attachments, four download slots, protected metadata cap; no eviction |
| AND-706: lifecycle/privacy UI | `OfflineCoordinator`, `OfflineScreen`; no SavedState plaintext or false “available” label |
| AND-707: actual peer and regression | 10 actual-peer offline cases within the 65-case interop campaign; 796 combined checks |
| AND-708: device/storage/security gates | **Open**; host reopen/fault tests do not prove power-loss or native platform behavior |

## Meaning of the UI

“Refreshed” means metadata was observed under an authenticated scope, not that file bytes were fetched. “Candidate verified” means a download receipt exists; the pin still requires explicit adoption and re-verification. A retained current version may be older than the observed source, and that is intentional. A source tombstone does not mean the private copy was destroyed.

This is **one-way version-preserving refresh**, not automatic bidirectional filesystem sync, destructive mirroring, an owner index controller or remote deletion propagation. [Native/release gates](phase-6-7-device-checklist.md) remain separate from development completion.
