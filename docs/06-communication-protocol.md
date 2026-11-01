# NARADA — Communication process and protocol

> **Current implementation:** desktop phases 1–3 have a development implementation; Android and native Windows/LAN/filesystem/security qualification remain open. See [Phase 3 implementation](phase-3-implementation.md), [actual file contract](phase-3-protocol.md), and [live tracker](../plan.md). The wider protocol below includes future features, not claims that those features are shipped.

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Channels, direction and purpose

| Channel | Initiator / direction | Use |
|---|---|---|
| mDNS/DNS-SD | Local multicast discovery | Find candidate Windows endpoints. No authentication or secret exchange. |
| Temporary pairing HTTPS | Android → Windows, while locally enabled | Invitation claim and pairing status only. |
| Normal HTTPS over mTLS | Android → Windows | Capabilities, metadata, transfer control, binary chunks and reconciliation. |
| WSS over mTLS | Android opens; both sides send | Encrypted chat envelopes, receipts, transfer offers and live state events. |
| Named Pipe IPC | Desktop shell ↔ same-user agent | Owner configuration, commands and UI updates. |
| Android platform calls | Application ↔ OS/provider | Discovery, storage access, notifications and background execution. |

Android need not expose a general inbound TCP server. Windows-originated chat can use the existing Android-initiated WebSocket. When no such session exists, Windows keeps outgoing messages locally.

Ordinary file transfer is encrypted **in transit** by TLS. Chat attachment blobs are also content-encrypted. Neither claim means the user's original Windows files are encrypted at rest by NARADA.

## Addressing and discovery

Proposed examples, not registered service/port allocations:

```text
DNS-SD service: _narada._tcp.local.
Normal API:    https://<qualified-laptop-address>:45821/api/v1
Live events:   wss://<qualified-laptop-address>:45821/api/v1/events
Pairing:       temporary listener advertised only in the active invitation
```

- Check service-name/port use before public distribution; both must be configurable.
- Bind only to selected interfaces and apply a narrow Windows firewall rule. Public-profile exposure is off by default.
- Discovery TXT fields contain version/capability hints, not authoritative permissions or personal data.
- Candidate selection may try known reachable LAN addresses. Every attempt must verify the same paired identity.
- Support qualified IPv4/IPv6 behavior explicitly, including scoped link-local addresses where implemented.
- Manual IP bypasses discovery failure, **not** routing/firewall/client-isolation failure.
- A no-internet Wi-Fi network must not be rejected merely because Android has not marked it internet-validated. Bind peer traffic to the appropriate local network when required.

## Connection state machine

```text
DISCONNECTED → DISCOVERING / TRYING_KNOWN_ENDPOINT
             → TLS_AUTHENTICATING
             → VERSION_NEGOTIATING
             → ONLINE
ONLINE       → DEGRADED → RETRY_WAIT → TLS_AUTHENTICATING
any state    → REVOKED / IDENTITY_CHANGED / INCOMPATIBLE / USER_STOPPED
```

`ONLINE` requires successful authenticated application exchange, not merely Wi-Fi association or an open socket. Online status expires after missed health checks; last seen is informational.

On reconnect: authenticate → negotiate → refresh grants → recover transfer status → retry durable outbox → reconcile metadata. Content concurrency remains bounded during recovery.

## Contract conventions

- `/api/v1` identifies protocol major version; app version is a separate field.
- Negotiate supported protocol range and capabilities after authentication. Unsupported major versions fail clearly; no silent security downgrade.
- Unknown optional fields can be ignored under an explicit compatibility rule. Unknown required features, enum values and security-sensitive fields fail closed.
- IDs are opaque and never grant access by themselves.
- Use UTC timestamps for display/audit, monotonic clocks for timeouts and logical versions for causality. Do not compare wall clocks to decide conflicts.
- Encode potentially large byte counts and sequence values as decimal strings in JSON to avoid JavaScript's safe-integer limits. Define bounded numeric ranges in the schema.
- Use correlation IDs; echo a caller's request ID only after validating its length/format.
- Use pagination and maximum nesting/count/size limits everywhere, including error payloads and manifests.
- Treat API response data as untrusted in both UIs.

