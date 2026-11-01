# Attachments — recovery and precautions

[Protocol](attachments-protocol.md) · [Offline recovery](offline-recovery.md) · [Native tasks](phase-6-7-device-checklist.md)

| Interruption / condition | Required behavior |
|---|---|
| Picker pending, no import admitted | Single-use owner; cancel/abandon the intent, never automatically reopen the picker |
| Import/encryption interrupted | No automatic retry or nonce reconstruction; fresh user selection/encryption is a new intent |
| Ciphertext fully staged, upload interrupted | Retain original bytes/key/descriptor and original pairing; reconcile exact remote bitmap on explicit resume |
| Chat manifest not delivered | Do not enable/upload bytes; explicitly synchronize the protected chat first |
| Lost chunk or finish reply | Unknown is not failed; query/reconcile exact IDs. Never silently generate a replacement message/blob |
| Changed grants, peer, epoch or foreground | Discard stale results, stop further networking; a previously verified private copy is not erased |
| Missing/damaged protected state | Fail closed and disable networking; no reset/rekey or software storage fallback |
| Quota exhausted | Keep existing records. Review explicit cancellation/removal; do not evict automatically |
| Receipt/hash/age failure | No availability or export success; retain explicit corrupt/recovery state |

## Separate SAF publication

Import uses only the returned temporary **read** permission. Export is a separate reviewed create-new document flow with temporary read + write authority, never a broad retained write grant. A durable export intent precedes picker/create; the picker result is single-use and bound to the admitted intent. A fresh target scan is bounded to 256 children; names are validated. No overwrite/truncate or automatic retry/delete is authorized.

All private data is reverified **before creation**. The new document URI is durably recorded before writing; the provider is closed and read back before VERIFIED. Picker-only interruption becomes NOT_CREATED. An admitted create, missing URI result, provider stall, write/readback failure or process death can leave an **UNCERTAIN** external document. Dismissal does not mean it never existed and does not delete it.

At most two export records are retained per copy. Unresolved or full export history disables another export until explicit review/forget as permitted. A new attempt requires new consent/intent and a fresh create-new target, never an automatic rewrite of the uncertain one.

The shared provider worker has a 90-second absolute budget and quarantine. A deliberately noncooperative provider can keep an old worker stuck; NARADA does not promise Binder stoppability, atomic publication, external durability or recall. Returning from an external provider window does not make that window part of NARADA's FLAG_SECURE guarantee.

## Privacy and key custody

Private captions, names, keys, raw paths, provider URIs and plaintext hashes are not diagnostic/log payloads or ordinary transfer metadata. Plaintext/pixels remain in bounded memory unless explicitly sealed or knowingly exported. Sensitive UI does not use SavedState and is cleared on tab/lifecycle/authority changes. Notifications remain generic and opt-in.

Software age/Olm secrets are protected at rest by AndroidKeyStore sealing, not independently attested/hardware-held content keys. VM/native copies and external providers prevent a blanket erasure claim. Whole-state rollback resistance and crash/ENOSPC/OS-keystore behavior require the [unexecuted native matrix](phase-6-7-device-checklist.md).

The current development APK signer differs from Phase 5. Do not uninstall or clear valued data to bypass an update conflict: that loses identity, trust, sealed history and private references. Use a new disposable test profile/device; same-signer migration is not qualified.
