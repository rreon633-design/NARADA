# NARADA — File-transfer mechanism

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Broad design baseline. Bounded desktop source exists, but its backend build/validation is incomplete; see [implemented protocol](phase-4-protocol.md) and [one-pass validation](phase-4-validation.md). Large-file, native durability/security and Android SAF portions below remain unqualified/deferred.

## Design invariants

1. Content is tied to an immutable manifest and source version.
2. Chunk length/index/hash are validated before durable acceptance.
3. A checkpoint never claims bytes are durable before the relevant storage flush/transaction succeeds.
4. Final hash and length must match before publication.
5. Publication is idempotent and conflict-safe.
6. File I/O and database commits are recovered together through an explicit protocol, not assumed atomic.
7. Working buffers and queues are bounded even for a very large file or manifest.

## Transfer state machine

```text
CREATED → PREPARING → QUEUED → TRANSFERRING → VERIFYING → COMMITTING → COMPLETED
                             ↕ PAUSED
                             → RETRY_WAIT → TRANSFERRING
any nonterminal state        → CANCEL_REQUESTED → CANCELLED
recoverable resource issue   → BLOCKED → QUEUED
permanent invalidity         → FAILED
expired resumable state      → EXPIRED
```

`PREPARING` includes source snapshot/hash work. `COMPLETED` refers to a verified committed destination in the transfer's storage domain. External export has its own state machine. Cancellation after a completed commit does not delete the user's completed file.

## Source consistency and manifest creation

Recommended correctness-first 1.0 mode: create or reuse an immutable local snapshot under a quota, then hash and transfer that snapshot.

- On Windows, obtain a reviewed stable read handle that prevents incompatible writes/deletion during snapshot creation, or fail visibly when the source is busy. A naïve copy of a file being modified is not a stable snapshot.
- Release the source handle after the private snapshot is complete. Do not lock the user's document for an entire multi-day interrupted transfer.
- On Android, stage SAF content when seekability, stable length, persistence or mutation behavior cannot be guaranteed.
- For an unknown-length stream, enforce a growing byte quota and cancel cleanly before exhausting storage.
- A later optimization can stream from a proven stable source without copying, but must retain the same consistency/resume guarantees.
- Source metadata such as path, file ID, size and modification time is useful for detecting change, but is not by itself cryptographic proof that bytes are unchanged.

The immutable manifest binds logical file size, chunk size/count, chunk digests, full-file digest, source-version identity and format version. Paginate large digest lists. A zero-byte file is a valid explicit case with zero data chunks and the correct empty-file digest.

Snapshot preparation costs disk space and time. Show that cost instead of presenting preparation as a hung 0% transfer.

## Chunk receiving and checkpoints

```text
for each requested missing chunk:
    authenticate peer and validate current transfer lease/grants
    validate manifest ID, chunk index, offset and expected length
    stream into a bounded buffer/staging region
    verify digest against immutable manifest
    write bytes to receiver staging storage
    flush the checkpoint's data as required by the durability policy
    commit the verified/durable chunk bitmap or checkpoint transaction
    acknowledge durable progress
```

- Validate arithmetic for overflow and out-of-range writes.
- A duplicate accepted chunk is a no-op if its digest matches.
- Batch flushes are allowed, but only the durable batch is acknowledged as resumable after crash.
- A receiver may revalidate ambiguous chunks on restart; re-downloading a bounded uncertain tail is preferable to false completion.
- For a non-seekable destination, do not pretend random-access resume works. Use private seekable staging and a later export step.
- If a source snapshot or manifest changed, start a new logical transfer rather than mixing versions.

## Commit-intent protocol

A filesystem rename and SQLite transaction cannot be committed atomically as one operation. Use this recovery pattern:

1. Finish chunk reception; verify complete size and hash.
2. Flush staging content and record a durable `COMMIT_INTENT` with transfer ID, staging object, destination policy, expected destination version and content digest.
3. Revalidate peer grant, root identity, free space, destination and cancellation state under the operation's commit lock.
4. Publish with supported same-filesystem atomic replacement/rename semantics. Keep-both uses a reserved unique destination; do not overwrite by default.
5. Flush relevant filesystem metadata where the platform supports and requires it; qualify power-loss behavior rather than assuming it.
6. Transactionally store the committed file mapping, transfer result, idempotency result and normalized index/journal update where these share the same metadata store.
7. Emit completion only after the durable commit record.
8. On startup, reconcile every intent: inspect staging/final objects, identity and digest; finalize a proven publish, retry a safe pending publish, or stop for manual resolution. Never blindly overwrite an unrelated file found at the destination.

External edits can occur outside NARADA's database. Watchers and reconciliation must reconcile those separately; no DB transaction can make the entire external filesystem transactional.

## Collision and edit policy

| Situation | Default action |
|---|---|
| New destination does not exist | Publish new file. |
| Destination exists, no explicit replace approval | Keep both with a safe unique name, or ask the user. |
| Replace approved and expected version still matches | Publish with retained previous version where supported/policy requires. |
| Destination changed after approval | Return conflict; preserve both candidates. |
| Filename invalid on destination | Propose a safe name and keep original display metadata; require user awareness. |
| Destination locked by another application | Retry within a bounded policy; otherwise show “file in use.” |
| Device revoked during receive/verify | Abort before new publication; clean staging according to policy. |

## Android SAF export semantics

SAF providers differ in supported operations and URI behavior; provider flags must be checked. The platform documentation describes capability and persisted-permission handling, not a universal atomic-replace guarantee. See [R3](27-references.md).

**Safe baseline:** complete and verify a private copy → create a new provider document → stream export → close and check the result → verify by re-reading when the provider permits → report the actual guarantee.

- Do not overwrite the only good provider copy without a qualified safe strategy.
- If export fails, keep the private verified copy and identify any partial provider document for explicit cleanup.
- Where the provider cannot support integrity re-read or durable atomic publication, state the limitation. Do not label a non-verifiable provider operation as fully verified.
- Providers may change display names or return a different document URI; persist the returned identity.
- Pause/resume of private transfer and retry of provider export are different operations.

## Cleanup and quotas

Each staging object has an owner, reference count, job state and expiry. A sweeper may remove only unreferenced, expired objects that are not under an active lease or commit intent.

Never delete user originals, active pins, retained conflicts or pending-message blobs during ordinary cache cleanup. Storage reservation is checked before preparation and periodically during streaming; actual disk-full errors still need recovery.
