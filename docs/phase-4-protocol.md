# Phase 4 — transfer protocol and publication boundaries

> **Unverified source contract:** the single pass did not build the backend. Targeted corrections were not rebuilt/retested. See [validation results](phase-4-validation.md).

## Owner commands

`list_transfers`, `start_transfer`, `transfer_control`, `create_transfer_offer`, `poll_transfer_offers`, `accept_transfer_offer`, `cleanup_transfers` extend the existing scoped IPC/Tauri surface. Start uses a caller-generated UUID retained for retry, a saved peer identity, direction, an opaque source root/object/version, and a destination root/directory ID/filename. It accepts no temporary path, ACL, overwrite flag or automatic-open option.

## Peer controls and bytes

`POST /api/v1/transfers` is a strict JSON envelope: `hello`, transfer `version: 1`, tagged `action`. Actions: prepare source, page manifest, propose receive, append/replay manifest page, status, commit, cancel, source-finish acknowledgement, and list actor-scoped pull offers. Unknown fields/versions are refused. Health advertises the exact reviewed list `health`, `permissions`, `files.v1`, `transfers.v1`; older reviewed lists remain usable for their own operations, but starting transfers requires `transfers.v1` explicitly. Unknown advertised operation combinations are rejected.

`GET|PUT /api/v1/transfers/chunks/{transfer_uuid}/{canonical_index}` carries **one binary chunk**, not base64 or a whole file in JSON. No query, redirect, compression, transfer encoding, path, index alias or oversized body is accepted. Version, exact length and SHA-256 are explicit headers; the body is checked again against the saved immutable manifest. GET has a zero-length body. PUT acknowledgement is empty binary data only **after** file flush and a durable checkpoint transaction. Both routes share the existing 12-request/session/second budget; workers pace requests and reuse pinned connections. Two inbound data slots do not consume all 24 session slots.

TLS 1.3 exact identity pins, private-key proof and current session revision/epoch remain required. The file policy read lease covers admitted source/receive IO; grant/exclusion changes use the existing write barrier. Revocation cannot erase bytes already received or published. Peer error text is sanitized, not displayed verbatim.

## Immutable content contract

Before copying, source identity, metadata version and bounds are checked. The original is copied into profile-private managed storage and hashed; source identity/version is rechecked afterward. A metadata version token is **not** a content digest. Full-file SHA-256, ordered per-chunk hashes, original source version, filename, size and dimensions form a deterministic manifest ID. Hashing is over compact UTF-8 JSON of this ordered array:

```text
[version, source_version, name, decimal_size, chunk_size, chunk_count, full_sha256, ordered_chunk_hashes]
```

The same transfer ID is bound to the same actor and initial parameters. Immutable snapshots can finish an earlier version if the original content changes after snapshotting, but the original namespace identity, current root grant and exclusions must still authorize further serving. A failed or changed-source preparation is not silently replaced with a different snapshot on retry.

## Checkpoint and commit order

1. Validate exact index/length/digest against the complete manifest.
2. Write the destination-filesystem private stage, then flush.
3. Commit its bitmap and verified-byte count in SQLite WAL/FULL; only then ACK.
4. Restart rehashes acknowledged chunks. Uncertain bits become missing. Resume keeps the same manifest and requests missing/uncertain chunks only.
5. Require all chunks; independently read and verify full length, full hash and ordered hashes.
6. Persist **COMMIT_INTENT** with staging identity and the saved destination identity/name.
7. Under current authorization and policy, publish by same-filesystem **no-replace** rename, then flush file/publication metadata as supported by the adapter.
8. Durably record a typed receiver receipt. Only that matching receipt can make a local job `completed`.

Linux directory fsync and Windows file flush/handle-rename source are implemented; native Windows metadata ordering and actual power-loss behavior are **not qualified**. A remote receiver's receipt is an authenticated peer assertion, not remote-storage attestation.

A dropped ACK is not delivery proof. Retry checks the existing receiver record. Duplicate commit returns the stored receipt. Cancellation never deletes a published user file. A local download can show **published; source acknowledgement pending**: publication is known locally, while source-retention cleanup acknowledgement is separate.
