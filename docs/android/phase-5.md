# Phase 5 — durable private text messaging

[Handoff](../../ANDROID-PHASE5-HANDOFF.md) · [Index](README.md) · [Measured validation](phase-5-validation.md)

## Status and success criteria

The **development implementation and available host/actual-peer evidence** are delivered. The roadmap's independent crypto/storage review is still an **open acceptance/release gate**, not an implied approval of this code. Android-compatible compilation/binding feasibility is demonstrated; native loading, platform storage behavior and security qualification are not.

| Work package | Delivered mechanism | Evidence / remaining gate |
|---|---|---|
| AND-501 | Locked Olm v1 adapter, strict bounded JNI, host plus four Android ABI builds |12 host Rust tests and static binary provenance; independent review/device execution open |
| AND-502 | Explicit account enrollment; signed transport-bound bundles and retained pins | Core and actual-peer negatives; no reset on damaged/missing state |
| AND-503 | Protected aggregate/account/session continuity validation; payload 4 reads1–4 | Protected-store host migration/tamper/failure tests; no rekey; whole-state rollback unproven |
| AND-504 | Persist ratchet/envelope before send; exact request/outcome retention | Transaction/failure/restart cases; no hidden resend or fresh-ciphertext retry |
| AND-505 | Persist ratchet/inbox/receipt before ACK; duplicate re-ACK | Actual desktop two-way text/lost response/restart/failure cases |
| AND-506 | Private history, explicitly sealed drafts, bounded foreground batches | Lifecycle/UI/authority host tests; real IME/insets/TalkBack still open |
| AND-507 | Generic notification, default off; no message/sender/IDs in payload | Two new notification host checks; OEM delivery/privacy still open |
| AND-508 | Split docs, dependency notices, CI source and artifact-bound receipts |581 passes;177 matched inputs; independent/native/release sign-off not granted |

## Product features

Select an approved desktop, explicitly enable protected text messaging and load the private view. Compose a text of at most4096 UTF-8 bytes, save an encrypted draft or queue and explicitly sync. History shows saved delivery receipts separately from delivery-unconfirmed/cancelled outcomes. Review exact retry and cancellation rather than inventing delivery or undoing already admitted network work.

Saved local history is separate from network eligibility: going offline cannot manufacture a new remote grant, but does not erase previously saved data. Re-pairing requires renewed consent for the binding; earlier-scope messages remain retained and are not replayed under new authority. Key changes never silently replace retained pins.

The five tabs and monochrome styling remain native Compose/Material3.48dp controls, scrolling and200% font fixtures retain the reference direction. The original mobile screenshot and `IFB_Android_Mobile_All_Screens.html` remain unchanged design inputs. Synthetic host renders19–22 are evidence fixtures, **not production chats or device screenshots**.

## Capacity and scope

- 32 retained messages across up to four chat bindings; two sessions/peer; no silent eviction.
- 4096 UTF-8 bytes/text, not4096 characters. Unicode is supported; controls other than LF/TAB are refused.
- 2 MiB protected aggregate budget; the message count alone does not guarantee space.
- At most three old receipt ACKs, three eligible sends and three pulls per explicit batch.
- At most three admitted send attempts with four-second spacing. Exact retry requires review and never bypasses the retained budget/scope.
- No background receive/push service, permanent presence, phone listener, cloud, uploads, automatic sync or attachment support.

## Metrics that actually passed

581 final host/interop checks =12 Rust +22 domain +299 connection +43 actual-peer +205 app host.102 new versus Phase 4. Lint0 errors/warnings; both development APKs built.177 measured inputs agree across final stage records. Four Olm ABI libraries are statically inspected; no Android execution. Ten instrumentation cases are merely compiled.

No latency, battery, sustained-runtime, physical accessibility, hardware-key attestation or production-readiness metric is asserted. See the [native checklist](phase-5-device-checklist.md).

## Retained earlier behavior

[Authorized files](files-protocol.md), [download checkpoints/receipts](transfers-protocol.md) and [separate create-new SAF export](saf-export.md) retain their prior scope. The mutation journal remains32 records; four downloads/128 MiB,64 MiB/file,256 KiB/chunk and two export records remain bounded. Received private copies can survive remote revocation; revocation cannot erase offline copies. Existing unknown file/export outcomes are not silently evicted by messaging.

Phase 6 attachments, Phase 7 pins/refresh, Phase 8 native qualification and Phase 9 release remain separate.
