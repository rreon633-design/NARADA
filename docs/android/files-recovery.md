# Android file state, privacy and recovery

[Index](README.md) · [Protocol](files-protocol.md) · [User workflow](phase-3-workflow.md)

## Two different kinds of state

| State | Ownership and lifetime |
|---|---|
| Roots, entries, breadcrumbs, search, page cursors, details and pixels | Foreground `FileBrowser` / ViewModel memory only. Cleared on tab exit or observed authority/route/lifecycle invalidation. File request failure discards server metadata/pixels and cursor history; a foreground user search/location may remain for correction/retry. Successful views have a 60-second idle expiry, renewed by successful user requests, not by closing details. No Room, DataStore, saved-state or disk content cache. |
| File mutation intent/receipt | Inside the existing AES-GCM sealed trust aggregate under `noBackupFilesDir/connections-v1/trust.bin`, authenticated with the original AndroidKeyStore sealing key and enrollment binding. |
| Private transport key | Still non-exportable AndroidKeyStore P-256; no new production software fallback. |
| Phone SAF grants | Separate read-only foundation metadata/provider authority; never substitute for remote root grants. |
| Desktop recovery ledger/trash | Owned by the actual desktop agent; phone dismissal/revocation cannot erase, cancel or repair it. |

`FLAG_SECURE` protects the Files window; selected-file and mutation windows explicitly request secure policy. Drafts are plain in-memory strings only; neither the JVM nor Android IME promises immutable-string/bitmap forensic zeroization. Accessibility/keyboard/OEM privacy must be qualified on devices. No filenames/queries/paths/operation bodies are added to support reports, notifications or general activity logs.

## Persist before send

The user chooses a current, revalidated selection and explicitly confirms a managed action. The core boundary requires confirmation too; UI-only disabling is not authorization.

1. Check local fences, fresh authenticated health, operation advertisement and current root capabilities.
2. Create an operation UUID and exact root/object/version/action/name request. Bind it to the peer ID **and pairing attempt**.
3. Atomically persist **`UNCONFIRMED`** inside the sealed aggregate. A failed write means **no effectful POST**.
4. Recheck ownership and send that exact request over the pinned mTLS lease.
5. Validate a matching committed receipt and post-response authorization/fences.
6. Persist **`COMMITTED` + receipt** before returning success. The UI discards the old page and asks for refresh; it does not optimistically rename/remove a cached row.

Any timeout, lost reply, malformed response, cancellation, post-check failure, policy change or failed receipt write can leave a real remote effect with no local confirmed success. The record remains **unconfirmed**. A write may have reached storage even when its verification/fsync reported failure; reopen protected state to inspect it, rather than assuming an in-memory outcome.

## Exact retry is not a read-only status query

On explicit retry, current pairing, trust, capabilities and operation status are rechecked. The original operation ID and **all arguments remain unchanged**. The desktop’s actor-bound idempotency ledger may return the saved receipt; if the first request never arrived, the retry can apply it now.

- No new operation ID, rebased version, changed name, alternate path, overwrite or silent automatic resend.
- A replacement pairing cannot replay the old intent, even if the device’s identity ID is the same.
- A recovery-time permission denial does **not** prove the earlier attempt failed; it may have committed before access was withdrawn.
- A server error such as `STALE_VERSION` or `NAME_COLLISION` is shown, but the conservative local journal does not manufacture a negative receipt.
- One unresolved operation per peer blocks starting another. Browsing and owner inspection remain useful for review.
- If the desktop reports `OPERATION_INDETERMINATE`, preserve its files and inspect recovery on that desktop. Phone code does not bypass the barrier.

## Explicit dismissal

After inspecting the desktop, a user can acknowledge **Dismiss local retry**. This saves `DISMISSED_UNCONFIRMED`, with **no success receipt**. It means only that this phone will not retry that record automatically (there were no automatic retries to begin with) and may accept a new explicit action.

It does **not** undo, cancel, confirm, delete or repair the remote operation. The bounded 32-record local window may evict the oldest terminal committed/dismissed record to admit a new one; unconfirmed records are not auto-evicted. The desktop’s separate receipt/quota policy is unchanged.

## Migration and failure preservation

Room remains **schema 3**. The sealed trust payload now has version **2**, optional advertised operations and the file-operation list. Version-1 payloads with no file journal are accepted and lazily upgraded on a successful mutation; no key, certificate, pin, trust tombstone or Room anchor is replaced. The existing encryption envelope/AAD format is unchanged.

Missing/corrupt ciphertext, partial enrollment, key/anchor mismatch or unavailable Keystore is still fail-closed. A failed v1→v2 write preserves the original evidence. No destructive migration, silent reset, resealing under a new key, plaintext fallback or re-pair is attempted. Do not downgrade to older app code as a recovery method; it cannot understand the new payload.

Local storage/key failures latch networking off until explicit successful protected-state reopen. Remote file storage failures are a separate code and do not masquerade as local storage loss. As in Phase 2, failed disable/revoke persistence is **not** a durable cross-restart intent; close/retry/review both devices rather than claim completed revocation.

## Interruption guide

| Situation | Safe next step |
|---|---|
| File query offline/denied/revoked | Discarded view is not an empty successful folder. Review Devices/current desktop grants, then refresh. |
| Cursor expired / root index changed | Refresh from first page. The phone does not merge old and new snapshots. |
| Object changed/moved | Refresh, select again and recheck details. A saved mutation is never silently rebased. |
| Closed app / switched network mid-action | Reopen Files and review the sealed unconfirmed operation. Closing the socket cannot undo an admitted desktop change. |
| Desktop restarted after lost reply | Reconnect its saved identity (explicit endpoint change if needed), inspect it, then retry the same operation. No pin replacement. |
| Phone process died after durable intent | Journal survives protected-state reopening; no background resend. Native kill/fsync qualification still required. |
| Receipt write failed | No in-memory success. Reopen storage and recover the same operation; preserve both profiles. |
| Rename collision / nonempty directory / trash quota | No overwrite or permanent purge. Inspect desktop state and review/dismiss unknown local retry before another explicit action. |
| SAF provider disappeared/refused access | Handle that local grant under Storage. It neither changes desktop access nor exports/downloads remote content. |

## Known qualification limits

Host tests model write failure/reopen and real-agent lost-reply/restart, not real Android process kills, flash/fsync behavior, backup restore or AndroidKeyStore/Conscrypt. Coordinated whole-profile rollback, rooted-device compromise and forensic memory erasure are not solved. Native Windows filesystem boundaries and independent crypto/storage/codec review are still required. See [the checklist](phase-3-device-checklist.md).
