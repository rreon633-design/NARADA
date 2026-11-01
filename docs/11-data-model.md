# NARADA — Data model and storage design

> **Current implementation:** desktop phases 1–3 have a development implementation; Android and native Windows/LAN/filesystem/security qualification remain open. See [Phase 3 implementation](phase-3-implementation.md), [actual file contract](phase-3-protocol.md), and [live tracker](../plan.md). The wider data model below includes future features, not claims that those features are shipped.

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Common entity rules

- Opaque stable application IDs; filesystem IDs are supporting metadata, not universal identities.
- Explicit `created_at`, `updated_at`, logical revision and state where relevant.
- Parameterized queries, foreign keys, migration versions and bounded indexes.
- Use UTC for human-facing time, logical sequence numbers for ordering and durable state-machine transitions for jobs.
- Keep large file/blob bodies outside SQLite in controlled storage.
- Distinguish metadata truth, content availability and external filesystem/provider state.

## General metadata tables

| Table | Important fields / constraint | Location |
|---|---|---|
| `devices` | Device ID, transport public key, trust status, grant epoch, last seen | Both |
| `pairing_sessions` | Invitation hash, expiry, attempt binding, state; single claim | Windows; temporary client state on Android |
| `shared_roots` | Root ID, local protected path, filesystem identity, policy, index epoch | Windows |
| `permissions` | Unique device/root grant, capabilities and revision | Windows; cached view on Android |
| `files` | Root/object ID, parent, normalized/display name, kind, metadata revision | Windows index; authorized cache on Android |
| `file_versions` | Content version, size, digest, source identity and availability | Both as needed |
| `transfer_sessions` | Transfer ID, peer, direction, manifest, state, quota, expiry | Both |
| `transfer_chunks` | Unique transfer/index; expected digest and durable checkpoint | Both |
| `commit_intents` | Transfer, staging/final identity, policy and recovery state | Both for qualified private files |
| `operation_results` | Unique peer/operation/idempotency key + request digest/result | Mutation owner |
| `change_journal` | Root/epoch/sequence, operation, object/version and tombstone | Windows |
| `sync_cursors` | Unique peer/root/scope, last applied cursor | Both |
| `snapshot_leases` | Stable snapshot reference, high-water cursor and expiry | Windows |
| `pinned_files` | Root/object, requested/current version, local object, state | Android |
| `content_jobs` | Unique desired object/version/purpose; retry and lease state | Both as needed |
| `cache_entries` | Object reference, class, size, last use and active references | Both |
| `conflicts` | Base/local/remote references and resolution state | Both as needed |
| `trash_entries` | Original location/version, retained object, expiry and restore state | Windows |
| `export_jobs` | Source private object, provider URI, state and verification level | Android |
| `audit_events` | Redacted local event type, actor reference and result | Both |
| `settings` | Validated non-secret configuration and schema version | Both |

## Protected chat tables on both endpoints

| Table | Purpose / invariant |
|---|---|
| `conversations` | Peer identity, conversation ID and local settings. |
| `messages` | Stable ID, protected display content, envelope digest, state and ordering metadata. |
| `chat_outbox` | Immutable ciphertext envelope, retry age, destination and state. |
| `chat_inbox_dedup` | Unique sender/conversation/message identity plus accepted envelope digest. |
| `delivery_receipts` | Message reference, receipt identity, accepted/read state and timestamp. |
| `encryption_sessions` | Library-controlled state with transaction-safe updates. |
| `prekey_records` | Library-specific generation/consumption state where required. |
| `attachment_secrets` | Protected content key/format parameters and message relationship. |
| `attachments` | Blob ID, transfer reference, lengths, integrity and availability. |
| `drafts` | Protected unsent content and local conversation reference. |

Do not serialize a library's private session state into an ordinary preferences file. Avoid cross-database foreign-key assumptions: mirrored UI references are rebuildable; critical crypto/message state lives together.

## Transaction and durability policy

- Use WAL where appropriate, but WAL alone is not a durability guarantee.
- Choose and test SQLite synchronization settings against the receipt/checkpoint promises; critical acknowledgments need the stronger durable policy.
- Configure busy timeouts, bounded writer queues and explicit error handling for disk full, lock contention and failed commits.
- Emit state-change events after commit, with enough identity to reload authoritative state.
- On corruption, preserve evidence and enter a repair/read-only path. Do not delete the DB and pretend to start fresh.
- Migrate from every supported prior schema. Interrupt migrations in tests.
- For encrypted stores, test page/WAL/temp/backup behavior and wrong-key failures, not merely successful open/read.

## Initial retention policy to validate

| Data | Proposed default | Safety rule |
|---|---|---|
| Pairing invitation | 120 seconds; invalidate incomplete invitations on restart | Never reusable after expiry/claim. |
| Inactive transfer snapshot/staging | 7 days after inactivity, subject to quota | Warn before expiry; expired transfer cannot claim resumability. |
| Completed mutation receipts | 30 days | Client must not blindly retry an expired unknown mutation. |
| Automatic chat retry | Up to 30 days, then action required | Preserve local message; explicit resend uses a new clearly labeled logical message. |
| Chat dedup markers | At least 90 days and at least the sender retry horizon; longer with retained history | Retention changes must preserve replay safety and protocol rules. |
| Journal/tombstones | Initial 30-day / bounded-size policy | Expired cursors force snapshot, not guessed deltas. |
| Pins | Until user unpins/clears app data or uninstalls | Never ordinary LRU eviction. |
| Unresolved conflicts | Until explicit resolution, with visible quota pressure | Do not silently delete the only surviving version. |
| Managed trash | Initial 14 days after informed delete policy | Visible expiry; restore before purge when possible. |
| Logs | Initial 7 days, maximum 20 MiB | No secrets or content. |
| Audit history | Initial 30 days with bounded count | Redacted and inspectable. |
| Message history | User-controlled retention | Explain local versus remote deletion and backup limits. |

Retention defaults are not guarantees against storage loss. Quotas must block or pause new work rather than silently evict protected data.

## Backup and recovery boundaries

- Back up metadata using supported SQLite backup methods, not an inconsistent live file copy.
- Exclude identity/session keys and sensitive DBs from ordinary OS/cloud backup unless a specifically reviewed encrypted recovery design exists.
- Do not restore old ratchet/session state and continue sending as if no rollback occurred. Stale crypto-state restoration can violate protocol assumptions.
- User-facing recovery of old content must be separated from identity/session activation. Unsupported key/session restore requires new trust/session establishment.
- Keep original shared files outside application reset/uninstall deletion paths.
- Document that pins and local-only chat are not substitutes for the user's independent backup strategy.