## Proposed route groups

All content/data routes require authenticated, active peers and operation-specific authorization. Pairing finalization is a narrow exception for an approved pending identity; it grants no content access. Local owner operations remain IPC-only. Every WebSocket frame type is separately authorized; opening a socket does not grant every capability.

| Operation | Proposed contract | Required rule |
|---|---|---|
| Claim invitation | `POST /pairing/v1/claims` on temporary listener | Valid invitation, rate limit and bounded payload. |
| Finish pairing | `POST /api/v1/pairing/finalize` | Approved pending identity with TLS proof; not a route to general access. |
| Capabilities | `GET /api/v1/capabilities` | Authenticated peer; no secrets or host paths. |
| List approved roots | `GET /api/v1/roots` | Return only grants for this peer. |
| Directory page | `GET /api/v1/roots/{root_id}/entries` | Scoped relative directory/object ID and cursor. |
| Metadata/search | `GET /api/v1/files/{file_id}`; `GET /api/v1/search` | Permission-filtered, paginated results. |
| Rename/trash | `POST /api/v1/files/{file_id}/rename`; `POST /api/v1/files/{file_id}/trash` | Capability, expected version, idempotency and confirmation intent. |
| Create transfer | `POST /api/v1/transfers` | Direction, root/source, destination policy, quota and version binding. |
| Inspect/resume | `GET /api/v1/transfers/{id}` | Owner peer only; durable checkpoint state. |
| Read manifest page | `GET /api/v1/transfers/{id}/manifest` | Bounded chunk-digest page for one immutable manifest. |
| Move file bytes | `GET` or `PUT /api/v1/transfers/{id}/chunks/{index}` | Direction, index, length, digest and lease checks. |
| Publish upload | `POST /api/v1/transfers/{id}/commit` | Windows receiver verifies all bytes, current grant and destination precondition. |
| Report download completion | `POST /api/v1/transfers/{id}/receipts` | Android reports only after its verified local commit; external SAF export is a separate result. |
| Pause/cancel | `POST /api/v1/transfers/{id}/pause`; `POST /api/v1/transfers/{id}/cancel` | Idempotent state change; no deletion of a completed destination. |
| Read changes | `GET /api/v1/roots/{root_id}/changes?cursor=...` | Cursor scope, epoch and grant revision. |
| Snapshot | `POST /api/v1/roots/{root_id}/snapshots` and paginated read | Stable index snapshot and high-water cursor. |
| Acknowledge metadata | `POST /api/v1/sync/acks` | Applied metadata plus durably queued content work. |
| Transfer offer | `transfer.offer` / `transfer.offer_response` frames on `/api/v1/events` | Authorized root/file/version, stable offer ID, expiry and recipient acceptance; no forced phone path. |
| Chat exchange | Typed frames on `/api/v1/events` | Authenticated peer, E2EE conversation binding and bounded inbox. |
| Attachment transport | Transfer routes with `kind=encrypted_attachment` | Opaque blob ID, peer ACL, size/quota and expiry. |
| Device administration | Local IPC, no ordinary LAN route | Local owner confirmation for grants, roots and revocation. |
| Diagnostic export | Local UI/IPC | Redacted, user-approved export; not an unauthenticated health dump. |

## Desktop-initiated file send and receiver authority

For **Send to phone**, the Windows owner queues a peer-scoped offer containing an offer ID, authorized root/file/version references and expiry. Once Android is reachable, it can accept according to the user's explicit receive/storage policy and create a normal download through the HTTPS API. Declined, expired, revoked or repeated offers have idempotent outcomes. The agent revalidates the source version and grants at acceptance; an offer is not permanent permission or proof that bytes were received.

The receiver owns completion truth: Windows commits uploaded content; Android commits downloaded content and reports an authenticated receipt. A source-side “all bytes sent” counter does not establish receiver completion. External provider export is tracked separately. Offers/control frames do not require chat permission, but they do require their own file capability and bounded queue policy.

## Retry and idempotency semantics

