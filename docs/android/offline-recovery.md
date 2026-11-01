# Offline copies — recovery, conflicts and precautions

[Protocol](offline-protocol.md) · [Attachment precautions](attachments-recovery.md) · [Workflow](phase-6-7-workflow.md)

## Three different facts

1. **Metadata observed:** a complete scoped listing/change stream was committed.
2. **Private bytes verified:** the download has a complete authenticated local receipt.
3. **Pin adopted:** explicit re-verification selected that exact version as current.

None of these means a file was exported externally. A remote finish acknowledgment is also separate from private verification. UI timestamps/“latest listing” must not collapse these distinctions.

## Recovery table

| Situation | Outcome / action |
|---|---|
| Restart during snapshot | Preserve completed metadata; exact bounded staging may resume, otherwise request a fresh snapshot |
| Expired cursor or endpoint/scope change | At most one automatic snapshot restart per refresh; stale scope cannot authorize pin/download |
| Refresh fails part-way | Prior completed metadata, current version and any previous reference remain intact |
| New version observed | It is an observation, not an automatic download/adoption. Confirm refresh, complete the candidate, then confirm adoption |
| Original source deleted | Show a tombstone; retain the verified private version |
| Same path, new object identity | Do not rebind; retain the original pin/tombstone and review a separate object |
| Candidate corrupt or receipt changed | Refuse adoption; no replacement of current bytes |
| Desktop finish ACK races verification | Permit only that independent flag change; receipt/source/manifest/state changes still fail |
| Quota full / previous version retained | Do not silently evict. Review references, stop candidate work and separately remove unneeded private bytes |
| Unpin/release | Reference removal only; private downloads remain in Transfers |
| Remote grant/revocation loss | Refuse further remote work; already received offline/exported copies cannot be erased remotely |

“Remove metadata” is allowed only when no pin references that root. A late old page/error/expiry cannot recreate it or damage a newer refresh. Cancel is local and cannot undo work already admitted remotely. Unknown external export outcomes remain separately reviewable and are never treated as failed/nonexistent merely because a record was dismissed.

## Operational precautions

Use a disposable test peer/root and small ordinary files first. Keep a known verified current copy while testing source update/deletion. Review object identity and expected version, not just a display path. Explicitly adopt a completed candidate; an observer seeing its receipt does not do this for you.

Missing/damaged sealed state or enrollment evidence disables networking. Never clear app data as a “repair,” replace keys, rewrite the protected database or bypass scope checks. Whole-state rollback resistance, native power-loss/ENOSPC, Android grants/Binder behavior, Windows filesystem identity and same-signer migrations remain [qualification tasks](phase-6-7-device-checklist.md).
