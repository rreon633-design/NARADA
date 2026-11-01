# Attachments — end-to-end protocol and communication

[Crypto](attachment-crypto.md) · [Workflow](phase-6-7-workflow.md) · [Recovery](attachments-recovery.md) · [Validation](phase-6-7-validation.md)

## Phone → desktop

```text
User confirms import → temporary read-only SAF document
  → bounded RAM → fresh age encryption → immutable encrypted staging
  → protected receipt + typed Olm message commit together
  → explicit Chat sync commits the manifest on desktop
  → explicit attachment send: capabilities → begin/status → missing PUT chunks → finish
  → remote full-decrypt/verify receipt (independent of the phone's local receipt)
```

The outbox ratchet and exact ciphertext are durable before send. The receiving ratchet/inbox/receipt is durable before chat ACK. An uncommitted/unknown-outcome message cannot authorize a blob upload. Local observers update projections after chat/transfer changes; they do not start network work.

### Authorized upload extension

| Item | Version-1 contract |
|---|---|
| Capability | `attachments.upload.v1`, alongside `attachments.age.v1` and Chat + Attachment grants |
| Control | `POST /api/v1/attachment-upload`, version 1; `capabilities`, `begin`, `status`, `finish` |
| Chunk | `PUT /api/v1/attachment-upload/chunks/{message}/{blob}/{index}` |
| Crypto | `age.x25519.v1` |
| Bounds | 262,144-byte chunks; plaintext 8,388,608; ciphertext 8,454,144; ≤33 chunks |
| Request headers | Explicit upload version 1 and ciphertext-chunk SHA-256; opaque octet-stream bytes |
| Status binding | Version, message UUID, blob UUID, ciphertext hash, state and exact ordered missing indices |
| Chunk receipt | Version, message UUID, blob UUID, index, stored flag |

The desktop checks mTLS identity, current Chat + Attachment authority and the already committed incoming message/descriptor. It uses durable `receiving_push` staging, restart-paused recovery and rehash-on-begin. Finish, including retries after a lost reply, performs complete age/plaintext verification. Unknown versions/capabilities or changed bindings fail closed; there is no protocol downgrade.

No age identity, filename or plaintext hash goes into ordinary upload control metadata. The transport's binary/error framing is bounded: redirects, duplicate headers, compressed bodies, inconsistent lengths/status/envelopes and malformed identities are rejected. Error details are reduced to approved codes.

## Desktop → phone

The desktop sends an Olm-protected typed descriptor through the existing chat protocol. The phone explicitly retains the message's attachment and fetches `GET /api/v1/blobs/chunks/{blob}/{index}`. It never accepts a desktop-initiated socket. Exact lengths/chunk hashes and full age/plaintext verification precede a protected availability receipt. Receipt, remote storage status and export status remain distinct.

## Protected local state

`AttachmentRecord` binds local ID, message ID, peer, original pairing, direction, immutable secret, checkpoints, receipt and export records. States include PICKER_PENDING, IMPORTING, STAGING, PAUSED, RUNNING, VERIFIED, CANCELLED and CORRUPT. Restart does not auto-import, send, re-encrypt or publish.

Encrypted ciphertext fragments use a separate `attachments-v1` directory and AAD-bound encrypted chunk adapter. The storage-only `DownloadRecord` adapter never enters ordinary Transfers requests/UI/metadata. The typed message and attachment continuation checks reject substituted identities/descriptors and preserve the original pairing.

Four attachment jobs reserve at most 33,816,576 ciphertext bytes. Downloads plus attachments share 128 MiB; ordinary downloads retain their four slots/64 MiB-file limit. A full quota is an explicit error, never permission to evict a pin, message or unknown outcome.

## Connection ownership and desktop preservation

All calls pass through the connection coordinator's foreground content lease: health is canceled/joined, transport-bound authority is checked before and after health, and pairing/epoch/foreground changes discard results. Protected-storage failure disables networking. Shared content admission is 300 ms; the desktop's 12 TLS-starts/second limit is unchanged.

The Rust workspace is 0.7.1 for this authorized extension. Original pre-extension bytes for seven changed live paths and all 2,370 frozen historical inputs remain available. The current 101-file desktop runtime/build fingerprint and exact actual-peer binary hash are recorded before/after the fresh Android interop campaign. Historical desktop validation was **not** rerun or renamed; Windows/native installer qualification remains open.
