# Transfers — durable state, recovery and precautions

[Protocol](transfers-protocol.md) · [SAF boundary](saf-export.md) · [Workflow](phase-4-workflow.md)

## Protected records

Room remains schema 3. The fsynced AES-GCM sealed no-backup aggregate now writes payload **v3**, reads v1/v2/v3 and preserves its original key ownership, trust AAD and Room anchor. Legacy reads migrate lazily; there is no reset, rekey, trust replacement or destructive migration. The aggregate holds connection trust, bounded file-operation history, downloads and exports. Its encoded and parser budgets are bounded; no filename, manifest, destination URI or content key is copied into Room/DataStore/support logs.

Damage, missing evidence or unavailable native keys fails closed. Protected-storage failure latches networking off; a remote/provider operational error is not automatically a local identity-storage failure. The foundation corruption marker and sealed consistency checks do **not** provide coherent whole-state rollback resistance. Installing old code against a newer payload is not a recovery strategy.

## Download state

| State | Meaning / next action |
|---|---|
| QUEUED | Explicitly admitted for a foreground run; only queued records may start |
| RUNNING | One durable run token owns mutations and checked progress |
| PAUSED | No automatic replay; user explicitly resumes the same identity/version |
| VERIFIED | Full private verification and receipt persisted; finish ACK may still be unconfirmed |
| CANCELLED | User stopped this attempt; displayed progress is zero; cancellation is not byte deletion |
| CORRUPT | Local verification failed; displayed progress is zero; never report success or silently replace evidence |

Restart reconciliation moves unfinished work to **PAUSED**, never automatic network continuation. Cancellation/pause joins the previous worker and invalidates its run ownership. Guards are checked inside protected mutations as well as around I/O. These are fences against stale new results, **not rollback guarantees for already admitted fsync/provider work**.

Encrypted chunk install precedes checkpoint. Resume re-decrypts and re-hashes saved fragments; full-file verification precedes the private receipt. Losing an ACK does not unverify a sound local receipt. Fully staged data may be explicitly checked locally in the foreground. Revoking desktop permission stops new authorized reads, not access to a copy already received.

## Capacity and removal

Four retained downloads and 128 MiB aggregate; each file is at most 64 MiB. Admission checks and disk-space checks are conservative, not physical capacity reservations. Racing storage use, ENOSPC and fsync/link failures remain possible and are handled as failures, not successes.

Removal is explicit. Encrypted storage only removes known owned fragments; symlinks/unexpected entries block cleanup. Cancelling is not deleting; removing the private copy is not deleting a provider export. There is no silent copy/history eviction or automatic deletion of uncertain destination files. No guarantee of secure physical erasure is made.

## Export state on interruption

| Recorded boundary | Recovery |
|---|---|
| Picker pending; no admitted create | NOT_CREATED after restart; no automatic picker/create replay |
| INTENT/create admitted, even if URI reply was lost | UNCERTAIN; destination may contain nothing, an empty, partial or full document |
| CREATED URI recorded | UNCERTAIN after interruption until explicit review; never reopen it for automatic rewrite |
| Exact independent readback + final receipt | VERIFIED; private receipt remains separate |
| Reviewed unknown outcome dismissed | DISMISSED_UNCONFIRMED, **not** proof of non-creation or recall |

A stale/duplicate picker callback has no fresh authority. Each intent has a unique activity-result registry key, captured export ID and one in-memory claim. Restart does not restore permission to create. Actual Android `onStart`/picker result delivery timing is a device gate despite host registry tests.

Two export records are retained. A third is rejected with `EXPORT_HISTORY_FULL`. Only reviewed terminal VERIFIED, NOT_CREATED or DISMISSED_UNCONFIRMED records can be forgotten; forgetting is bookkeeping, never provider deletion, undo, proof of cleanup or recall. Another export requires new review/consent/intent and a fresh create-new target.

## Signing and backup

The original Phase 1–3 debug private signer is missing. The supplied Phase 4 APK has a **different** development certificate, so it cannot establish same-signer in-place upgrade. Do not uninstall/clear-data to bypass that conflict on a valued profile. Establish your own controlled signer before adopting a development profile you need to preserve.

Android backup/device-transfer exclusion policy is unchanged and has not been qualified across OEMs. Private chunks/keys must not be exported as diagnostic data. The private copy is only protected within the app's stated threat model; root/OS compromise, a malicious provider, earlier external copies and physical storage rollback are not solved by this slice.
