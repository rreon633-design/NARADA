# NARADA — Phase 5: protected desktop chat

> Source 0.7.0 · Desktop development implementation · [Batch status](desktop-5-9-status.md) · [Cryptography decision](crypto-content-decision.md)

## Implemented flow

1. Pair using the existing certificate/private-key proof and explicit owner approval.
2. Enable **Allow encrypted chat** on both devices. This is independent of folder grants.
3. Save a logical message ID and private local history in the protected content store. Offline means locally queued, not delivered.
4. Exchange signed Olm bundles on a pinned mutual-TLS session. The signature binds the protocol, both transport device IDs, Curve25519/Ed25519 identities and the per-peer one-time key.
5. Create/advance a library-managed Olm session. Commit the encrypted envelope, advanced pickle and protected local history **together before transmission**.
6. The receiver authenticates the envelope and its inner IDs, performs ID/digest deduplication and commits history, session/account state and receipt in one protected transaction **before returning delivery**.
7. A lost ACK retransmits the same saved ciphertext. An existing ID with another peer/direction/digest is refused. A valid late receipt outranks a local cancellation.

An outbound-capable peer can send and pull. An inbound-only device does not acquire an invented callback endpoint: its outbox is offered when the other paired endpoint polls. No cloud mailbox exists.

## Protected storage

`content.db` is separate from ordinary metadata. SQLite and its WAL receive authenticated ciphertext only. The wrapped master key is in `content.key`; a sequence/ciphertext-hash guard is in `content.guard`. Account/session pickles, drafts, conversation bindings, message text, private manifests and receipts are inside the encrypted aggregate.

A transaction writes its durable guard before the database commit. An error or cancellation during commit locks the live instance. A guard/database mismatch on restart blocks content instead of rebuilding accounts or sessions. Partial missing-key/database cases were exercised in the pass. Whole-store loss exposed an additional source-review gap; the [post-pass correction](desktop-5-9-corrections.md) adds an independently retained public identity marker and existing-only opening. That correction is not rebuilt or tested.

The guard detects partial/DB-only rollback, **not matching rollback of all protected content state, its guard and the same identity**. Do not restore an older ratchet store after newer messages have been sent. There is no hardware monotonic-counter claim.

## Truthful UI and limits

- `pending`: saved locally; it may still be waiting for a secure session.
- `retry_wait`: an envelope exists; no receiver receipt is confirmed yet.
- `action_required`: automatic attempts stopped or a permission/key/storage problem needs review.
- `delivered`: the corresponding authenticated receiver receipt was committed.
- `cancelled`: future attempts stopped. This is not a remote unsend.
- Read state is local only. No read receipts, cloud delivery, or automatic OS message previews.

Limits: 4096 UTF-8 text bytes, 8500-byte combined plaintext message envelope, 128 retained messages, 10 history rows/page, 32 protected peer bindings/drafts, two sessions per peer, 4 MiB aggregate private document. Library skipped-key limits also apply. Normal automatic attempts are bounded to three within a 24-hour window; a manual retry is explicit and preserves ciphertext. Empty polls do not rewrite protected state.

The desktop Chat page uses the supplied two-column conversation reference. It contains real paired devices, real history and protected drafts—no seeded conversations. Native notification delivery remains unqualified; no notification-body feature is faked.

## Verification and boundaries

The new tests cover real-library exchange, lost receipt/restart, ID collision, authentication failure without state advancement, identity change, bounded retry, protected drafts, byte limits, cancellation, key loss, guard mismatch, ciphertext/WAL privacy and actual separate-agent delivery. See [observed validation](desktop-5-9-validation.md), not historical Phase 3/4 counts.

NAR-041–046 have desktop implementation/test source; original cross-platform/native/independent-review acceptance remains open. Android remains deferred.
