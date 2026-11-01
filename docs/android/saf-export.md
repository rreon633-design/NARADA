# SAF export — separate consent, new document, explicit uncertainty

[Private-copy workflow](phase-4-workflow.md) · [Recovery](transfers-recovery.md) · [Native checklist](phase-4-device-checklist.md)

## Three separate authorities

1. Desktop grants authorize remote reads. They are not phone URIs.
2. Storage settings retain **read-only** SAF access for the earlier local-storage adapter. They do not authorize writing downloads.
3. Every export requests a fresh **temporary read + write tree grant** through `ACTION_OPEN_DOCUMENT_TREE`. No persistable broad write grant is taken.

The app never uses an existing-document truncate/replace path. It creates a new child under a selected create-capable directory and rejects its own FileProvider authority. Provider windows are outside the app's control. The main Activity sets `FLAG_SECURE` before composition; that is an implemented policy, not device/OEM screenshot-protection evidence and not protection for exported documents.

## Consent before publication

The review explains that destination bytes are no longer protected by the app's private encryption, a provider may upload/share/retain them, interruption can leave empty/partial/full files, and a verified private copy is separate. Offline export of a previously received, locally verified copy is explicit and allowed; remote revocation cannot recall bytes already received.

Persist the export intent **before picker/create**. Fully verify the private source before any document creation. A user abandoning a picker is different from an admitted create whose reply was lost.

## Provider algorithm

- Validate a content/tree URI, offered read/write flags, create-capable directory and complete bounded child snapshot (at most 256 children). Reject partial/truncated metadata, unsupported/virtual targets and invalid rows.
- Validate a safe name and NFC/case-fold collision checks. Reject collisions rather than overwriting or selecting another existing document. A racing collision can still create an uncertain outcome.
- After create, verify the returned name and a fresh child identity absent from the pre-create snapshot, correct descendant scope, nonvirtual ordinary document and empty target.
- Persist the returned URI before writing. Use bounded chunks and a **no-truncate** descriptor mode (`wa`); do not use `w`/`wt` to replace an existing document.
- Close the writer, independently reopen for reading and demand exact byte count, no trailing bytes and full SHA-256. Only then persist the VERIFIED export receipt, with a guard **inside** that final protected mutation.
- Never automatically delete a target to “clean up” a failure. Never automatically reopen/rewrite an uncertain target. Keep the uncertainty journal even when a later create denies or collides.

A provider can lie about identity, stream semantics, metadata or storage behavior. Readback proves the bytes returned at that check, **not atomic publication, physical durability, exclusive ownership or future retention**. A cloud/provider may expose an incomplete file while it is being written. NARADA cannot guarantee a hostile provider obeys mode/grant/cancellation contracts.

## Bounded execution, not forcible termination

One provider worker, one absolute monotonic **90-second** budget, cancellation signals, interruption and descriptor closure. Worker guards independently check the absolute deadline, not merely whether the caller was scheduled to time out. The worker passes `Result<Unit>` as data so future/coroutine exception wrapping does not erase typed storage/provider failures.

If a provider/helper does not cooperate, its slot stays **quarantined** until worker, caller and cancellation helper have all completed. No unbounded replacement threads or concurrent new export worker are created. Late work cannot report a new verified result after its guard rejects it; already admitted external/durable work cannot be rolled back. The operating system may still be unable to stop hostile Binder/provider activity.

## Evidence and still-open gates

Core export tests cover ordering, uncertain create/write/readback, private corruption, exact bytes, capacity, reviewed forgetting and failures. Host tests exercise a custom Robolectric provider, registry ownership, typed errors, quarantine, deadlines and cancellation. These are **not** real OS-grant, Binder, cloud, removably mounted storage or provider compliance evidence. Eight native instrumentation cases compile, but none were run.

Qualify real local/cloud/removable providers, revoked grants, rename/collision behavior, pipe/nonseekable descriptors, storage full, kill/rotation while picking/creating/writing/readback, dishonest metadata, callback timing and slow/unresponsive providers before release. See the explicit [checklist](phase-4-device-checklist.md).