- Namespace idempotency records by authenticated device, operation and key.
- Bind each key to a digest of the request's semantic parameters. Same key with different parameters returns `IDEMPOTENCY_CONFLICT`.
- Persist the logical operation/result reference before replying. A lost reply must not create a second file or trash entry.
- Chunk PUT is idempotent for the same transfer/index/digest. Different content for an already accepted index is an error.
- WebSocket events are notifications, not durable facts. After a gap, query status or replay the appropriate durable journal.
- Delivery is **at least once at the transport level**, with deduplicated application effects—not a magical exactly-once network.
- Keep operation receipts for a documented retry horizon. After expiry, return an explicit expired/unknown result; never blindly repeat a potentially completed mutation.
- Proposed interactive reconnect: full jitter between zero and `min(30 seconds, 0.5 seconds × 2^attempt)`. Reset after stable connectivity; background work follows OS scheduling rather than an endless loop.
- Retry transient network/overload failures. Pause for disk full. Stop for revoked identity, invalid manifest, incompatible protocol or denied permission until the cause changes.

## Proposed limits and timeouts

| Item | Starting value to validate |
|---|---|
| Directory page | 100 entries; hard maximum 500. |
| Ordinary control JSON | Maximum 1 MiB, with lower per-route limits. |
| Chat envelope | Maximum 256 KiB including encoding overhead; text limit lower in UI. |
| Chunk payload | Default 4 MiB; negotiated bounded range, initial maximum 8 MiB plus documented format overhead. |
| Concurrent transfers | Two globally active jobs initially; per-peer and per-root caps. |
| In-flight chunks | At most two per transfer initially; lower when storage/network is slow. |
| Control timeout | Initial 5 seconds, operation-specific exceptions. |
| Chunk timeout | Initial 30 seconds without progress, not 30 seconds for the whole file. |
| Visible-session heartbeat | Initial 30-second interval; expire after bounded missed replies. |
| Invitation attempts | Bounded per invitation/IP and globally; increase delay after failures. |
| Outbox and staging | Explicit byte/job quotas; no unlimited durable queue. |

These values are engineering defaults, not security proofs. Proxy, parser, TLS and application limits must agree. Disable automatic decompression or strictly bound expansion where used.

## Example structures

Illustrative transfer response; digest strings and IDs below are placeholders, not a finalized schema:

```json
{
  "protocol_version": 1,
  "transfer_id": "opaque-transfer-id",
  "direction": "download",
  "source_version": "opaque-immutable-version",
  "manifest_id": "opaque-manifest-id",
  "size_bytes": "1073741824",
  "chunk_size_bytes": 4194304,
  "chunk_count": 256,
  "hash_algorithm": "sha256",
  "content_sha256": "64-lowercase-hex-characters",
  "manifest_pages": 1,
  "expires_at": "2026-11-06T10:00:00Z"
}
```

Illustrative chat frame; authenticate the peer from the session and the sender from the E2EE protocol, not the declared fields:

```json
{
  "type": "chat.envelope",
  "protocol_version": 1,
  "message_id": "opaque-stable-message-id",
  "conversation_id": "opaque-conversation-id",
  "created_at": "2026-10-30T10:00:00Z",
  "content_type": "application/vnd.narada.encrypted-message",
  "ciphertext": "base64-encoded-library-output"
}
```

Illustrative error:

```json
{
  "error": {
    "code": "SOURCE_CHANGED",
    "message": "The selected source version is no longer available.",
    "retryable": false,
    "request_id": "validated-correlation-id",
    "action": "START_NEW_TRANSFER"
  }
}
```

Define stable codes including `AUTH_REQUIRED`, `DEVICE_REVOKED`, `IDENTITY_CHANGED`, `PERMISSION_DENIED`, `PATH_REJECTED`, `SOURCE_CHANGED`, `DESTINATION_CONFLICT`, `HASH_MISMATCH`, `DISK_FULL`, `URI_PERMISSION_LOST`, `CURSOR_EXPIRED`, `TRANSFER_EXPIRED`, `RATE_LIMITED`, `PROTOCOL_INCOMPATIBLE` and `CRYPTO_STATE_UNAVAILABLE`.
