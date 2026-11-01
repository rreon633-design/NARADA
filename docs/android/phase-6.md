# Phase 6 — two-way encrypted attachments

[Handoff](../../ANDROID-PHASE6-7-HANDOFF.md) · [Crypto decision](attachment-crypto.md) · [Protocol](attachments-protocol.md) · [Workflow](phase-6-7-workflow.md) · [Validation](phase-6-7-validation.md)

## Delivered development scope

The user explicitly authorized **two-way support and a versioned desktop upload extension**. The phone remains an outbound client: desktop→phone is a phone-initiated fetch; phone→desktop is a phone-initiated upload. Existing desktop source/evidence is preserved separately, not silently replaced or described as revalidated.

- Temporary **read-only SAF import**, one selected ordinary document, ≤8 MiB. No persisted import grant, raw path resolution or executable preview.
- Fresh age X25519 identity for every encryption, immutable ciphertext, protected descriptor/key/name/plaintext hash. Interrupted encryption is abandoned, never resumed by reconstructing a key/nonce stream.
- Atomic protected commit of local receipt and typed Olm chat body; manifest delivery is separate from byte upload. UI and engine require an actually committed manifest before upload. Reopening chat/receipts refreshes UI only; it never sends bytes.
- Version-1 capability negotiation, bounded begin/status/chunk/finish, exact immutable retries, durable bitmaps and restart-paused recovery. Four retained attachment jobs; no silent eviction.
- Ciphertext length/hash and every age authentication step, then plaintext length/hash, precede availability. A checkpoint/message receipt is not a full byte receipt.
- Separate, explicit create-new `application/octet-stream` SAF export; verify-before-create, durable intent/URI, readback, uncertain-outcome handling and a shared bounded provider worker.
- Monochrome native Compose views, lifecycle-cleared private presentation and secure, scrollable consent with reachable ≥48 dp actions. Host UI evidence is synthetic, not device qualification.

## Task map and measured success criteria

| Task | Implementation / evidence |
|---|---|
| AND-601: compatible age adapter | `native-age`, `AgeCryptography.kt`; 12 Rust host tests and four-ABI builds; independent review still open |
| AND-602: protected typed manifest | Olm bridge 2, `ChatBody.attachment`, preserved old text digest, typed attachment digest; 20 Olm Rust tests |
| AND-603: import + immutable staging | `AttachmentsEngine`, `DocumentsImportProvider`, picker binding and protected storage tests |
| AND-604: negotiated two-way transport | `AttachmentsClient`, authorized Rust upload extension; 12 actual-peer attachment cases |
| AND-605: explicit verified export | `AttachmentExport`, shared provider quarantine and consent; host fault/provider tests |
| AND-606: privacy + recovery UI | `AttachmentsCoordinator`, panel and guarded picker; host lifecycle/save-state/large-type tests |
| AND-607: source-bound artifact handoff | Combined 796-check campaign, clean error/warning lint, both APK builds and static/native-provenance audit |
| AND-608: native/independent qualification | **Open**, not represented as complete by the above checks |

The full combined campaign and failed iterations are in [validation](phase-6-7-validation.md). This is **development implementation complete**, not independent crypto/storage/legal/advisory approval or a production release.

## Deliberate exclusions

No ordinary-file upload, phone listener, permanent/background presence, automatic send/fetch/open/export, owner reindex API, auth downgrade, crypto reset or key recovery fallback. Existing verified/exported copies cannot be recalled by remote revocation. See [precautions](attachments-recovery.md) and [native tasks](phase-6-7-device-checklist.md).
