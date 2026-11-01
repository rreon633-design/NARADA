# Connection protocol and mechanisms

[Scope](phase-2.md) · [Security](connection-security.md) · [Workflow](pairing-workflow.md)

## Actual authority

The current desktop Rust protocol, not a newly invented mobile API, is authoritative. Production Kotlin lives in `apps/android/core/connection`; Android adapters live in `app/.../connection`. The isolated interoperability harness starts a real corrected Rust agent with a new disposable profile and owner-only Unix IPC. It never replaces the server with a successful mock.

## End-to-end communication

```text
Desktop owner                         Android user/client
Enable private listener               Prepare protected identity; enable connections
Create short-lived invitation   →     Scan/import/paste; review full host fingerprint
Pinned TLS 1.3 bootstrap         ←     One claim, only after cert + SPKI pin verification
Consume token; return API port   →     Persist host certificate + WAITING, never token
mTLS proves phone private key    ←     /pair/v1/prove, bounded foreground polling
Compare phone fingerprint;
explicitly approve grants       →     APPROVED is not ACTIVE or Connected
                                      Compare phone fingerprint on desktop
                                      Persist ACK_PENDING before sending ACK
Durable peer activation          ←     /pair/v1/ack over mTLS
Idempotent ACTIVE reply          →     Persist ACTIVE; open authenticated health
Valid health and grant counters  →     Persist, then display Connected
```

`CLAIMING → WAITING → APPROVED → ACK_PENDING → ACTIVE`; cancellation/revocation creates a durable `REVOKED` tombstone with a new local epoch. A failed/uncertain bootstrap is never replayed. A late proof, ACK or health response cannot overwrite a changed attempt/epoch. A distributed ACK already committed remotely cannot be “unsent”; revoke on the desktop too.

## Wire contract

| Item | Contract |
|---|---|
| Invitation | UTF-8 JSON ≤4 KiB; `format=narada-pairing`, version 1; invitation UUID, 64-hex token, private IPv4 `address:port`, certificate SHA-256 `fingerprint`, SPKI SHA-256 `device_id`, label, UTC expiry |
| Invitation lifetime | Desktop 120 seconds monotonic; client rejects expired or implausibly future invitations (150-second allowance) |
| Identity | P-256 / ECDSA-SHA256 self-signed certificates; exact DER certificate and SPKI pins; key-possession checks |
| Server profile | `narada.local` DNS SAN, standard chain/hostname verification against the exact pinned leaf, current validity and self-signature |
| Android client profile | Generic subject; client-only self-signed cert may omit SAN/EKU; actual Rust verifier accepts this profile |
| Transport | TLS 1.3 only; ALPN `http/1.1`; fresh context per connection, no cross-connection resumption/0-RTT; no insecure verifier |
| Claim | POST `/pair/v1/claim`: hello, invitation/attempt IDs, token, public certificate, label, platform `android`; response echoes attempt and returns certificate/API port |
| Proof/ACK | POST `/pair/v1/prove` and `/pair/v1/ack`: hello + same invitation/attempt IDs, mTLS identity required |
| Health | POST `/api/v1/health`; body is **Hello directly**, not `{hello: ...}` |
| Hello | Version 1, mandatory capabilities `connection.health`, `connection.permissions` |
| Envelope | `{ok,data,error}`; status/body consistency, ≤16 KiB, no duplicates/unknown fields/type coercion; bounded chunked or content-length JSON |
| HTTP exclusions | No redirects, queries, compression, duplicate lengths, ambiguous length+chunked or chunk trailers |
| Grants | Chat/attachment flags; ≤8 unique UUID roots, unique subset of `browse,read,create,modify,trash,sync_read` |
| Health authorization | Pinned device ID, non-regressing grant revision/session epoch, consistent grants; validate and persist before UI use |

Recognized operation offers are the desktop's cumulative sets beginning with `health,permissions`, then files, transfers, or the full chat/attachments/sync set. Recognizing an offer does not activate those Android features.

## Resource and lifecycle policy

- One live foreground health connection; poll about every 5 seconds, renew at about 50 seconds against the desktop's 60-second session limit.
- Connect/handshake absolute budget 9 seconds; request absolute budget 8 seconds with a 5-second read timeout. Slow, non-idle TLS responses are covered by a real socket deadline test.
- Six **persisted** consecutive transient failures, bounded jitter/backoff; authenticated health resets the count. Fatal auth/pin/protocol failures stop for manual review. No token retry or automatic re-pair.
- Selected Android Network socket binding, numeric private IPv4 only. Phone loopback, cellular and VPN are rejected. No public/DNS/proxy fallback.
- NSD `_narada._tcp.` / TXT `v=1`: explicit 20-second scan, ≤32 hints, serial resolution and bounded queue. Untrusted hints remain at most 30 seconds after scan completion and clear on stop/background/route changes.
- API 34+ cancels an active NSD resolution. API 29–33 lacks that public cancellation method: callbacks are generation-fenced/discarded, but an already-issued platform query can finish. Older discovery also lacks the same per-Network API; results never confer trust.

No Android listener, network owner approval API, filesystem service or permanent presence service is provided.
