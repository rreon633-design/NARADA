# Android chat — durable recovery and precautions

[Workflow](phase-5-workflow.md) · [Protocol](chat-protocol.md) · [Device checklist](phase-5-device-checklist.md)

## One protected ownership boundary

Room remains schema3. The sealed no-backup trust aggregate is now **payload 4, reading1–4**, using the existing key/owner/AAD and enrollment anchor. Migration preserves trust, mutation/download/export records and does not silently create a messaging account or regenerate identity. The parser permits at most2 MiB/65536 nodes for the aggregate; wire limits are smaller.

Account/session candidates, key pins, drafts, history, envelopes, receipts and retry/run markers are encrypted together. Protected read/persist validation checks native account public keys and session identity/configuration. Missing/damaged enrollment/key/state evidence is not treated as a new empty account. Storage failure latches connection networking off.

## Outbox states

| Retained state | Meaning | Recovery / allowed consent |
|---|---|---|
| `QUEUED` | Protected text/ID/scope, no retained envelope | Explicit sync may prepare/send; no automatic restart send |
| `READY` | Ratchet and exact ciphertext already saved | Only that envelope is eligible; recovery never re-encrypts |
| `UNCERTAIN` | Admitted attempt without a saved matching receipt | Review exact retry within budget, or cancel further attempts; remote effect may exist |
| `DELIVERED` | Exact matching protocol receipt durably saved | Retain history/receipt; not a human read receipt |
| `CANCELLED_NOT_SENT` | Cancelled before envelope/send admission | No remote delivery claimed |
| `CANCELLED_UNCONFIRMED` | Further local work cancelled after possible admission | Not proof that the desktop did not receive it |

A run token and attempt are persisted before network admission. Restart clears/reconciles run markers into safe retained states without sending or encrypting. Three-attempt/four-second policy is enforced; “retry” is not permission to reset a budget, invent a UUID or change text. There is no history-clearing/session-eviction shortcut when the bounded store fills.

## Incoming receipt recovery

Advanced ratchet, history and receipt must commit together before ACK. A failed commit sends no ACK. A duplicate with the same retained identity/digest reuses the receipt; conflicting content is refused. Interrupted ACK leaves an unacknowledged local receipt; the next explicitly requested batch can re-ACK it. Unsupported attachments are not silently consumed.

## Lifecycle, scope and privacy

- Chat has an application-owned coordinator, not a screen-owned socket or permanent service. Leaving the tab, losing foreground or changing authority cancels work and clears the private projection/composer.
- The UI rechecks a live access snapshot before rendering; public connection state strips the chat aggregate. No messages/pickles/drafts are placed in SavedStateHandle, `rememberSaveable`, Room/DataStore metadata, diagnostic exports or notification extras.
- Typing is memory-only until an explicit encrypted draft save or queue commit. Leaving loses an unsaved composer; this is not a hidden plaintext auto-save.
- Offline saved history and existing private copies are not erased by remote revocation. New network work still needs valid current grants, pairing, route and foreground.
- Re-pairing retains history/pins and requires explicit renewed consent. Old-scope messages never auto-replay. A changed messaging key is a hard review error, not a “clear data to fix” instruction.
- `FLAG_SECURE` is set before composition. Physical capture/IME/accessibility/OEM behavior requires native review; it cannot control external provider documents/windows.

## Storage and cryptographic caveats

Fsync/atomic-install/host fault tests establish the implemented ordering in the tested environment, not phone power-loss durability. Whole-state rollback protection, hostile privileged-state replacement resistance and guaranteed plaintext memory erasure are unproven. Software Olm secrets are protected at rest, not hardware-held ratchet keys. No protected-storage software fallback is shipped.

The32-message/four-binding/two-session and2 MiB aggregate limits intentionally fail closed. Unknown outcomes, history, pins and previous file/export journals are not silently evicted to make room. There is no promise that all count limits can be reached simultaneously within the byte budget.

## Notification precautions

Notifications are opt-in and default off. After a successful explicit foreground batch observes newly saved incoming messages, the callback carries **no private arguments**. Notification1002 contains only title `NARADA` and body:

> Private message activity. Open NARADA to view.

SECRET visibility, local-only, generic immutable explicit launch intent; no sender/text/message or peer ID, content extras or actions. Notifications do not imply background delivery, read status or continuous presence. Actual lock-screen/OEM behavior is not host-qualified.

## Upgrade / repair is not reset

The supplied0.5.0 debug certificate differs from0.4.0 and0.1–0.3. A normal in-place install is not qualified. Test only in a fresh disposable profile/device unless controlled signing/migration is independently available. **Uninstall, clear-data, deleting anchors or regenerating keys is not lossless repair.** It can orphan trust, history and received copies.

Retain damaged evidence privately for scoped review; never export keys/pickles/raw stores in support reports. The development package includes no old private signing key and offers no bypass for signature/key continuity.
