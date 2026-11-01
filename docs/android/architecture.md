# Android architecture — trust, chat, attachments and retained versions

[Project / tech stack](../../apps/android/README.md) · [Plan](../../android-plan.md) · [Storage/privacy](security-storage.md) · [Communication](attachments-protocol.md)

## Layers and ownership

```text
Activity / Compose / five-tab navigation / lifecycle
           │ explicit intents; private memory-only projections
           ▼
ConnectionsCoordinator ─── owns sockets + content leases, cancels/joins health
    │ pairing/epoch/foreground and pre/post-health fences
    ├─ FilesCoordinator → FilesClient / bounded mutation journal
    ├─ ChatCoordinator → ChatEngine / ChatClient → stateless Olm JNI
    ├─ AttachmentsCoordinator → AttachmentsEngine / Client → stateless age JNI
    ├─ TransfersCoordinator → DownloadEngine / TransfersClient
    └─ OfflineCoordinator → OfflineEngine / SyncClient → exact download references
           │
Protected trust repository + owned AndroidKeyStore sealing
           ├─ fsynced noBackup aggregate, Room3 enrollment anchor
           └─ separate encrypted download / attachment chunk directories

SAF picker bindings → single-use admitted intents → shared bounded provider worker
    (temporary import/export grants; deadline + quarantine; external uncertainty)
```

`core:domain` and `core:connection` are platform-free Kotlin modules. The Android app supplies lifecycle, network-bound sockets, AndroidKeyStore, SAF, encrypted chunk storage, Room/DataStore, Hilt and Compose adapters. Rust adapters are stateless JNI libraries, not a listener or ported desktop backend.

## Durable state versus presentation

- DataStore owns non-secret setup/name/notification/motion preferences. Room3 retains bounded operational ledgers and a public connection enrollment anchor, not content/crypto plaintext.
- The sealed aggregate is payload **6**, reading versions1–6 without rekeying. It includes pairing/trust, the 32-record file mutation journal, downloads/exports, chat account/session/drafts/history/outbox/inbox/receipts, attachment secrets/jobs and explicit offline metadata/pins.
- Missing/damaged ownership or protected storage fails closed and disables networking. Atomic-file/fsync/readback continuity does not establish whole-state rollback resistance.
- General navigation/preferences may restore non-secret UI state. Sensitive Files/Chat/Transfers/Attachments/Offline/Devices subtrees do not persist plaintext in SavedState; private projections clear on tab/lifecycle/authority changes. Ordinary browsing metadata/pixels remain memory-only; only explicitly scoped offline metadata is sealed persistently.
- Public connection state strips chat, attachment, offline cache and pin content. UI receives only the currently authorized minimal private projection. No sample activity is inserted into production state.

## Transport and cancellation

Only allowed private IPv4 Wi-Fi/Ethernet routes are admitted. Pinned bootstrap, explicit desktop approval/fingerprint comparison, durable ACK_PENDING and authenticated health precede Connected. Content uses coordinator-owned outbound sockets, never a phone content server. Pending pairing blocks content; changed pairing/epoch/foreground/grants invalidate results before publication. Protected-storage failure latches networking off.

Shared content admission remains 300 ms; no auth retry/downgrade or increase to the desktop's TLS-start limit. Cancellation cannot roll back work already admitted remotely or undo external publication. Retained exact requests, receipts and unknown outcomes make that boundary explicit.

## Transactions and mechanisms

- **Chat:** ratchet + exact ciphertext before send; ratchet + inbox + receipt before ACK. Explicit retry never silently re-encrypts. Bridge2 adds typed attachments while retaining the old text digest.
- **Attachments:** bounded read-only import → fresh age encryption → immutable encrypted staging → protected receipt + typed message atomically. Manifest commitment precedes explicit upload. Full authentication precedes availability/export. The ciphertext-only storage adapter never enters ordinary transfer UI/wire metadata.
- **Downloads:** immutable source/version/UUID/manifest; encrypted chunk before checkpoint; decrypt/hash all chunks/full file before private receipt. Remote finish ACK is independent.
- **Offline:** scoped work stages metadata separately; pin + exact candidate commit before `prepare_sync`. Adoption re-verifies bytes and atomically switches references, retaining current/one previous. Only independent `remoteFinished` may differ during verification. No silent replacement/eviction.
- **SAF:** durable reviewed intent before picker/create; single-use binding; fresh create-new target and durable URI before write; close/readback before external receipt. Uncertain targets are never automatically rewritten/deleted. The shared 90-second worker may quarantine noncooperative providers; atomic publication/stoppability is not guaranteed.

## Background and native boundaries

WorkManager's existing unique local-check request is not background messaging, sync or permanent presence. UI observation does not start content work. The phone never exposes an owner reindex API or bidirectional filesystem writer.

Linux software-key/JSSE/JNI/Robolectric fixtures are test-only and APK DEX inspection excludes them. Production transport/storage ownership stays with AndroidKeyStore; age/Olm secrets are software-protected at rest, not hardware-held content keys. Hardware reports are not attestation. [Native and independent gates](phase-6-7-device-checklist.md) remain open despite the [796-check development campaign](phase-6-7-validation.md).
