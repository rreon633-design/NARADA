# Phase 4 — end-to-end workflow, tips and troubleshooting

[Setup](../../apps/android/README.md) · [Pairing](pairing-workflow.md) · [Mechanisms](transfers-protocol.md) · [Precautions](transfers-recovery.md)

## Receive a checked private copy

1. Use a fresh disposable Android test profile; the supplied APK does not share the earlier debug signer. Complete setup and explicitly enable Connections.
2. Pair with the authorized desktop: single-use invitation, desktop approval, fingerprint comparison, saved ACK/health. Use an allowed private IPv4 Wi-Fi/Ethernet route. No cloud/public/cellular fallback exists.
3. On Files, choose a real granted ordinary file at most 64 MiB. Review its actual source/name/size/version and confirm download. A thumbnail is not a received copy.
4. The sealed intent is saved first. In the foreground, Transfers prepares that exact snapshot, verifies all manifest pages and stages encrypted chunks. Its displayed progress is locally checked durable progress, not a demo timer or desktop progress field.
5. Pause or cancel explicitly as needed. Leaving the app/background or losing authority stops eligible network work. After restart, use **Resume** deliberately; the original identity/version remains fixed.
6. A private **Verified** result means full private bytes were rechecked and the receipt saved. Finish acknowledgement is independent. If all chunks were staged but the final receipt was interrupted, use explicit local verification rather than fetching a replacement snapshot.

Transfers has no permanent background service. Screen/ViewModel lifetime does not own sockets or durable acceptance. Retained verified copies are available for explicit local verification/export even when the desktop is offline; new network reads still require current authority.

## Publish a separate copy

1. Choose a received copy and review the provider/retention/partial-file warning.
2. Request a new export. The app records its intent and verifies private bytes before creation.
3. Select a directory in the system picker with temporary read and write authority. This is separate from Storage settings and desktop grants.
4. NARADA validates the directory, bounded collision snapshot and fresh returned document, records its URI, writes without truncating another document, closes, independently reads back and verifies exact size/full hash.
5. A verified export receipt is not encryption of the external destination or proof of physical durability. Keep/review/remove that provider copy using the provider's own controls.
6. If the outcome is uncertain, inspect the destination yourself. Do not assume a failed reply means no file. Dismissal remains unconfirmed; retry means new consent and a fresh target, not rewriting the uncertain document.

## Problems → safe response

| Symptom | Meaning / response |
|---|---|
| Unsupported file / over 64 MiB | This bounded slice does not accept it; do not bypass the guard or treat preview as download |
| Queue capacity reached | Review/remove an unwanted retained record/private copy explicitly; no silent eviction |
| Stale/expired immutable snapshot | Original source cannot be substituted; review the old attempt, refresh actual Files metadata and explicitly choose a new transfer if desired |
| Permission withdrawn / peer revoked | New remote results are discarded; already received copies cannot be recalled |
| Paused after restart | Expected; re-establish authorized connectivity and explicitly resume, or verify fully staged bytes locally |
| Local copy changed / hash mismatch | No verified success; preserve evidence, investigate storage/key/integrity state; never repair by silently overwriting a receipt |
| Storage full / fsync/link failure | Progress is not acknowledged past durable installation; free space through deliberate action and retry the supported same-source recovery |
| Provider busy / timeout | The single worker may be quarantined; do not spawn parallel retries or assume the provider stopped |
| Export uncertain | A destination may be empty/partial/full; review externally, acknowledge uncertainty, then start a new export only with new consent |
| Export history full | Review/dismiss the unresolved entry as appropriate, then explicitly forget an eligible terminal record; this does not delete a document |
| Protected-storage blocked | Networking stays off. Preserve data; do not clear data, reset/rekey, or use remote errors to bypass the gate |
| APK certificate conflict | Expected against the Phase 1–3 signer; use a fresh test profile or a compatible controlled signer, not destructive “repair” |

## Engineering tips

Use the sequential small-host commands in the [project README](../../apps/android/README.md). Lint needed a 640 MiB heap; a 384 MiB run was terminated after GC thrashing, not reported as a pass. Test-only software identities and synthetic UI/provider fixtures must never become production fallbacks. Use the actual Rust interop task, not historical desktop test campaigns.

For validation, choose a new `--record` name; never overwrite old evidence. The final audit rejects filtered tests, absent/zero suites and stale source hashes. The full historical audit needs the preserved workspace/containers; the source archive intentionally contains selected receipts, not every old desktop delivery. Consult [evidence preservation](../../artifacts/android-phase-4/README.md) before restoring archived logs; restoring everything expands disk/snapshot use.
