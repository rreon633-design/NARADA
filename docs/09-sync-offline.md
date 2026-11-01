# NARADA — Offline and synchronization mechanism

> **Current desktop source 0.7.0:** see [phases 5–9 scope and readiness](desktop-5-9-status.md), [current validation](desktop-5-9-validation.md) and [cryptography decision](crypto-content-decision.md). Original cross-platform/native/security acceptance checkboxes remain open where evidence is missing. Android is still deferred.

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Define “sync” precisely

NARADA 1.0 has three distinct operations:

1. **Metadata sync:** Keep authorized file listings up to date.
2. **Pin refresh:** Copy changed selected laptop content to Android, one way.
3. **Explicit upload:** A user-requested phone-to-laptop transfer with collision/version checks.

These do not equal unrestricted bidirectional folder synchronization. Exporting a file to another Android app creates an independent copy unless a later explicit sync relationship is established.

## Change detection and journal

```text
filesystem hint → debounce/coalesce → inspect actual state
                → compare indexed metadata/version
                → transactionally update index + normalized change journal
                → notify connected peers
```

- Watcher overflow, restart and missed events trigger reconciliation.
- Reconcile at startup, after known gaps, on reconnect as needed and periodically within an I/O budget.
- Do not hash every file after every watcher event. Use hints to select candidates, then validate bytes when a content version matters.
- Track root identity, permission scope and index epoch; replaced/remounted roots may invalidate old cursors.
- Rename inference from filesystem IDs is a best-effort optimization. IDs can be reused or change across copies, restores and volumes.

## Cursor and snapshot contract

A cursor logically binds `root_id`, index epoch, journal sequence and permission-scope revision. The wire representation can be opaque.

1. Client requests changes after its last applied cursor.
2. Server checks cursor validity and current authorization.
3. Server returns a bounded ordered page and a next cursor.
4. Client transactionally applies metadata and records any required content jobs.
5. Client acknowledges the metadata cursor only after that transaction.
6. Pin content completion is tracked separately; a metadata cursor does not imply every file is downloaded.
7. On expired journal/epoch/grant changes, return `CURSOR_EXPIRED` or scope-reset status and require a snapshot.

**Consistent snapshot:** Capture a stable indexed view at a journal high-water mark `S`, page that same view with a bounded snapshot lifetime, then replay changes after `S`. Do not page a continuously changing table and call it a consistent snapshot. Use a tested snapshot/materialization strategy that does not hold a read transaction forever or grow the WAL without bound.

The snapshot describes the index at `S`, not a magical point-in-time copy of every source file. Content requests still validate the referenced source version.

## Pin state machine

```text
NOT_PINNED → REQUESTED → DOWNLOADING → VERIFYING → AVAILABLE
AVAILABLE  → STALE → REFRESHING → AVAILABLE
AVAILABLE  → SOURCE_MISSING / ACCESS_REVOKED
any state  → UNPIN_REQUESTED → NOT_PINNED
```

A failed refresh leaves the previous verified version usable. Store pins in persistent app-private files, not an OS-evictable cache directory. App uninstall, clearing app data, hardware loss or explicit cleanup can still remove them; make that visible in help text.

## Conflict rules

Use logical versions/content digests and a shared base, not “newest timestamp wins.”

| Comparison | Decision |
|---|---|
| Local and remote match base | No change. |
| Remote changed; local managed copy still matches base | Safe one-way refresh. |
| Local editable/exported copy changed; remote unchanged | Do not silently discard local work; explicit upload/export workflow. |
| Both changed differently | Preserve both and create a conflict record. |
| Delete versus local edit | Preserve edited bytes and explain the source deletion. |
| Same normalized name but different content | Keep separate candidates; ask for a destination/resolution. |

Future automatic two-way sync needs base/local/remote versions, rename/delete semantics, per-entry outcomes and a real conflict-resolution UI before it can be promoted to P0.

## Deletions, retention and restore

- Remote trash is a deliberate authorized operation, not the same as pin cleanup.
- Use tombstones in metadata to communicate disappearance and prevent accidental resurrection.
- Default 1.0 policy: source deletion marks a pin stale/source-missing and asks before local removal.
- Tombstone/journal compaction invalidates sufficiently old cursors; snapshot reconciliation is the safe fallback.
- NARADA-managed trash is not guaranteed to be Windows Recycle Bin integration. Test the actual retention/restore implementation on supported roots.
- Sync is not backup. Replicated mistakes and ransomware-modified files can propagate under permissive policies; retain independent backups.
