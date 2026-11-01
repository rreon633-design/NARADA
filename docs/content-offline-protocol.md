# NARADA — Content and offline protocol surface

> Application 0.7.0 · Owner RPC 1 · File/transfer/content/sync protocols 1

## Owner-only commands added

| Group | Commands |
|---|---|
| Private status/history | `content_status`, `list_conversations`, `chat_history` |
| Message controls | `send_chat`, `save_chat_draft`, `mark_chat_read`, `chat_control` |
| Attachment controls | `fetch_attachment`, `pause_attachment`, `export_attachment`, `cleanup_content` |
| Offline controls | `sync_query`, `list_pins`, `create_pin`, `pin_control`, `refresh_sync` |
| Support | `support_bundle` |

All 17 have typed protocol, native Tauri wrapper, invoke registration, build permission, main-window capability and JavaScript bridge source. The full native surface is 50 commands including the existing native root chooser. Static parity is not native execution.

Owner IPC remains 65,536 bytes. History is ten rows/page. Sensitive request Debug implementations redact private text. No browser persistence stores messages, drafts or private manifests.

## Paired network routes

- `POST /api/v1/chat`: signed bundle, encrypted push, authenticated pull, receipt ACK. Chat consent required.
- `GET /api/v1/blobs/chunks/{uuid}/{canonical-index}`: exact binary ciphertext chunk; both chat and attachment consent, peer ownership and current lease required.
- `POST /api/v1/sync`: scoped materialized snapshot or journal page. Browse, Read and Sync read required.
- Existing `/api/v1/transfers` adds `prepare_sync`; its source records persist the additional capability requirement. Regular `prepare` keeps its existing idempotency tuple.

No owner, key export, arbitrary path, settings, history or support-bundle endpoint is forwarded remotely. Bootstrap sessions have no content access. Control bodies remain 16 KiB. Sync/file metadata responses have their existing separately bounded allowance; bulk content is never base64-stuffed into a globally enlarged frame.

Binary framing reuses the strict transfer version/length/content-type/hash headers, forbids compression and checks the immutable encrypted manifest. Two bulk lanes provide backpressure; chat/control is separately bounded. Connection rate/lifetime and peer trust checks remain active.

Health advertises the exact ordered extension list `health`, `permissions`, `files.v1`, `transfers.v1`, `chat.olm.v1`, `attachments.age.v1`, `sync.v1`. Legacy connection/file/transfer lists are recognized, but absent new operations fail explicitly—no crypto or sync downgrade occurs.

## Errors are not completion

Key change/store lock requires review. A failed/deferred network write is not delivery. `CURSOR_EXPIRED` means take a new snapshot, not trust partial metadata. An unknown publication result is not a failed export that may be rebound to another file. A stale pin job is not relabeled as bytes of a new source version. Known peer error codes are mapped to fixed messages instead of forwarding arbitrary error text.
