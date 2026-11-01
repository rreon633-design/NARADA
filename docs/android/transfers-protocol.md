# Transfers — communication and integrity mechanisms

[Scope](phase-4.md) · [Recovery](transfers-recovery.md) · [SAF export](saf-export.md) · [Connection protocol](connection-protocol.md)

## Authority and ownership

The actual unchanged desktop `transfers.v1` protocol is reached through pinned, network-bound TLS 1.3 mTLS. Hello/version/capability, active pairing, current grants and pre/post authenticated health are checked. Pending pairing blocks content leases. The application-scoped connection coordinator owns sockets and commands, not a Composable or ViewModel.

A content lease cancels **and joins** health before opening content sessions, then restarts health only when eligible. Files and Transfers have separate cancellation ownership. Epoch, pairing, foreground, protected intent/run token and receipt fences run before/after network work and inside protected state mutations. Authority changes discard new remote results; they cannot erase already received bytes or roll back work already admitted to durable storage.

Production transfer admission is shared and spaced by **300 ms**, without authentication retries, protocol downgrade or weaker server limits. This accommodates the unchanged desktop's 12-new-TLS-starts/second admission gate and native health restart pattern. Actual-peer tests pass; other clients can still exhaust admission, and Android radio/Conscrypt scheduling remains unqualified.

## Immutable contract

1. Persist the selected root/object/version, expected name/size and a fresh operation UUID before `prepare`.
2. Resume uses the **same UUID and source/version**. A changed request with the same UUID conflicts; expiry/staleness never causes an automatic new snapshot.
3. Collect all manifest pages (32 hashes/page), binding transfer/source/manifest IDs on every response.
4. Recompute `manifest_id` as SHA-256 of compact UTF-8 JSON for this exact tuple:

```text
[version, source_version, name, size, chunk_size, chunk_count, sha256, hashes]
```

`size` is the canonical decimal string used by the desktop. The digest covers **all** chunk hashes, not just page one. Empty content has zero chunks and its full-file digest.

The desktop enforces immutable snapshot identity/version, Read (and applicable SyncRead), exclusions, hidden/path rules, snapshot integrity and expiry. Its source limits remain 64 MiB/file, 128 MiB total, 24-hour expiry, eight active sources and 128 history entries. The expiry test clock-ages an owned stopped fixture database; it does not prove 24 hours of wall-clock operation.

## Binary transport

Chunks use `GET /api/v1/transfers/chunks/{id}/{index}` with protocol transfer-version 1 and zero request body. IDs and index are strict; binary responses require the expected exact length, octet-stream, no content/transfer encoding, no-store/nosniff and matching chunk SHA-256. JSON errors and all parsing/nesting/node/body budgets remain bounded. No redirects, DNS endpoint, public/cellular/VPN endpoint, phone listener or raw desktop filesystem path is introduced.

Desktop JSON status/progress is **not phone progress**. The phone verifies bytes independently. The desktop also limits requests per session and binary lanes; client pacing does not remove any peer policy.

## Private receipt sequence

```text
protected intent → prepare/pages → whole-manifest digest
    → re-decrypt/re-hash any saved chunks
    → fetch missing chunk → exact length + SHA-256
    → AES-GCM seal → fsync → hard-link no-replace install → directory sync
    → sealed checkpoint
    → re-read every chunk + full-file SHA-256
    → sealed VERIFIED private receipt
    → independent finish acknowledgement attempt
```

The bitmap alone is not evidence. A crash after chunk installation but before checkpoint can be reconciled by rechecking that exact encrypted chunk. A missing/corrupt previously acknowledged chunk is not silently overwritten as though still verified. Complete staged data can be **explicitly reverified locally** while foregrounded, without preparing a replacement source.

AES-256-GCM uses the existing AndroidKeyStore transfer key and a fresh 96-bit IV per chunk. AAD binds a distinct chunk domain, store UUID, transfer UUID, manifest ID, index and plaintext length. Opaque private paths live beneath `noBackupFilesDir/received-v1`; source filenames never become local filesystem paths. This is private-at-rest protection plus authenticated transport, **not age attachments or Olm messaging**. No production software-key fallback exists.

Private receipt and finish ACK are distinct: a lost/denied finish response must not erase a verified local copy. A verified private receipt is also distinct from a SAF export receipt and from current desktop permission. See [recovery](transfers-recovery.md) for limits and irreversibility.
