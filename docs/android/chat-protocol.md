# Android chat — actual desktop protocol and mechanisms

[Crypto decision](chat-crypto.md) · [Recovery rules](chat-recovery.md) · [Workflow](phase-5-workflow.md)

## Transport and authority

Use the existing outbound, private-IPv4, Wi-Fi/Ethernet, network-bound TLS1.3/mTLS connection to the approved desktop. The phone has no chat content server or owner API. Public/loopback/cellular/VPN routes, phone-side DNS fallback and insecure TLS verification remain refused. Loopback is used only by host fixtures.

The desktop must grant chat and advertise **`chat.olm.v1`**. The client uses version 1/hello and **`/api/v1/chat`**, with strict snake_case messages whose `kind` is `bundle`, `push`, `pull` or `ack`. Parsing retains bounded sizes/nodes/depth. Chat wire ceiling is16384 bytes; it is not the2 MiB sealed-store budget.

The ConnectionsCoordinator owns the socket and serializes the content lease, cancels/joins health before content and resumes it only when eligible. Pending pairing blocks content. Files, Chat and Transfers share300 ms production admission. This does not increase the desktop's unchanged12 TLS-starts/s admission limit; other clients/native scheduling can still exhaust it. No authentication retry/downgrade is introduced.

Pre/post authenticated health and pairing/epoch/foreground checks fence results. A response under changed authority is discarded. An already admitted remote effect cannot be undone by coroutine cancellation.

## Bundle and key binding

The signed `NARADA-CHAT-BUNDLE-1` tuple binds version, transport ID, recipient, Curve25519 identity, Ed25519 identity and one-time key. Canonical padded standard Base64 and signature/key relationships are checked. A peer bundle must match the current authenticated transport identity and intended local recipient.

Retained pins do not rotate silently. Enablement is explicit; re-pairing requires renewed binding consent, retaining history/pins. An old-pairing outbox entry is not sent under a new pairing. Two sessions/peer and four bindings are hard retention limits, not an eviction policy.

## Envelope and inner message

The declaration-order SHA256 digest covers `version`, `message_id`, `sender`, `recipient`, `session_id`, `message_type`, `ciphertext`. Reordering/reconstructing a different representation is not a new idempotency rule. IDs and Base64 are canonical; sender/recipient/session and transport-pinned keys must agree.

The decrypted inner `NARADA-CHAT-1` message repeats the identifiers and contains text plus the attachment field. Inner/outer binding is checked before persistence. Text is at most4096 **UTF-8 bytes**, with only LF/TAB allowed among controls. **Any attachment payload is unsupported in Phase 5**: it is neither committed nor ACKed as a successful text. Phase 6 must add its own reviewed age-format contract.

## Outbox transaction

```text
explicit queue consent → sealed QUEUED text/id/pairing intent
  → validate/pin signed peer bundle and choose exact v1 session
  → compute candidate ratchet + encrypted envelope
  → atomically seal ratchet + exact envelope/digest as READY
  → durably admit run/attempt before network push
  → push the retained envelope, not re-encrypted text
  → validate matching receipt → seal DELIVERED
```

A durable failure prevents send. A lost push response produces delivery-unconfirmed state rather than automatic new encryption. A reviewed retry uses the same ID, pairing, envelope and digest. Attempt/run metadata is protected; batch/retry limits apply.

## Inbox transaction and receipts

```text
explicit pull → check authority + signed key/session/inner-outer binding
  → decrypt into candidate state
  → atomically seal advanced ratchet + history + exact receipt
  → ACK only the durably retained receipt
```

A failed inbox save sends no ACK and does not commit a candidate ratchet. Replaying the same retained ID/peer/digest returns the same retained receipt without another decrypt; a conflict is refused. A lost ACK response leaves the local receipt durable for a later explicit re-ACK. Incoming history stays separately readable from its remote ACK status.

A receipt binds IDs/digest, delivery state and RFC3339 `committed_at`. The desktop's exact ACK is idempotent; an altered receipt is not accepted. `DELIVERED` means a saved protocol receipt, not that a human read the message. Local “mark read” does not send a read receipt.

Desktop retention is128 messages/32 bindings/two sessions; its send policy includes three attempts,24-hour action-required aging and four-second spacing. Android retains32 messages/four bindings/two sessions. **Finite retained receipts are not an infinite replay guarantee.** No claim of persistence beyond the specified stores/retention bounds is made.

## Tested peer and scope

The43-case final actual-agent campaign contains34 earlier connection/file/transfer cases and nine chat cases: Unicode/two-way replies, lost push/ACK, exact retry, restarts, withdrawn grant, failed inbox persistence,4096-byte boundary and unsupported attachment. It uses the unchanged desktop source and an explicitly enabled **Linux test development vault**, not Windows DPAPI or an Android software-key fallback. Host TLS/JNI is not native platform proof.
