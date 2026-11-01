# Android — privacy, storage and recovery

[Architecture](architecture.md) · [Connection security](connection-security.md) · [Current evidence](phase-6-7-validation.md) · [Open gates](phase-6-7-device-checklist.md)

## Current protection boundary

The Phase6–7 development build supports explicitly enabled outbound connections, authorized files, verified downloads, private text/attachments and scoped offline copies. It does not add a phone listener, background content service, owner reindex command or bidirectional filesystem writer. App sandboxing is not whole-device protection, attestation or guaranteed erasure.

Production P-256 transport and AES-256-GCM sealing keys belong to AndroidKeyStore; no software identity/storage fallback or reset/rekey repair exists. OS-reported hardware backing is not attestation. Olm account/session material and age identities are software secrets protected by that seal, not hardware-held ratchet/file keys. Whole-state rollback resistance remains unproven.

## State locations

| State | Location / boundary | Support export |
|---|---|---|
| Setup, label, notification/motion preferences | DataStore; non-secret preferences | No labels/raw preferences |
| Bounded local-check/activity/URI grant ledger and enrollment anchor | Room schema3; no message/file plaintext or crypto keys | Counts/status only, not rows/URIs |
| Pairing/trust, file journal, download/export records, chat account/session/drafts/history/envelopes/receipts, attachment secrets/jobs, offline metadata/pins | Fsynced AES-GCM sealed aggregate in `noBackupFilesDir/connections-v1`; payload6 reads1–6 with same ownership/AAD | No |
| Ordinary private download chunks | `noBackupFilesDir/received-v1`, context-bound AES-GCM and opaque identifiers | No |
| Attachment ciphertext fragments | Separate `noBackupFilesDir/attachments-v1`, context-bound AES-GCM; never ordinary transfer metadata | No |
| Ordinary browse/search/details/pixels and unsaved private UI | Bounded foreground memory; no SavedState persistence | No |
| Explicit scoped offline cache and pin observations | Protected aggregate, quota-bound; metadata is not byte availability | No |
| Read-only SAF import | Temporary grant and bounded memory; no retained broad read/write authority | No |
| User-confirmed SAF export | External create-new inert document; provider can retain/share/upload it | No provider URI/content in diagnostics |
| Reviewed support reports | Bounded `cacheDir/support/`, temporary read FileProvider grant | Only selected redacted report |
| Work scheduling | WorkManager private database for local checks, not permanent content presence | No raw scheduler DB |

## Persistence and failure behavior

Reserve the Room PREPARING anchor before enrollment keys; seal/fsync/read back state, then bind READY. Partial/missing/damaged evidence cannot create a replacement identity. Payload migration retains key ownership and validates protected continuity/references, not merely a schema integer. Backups are disabled/excluded by the existing app policy, but actual backup/upgrade/OS-key behavior still requires native testing.

Chat persists before send/ACK; downloads/attachments persist ciphertext before checkpoints; adoption requires full re-verification. Journal unknown outcomes are retained, not erased by dismissal. Pins/source tombstones/grant loss do not destroy received copies. Quota exhaustion does not silently evict data.

Protected-storage errors disable networking. Do not manually edit protected state, clear data, delete keystore aliases or enable a fallback to “repair” an old enrollment. The current signer differs from Phase5; same-signer upgrade/migration is not qualified. Uninstall/clear-data loses identity/trust/history and is not lossless recovery.

## UI, logging, notifications and external windows

FLAG_SECURE is set before sensitive composition; owned consent windows are secure. Sensitive projections clear on lifecycle/tab/authority changes and do not enter SavedState/Room/logs. Attachments expose only minimal name/size/status UI from protected records. Generic notifications remain opt-in and do not include messages, filenames or keys. Diagnostic exceptions are converted to approved codes, not remote/provider details.

External SAF/scanner/provider windows and exported documents are separate boundaries. A selected provider may leave empty/partial/full uncertain output; NARADA cannot promise atomic publication, recall, physical durability or guaranteed stoppability. Memory/native zeroing is best-effort, not proof that all VM/OS copies are erased. See [attachment recovery](attachments-recovery.md) and [offline recovery](offline-recovery.md).

## Evidence limits

The [current validation](phase-6-7-validation.md) separates 796 development checks, static/native build provenance, 13 compiled instrumentation cases and **zero device/emulator executions**. JVM software crypto/vaults are test-only; their absence from the application DEX is checked. AndroidKeyStore/Conscrypt/Windows, hostile native providers, migration, power-loss, independent crypto/JNI/application/advisory/legal review and release approval remain open.
