# Phase 4 — desktop verified transfers

NARADA 0.4.0 extends the delivered desktop file workspace. Android/SAF remains deferred. The source is a **bounded development slice**, not a Windows-qualified release or large-file transfer claim.

> **Validation incomplete:** the one pass recorded 27 passing JavaScript tests but a failed Rust build. Targeted visibility/formatting corrections are not revalidated. Backend, process and browser suites did not run. The descriptions below are the implemented design, not demonstrated runtime guarantees. See [results](phase-4-validation.md).

## Implementation map

- `narada-protocol/src/transfers.rs`: strict owner/control DTOs, immutable manifest, typed receipt and size/index bounds.
- `0005_transfers.sql`: source reservations, receive checkpoints/commit intent, local jobs, cached offers and managed-directory identities. Migrations 0001–0004 are unchanged.
- `narada-files/src/transfers/`: snapshot, receive, checkpoint verification, no-overwrite commit/reconciliation, explicit cleanup and durable queue.
- `safe_fs` adapters: identity-checked create/write/unlink and private staging directories. Linux uses rooted `openat2`; Windows source uses pinned ancestors, reparse refusal, protected owner DACL at directory creation, and handle-based no-replace rename.
- `narada-connection/src/service/transfers.rs`: two outbound workers, approval polling, saved-endpoint pull offers and separate bounded binary routes.
- `desktop-ui/src/pages/transfers.js`, `components/transfer-dialog.js`, `lib/transfers.js`, `styles/app.css`: real queue, filters, progress, pause/cancel/retry, receive approval, offers, details and cleanup. Files inspector actions use actual object/version references.
- Native wrappers/build manifest/capability list/bridge parity: seven new owner commands; no unrestricted filesystem command or binary file content in UI IPC.

## Explicit limits

| Resource | Bound |
|---|---|
| One ordinary file | 64 MiB; zero-byte files supported |
| One binary chunk | 256 KiB; exact final-tail length |
| Manifest | ≤256 ordered chunk hashes; 32 hashes per page |
| Source reservation | 128 MiB per profile, including retained snapshots |
| Receive reservation | 128 MiB per profile, including approval-pending offers |
| Source / receive active entries | 8 per side; retained history capped at 128 per table |
| Local active queue | 8; latest/active window up to 24 jobs |
| Workers | 2 outbound jobs, independently 2 inbound binary lanes; existing bounded file admission remains 8 |
| Retained snapshot / receive validity | 24 hours; cleanup is explicit, not a blind orphan purge |
| Disk headroom | Declared reservation plus a 16 MiB safety margin |
| Control body / owner frame | Existing 16 KiB / 65,536 bytes; not enlarged for files |

These are allocation/admission rules, **not measured whole-process memory or throughput guarantees**. Source snapshots and receive staging are ordinary local files, not encrypted at rest. Existing full-disk encryption is an independent host control.

## Supported work

Download from an approved remote root into an explicitly chosen local registered root. Upload from an owner-selected local file to a remote Browse + Create root, with **additional receiver-owner approval** before allocation/bytes. For inbound-only peers, create a pull offer against an already granted Browse + Read root: the recipient checks the inviter over its saved outbound endpoint and chooses a local destination. No reverse endpoint or whole-root permission is guessed or silently granted.

The desktop dialogs target the top level of a selected approved root. The strict backend also accepts an existing directory object ID; raw paths are never transfer destinations. Directories, automatic archive extraction, overwrite, move-original, executable launch and recursive transfers are absent.

## Remaining qualification / limits

Native MSVC/Tauri/WebView/picker execution, DPAPI/ACL/share-mode behavior, real Windows namespace/durability semantics, power-loss and disk-controller cache faults, physical LAN/routing, high-contention performance and independent security review remain open. Linux cannot claim malicious same-user namespace-race containment. No installer is release-qualified. Retained ambiguous intents require owner investigation; there is no destructive “reset recovery” button.

See [protocol](phase-4-protocol.md), [workflow and recovery](transfers-and-recovery.md), [validation](phase-4-validation.md), and the broader [mechanism design](08-transfer-mechanism.md).
