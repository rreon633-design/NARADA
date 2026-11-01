# NARADA — Phase 7: scoped journals and desktop pins

> Source 0.7.0 / metadata schema 6 · [Protocol](content-offline-protocol.md) · [Validation](desktop-5-9-validation.md)

## Metadata is not file availability

Catalog replacement and the ordered upsert/deletion journal are committed in one metadata transaction. Existing watcher hints plus reconciliation remain in use. A missed hint/restart rebuilds the catalog rather than treating an incomplete event stream as authoritative.

A snapshot materializes a bounded set of entries at a journal high-water fence. Pagination never rereads a changing live catalog. Opaque cursors bind the actor, approved root identity/policy, catalog epoch and peer grant revision/session epoch. Revoke/regrant to identical capabilities cannot revive an old token. Expiry or pruned history requires a new snapshot.

Bounds: 16 entries/events per page; 2 MiB/5000-entry snapshot/cache per scope; four retained materialized snapshots; 2048 cursor tokens; 120-second cursor lifetime; 2048 journal events per root. Removed roots release their journal. Client cache rows and their next cursor commit together. A sync worker reconnects between bounded groups of pages without changing the materialized fence.

## Keep selected content

1. The remote owner grants **Browse + Read + Sync read** for an approved root.
2. In the desktop Files page, select a remote file and choose **Keep offline**.
3. Choose a registered local folder and initial filename.
4. The agent durably stores a pin intent and enqueues a real download. Pin source rows retain the Sync read requirement on all subsequent source operations.
5. Only a verified publication receipt becomes the pin's current copy.
6. A later source version creates a new transfer and new versioned filename. Previous files remain untouched, including locally edited versions.

Eight active pins, 128 MiB total active reservations/recorded versions and three versions per pin bound this development slice. Retained inactive pin metadata is capped and can be pruned after its idempotency window. Unpin never deletes published files. They become ordinary owner-managed files, not an unbounded hidden cache.

## Conflict/recovery policy

- Source deletion creates a visible tombstone state; it never removes the last local copy.
- Publication collision blocks the transfer; no overwrite fallback exists.
- A stale/expired failed intent can be explicitly cancelled and rechecked against a new metadata fence. A receipt wins over cancellation; unknown commit outcomes stay blocked.
- Completed job receipts required by pending pins are excluded from ordinary transfer history cleanup.
- Metadata failures retain the previous cache and local copies.
- Receipt byte counts mean **verified at publication**, not proof another application has never edited/deleted that file afterward.
- Manual Check for changes requests a fair, one-scope-at-a-time worker. Root metadata and actual transfer progress are displayed separately.

NAR-051–056 have this **desktop selected-file implementation** and tests. Whole-folder mirroring, automatic conflict deletion and Android pin/SAF behavior are not silently substituted. Android tasks remain deferred by the user; native execution is a separate gate.
