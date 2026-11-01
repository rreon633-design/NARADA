# Offline metadata, pin mechanisms and communication

[Phase 7](phase-7.md) · [Recovery](offline-recovery.md) · [Workflow](phase-6-7-workflow.md)

## Scoped metadata, not an owner index

`SyncClient` sends only the versioned snapshot/changes operations through the existing authenticated outbound content session. Read + SyncRead is required. Display names/relative paths never become filesystem authority. A phone refresh does **not** invoke owner reindex, a recursive write or a filesystem watcher.

The desktop uses a stable captured snapshot with 16 entries per page, a 2 MiB snapshot bound and 120-second cursor lifetime. Scope binds actor, grants, root revision, root identity and connection lease scope. Capacity is bounded: four retained snapshots, 2,048 opaque cursor tokens and a 2,048-event per-root journal window. Reindexing does not mutate an already captured snapshot; the subsequent ordered changes stream catches up. Expired/changed scopes require a fresh snapshot; capacity errors do not silently evict a still-live snapshot.

The phone binds work to peer, original pairing, certificate fingerprint, local epoch, remote permission revision/epoch, grants and negotiated operations. Endpoint changes advance the local epoch. Pre/post-health fences reject changed authority; stale metadata cannot authorize a new pin.

## Page transaction

```text
Explicit refresh → persist SyncWork(id, scope, exact cursor, staged entries)
  → request one page → validate mode/root/cursor/fence/order/count/paths
  → recheck current authority + exact work
  → commit next staging checkpoint
  → only the final page publishes entries + changes cursor + observation time
```

Snapshot pages retain a constant fence; a partial page must be full and cannot also advertise completion. Change event sequences are strictly increasing and its fence equals the last event. Duplicate objects/paths, invalid parent graphs, self-repeating cursors, unknown fields and unbounded data are rejected.

A refresh admits at most 16 pages and one automatic expired-cursor restart. The restart must still own the **exact admitted work**; a late expiry cannot replace newer work or recreate explicitly forgotten metadata. A late failure may annotate only its own surviving work ID, never a newer/completed cache. Partial/failed refresh leaves the prior completed view and private downloads intact.

## Pin and version transactions

1. A confirmed pin commits the pin and an exact object/version `DownloadRecord(syncRequired=true)` together, before any transfer preparation.
2. `prepare_sync` rechecks Read + SyncRead and expected version. Download writes encrypted chunks durably before checkpoints; restart pauses work.
3. A full private receipt requires decrypting/rehashing all chunks and the full file. Desktop finish ACK is independent.
4. Explicit adoption re-reads/decrypts/hashes the candidate and atomically changes references only if the exact receipt/source/manifest/state still matches.
5. Current becomes one retained previous reference. Another adoption cannot silently discard that older reference; release it explicitly first.

The verification comparison intentionally ignores **only `remoteFinished`**, since an independent desktop finish ACK may commit during verification. A changed receipt, source, manifest, chunk state or other field still prevents adoption. This ACK race and changed-receipt rejection have dedicated regressions.

## Quotas and cleanup

- Two protected caches; 128 entries each; **393,216 serialized bytes across committed/staged caches and pins**, not a per-page allowance.
- Four pins; current/candidate/one previous reference per pin. All references bind peer/pairing/root/object and an existing sync-required download; current/previous require receipts.
- Four download jobs and 128 MiB combined download + attachment reservations; maximum ordinary file 64 MiB, 256 KiB chunks. No silent eviction.
- Release/unpin drops references only, leaving private bytes in Transfers. Active/queued candidates must be stopped first. Explicit byte removal fails while any pin references the download or export uncertainty blocks removal.
- Tombstones and same-path replacement never rebind an old pin to a new object. Existing private versions remain exportable subject to independent full verification.
