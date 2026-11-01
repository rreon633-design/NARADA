# NARADA — Cryptography integration decision

> Development ADR · 30 October 2026 · Applies to source 0.7.0, not a production certification

## Selected libraries

| Purpose | Exact dependency / mechanism | Boundary |
|---|---|---|
| Message ratchet | vodozemac **0.11.1**, default features off | Standard Olm v1; account/session pickles transactionally protected. |
| Attachment stream | age **0.12.1**, default features off | Fresh X25519 recipient per attachment; standard age v1/STREAM format. |
| Local private aggregate | ring AES-256-GCM, random 96-bit nonces, domain/device/sequence AAD | Wrapped random master, ciphertext-only SQLite/WAL, durable sequence/hash guard. |
| Peer transport | Existing pinned P-256 mutual TLS 1.3 | Identifies the endpoint that supplies the signed E2EE bundle; not a substitute for message encryption. |

The maintained vodozemac repository describes its Olm implementation and links a historical Least Authority audit. That audit does **not** certify this exact dependency version or NARADA's integration. [1](https://github.com/matrix-org/vodozemac)

The age Rust documentation describes compatibility with the age v1 format, but warns that pre-1.0 releases are beta/testing-only. That warning is a **release blocker requiring explicit independent assessment**, not something hidden behind the word encrypted. [2](https://docs.rs/age/0.12.1/age/)

## Decisions that must not be misrepresented

- Standard Olm v1 has a truncated **64-bit message authentication tag**. The experimental session-config-v2 feature is not enabled. No post-quantum, 128-bit-message-MAC or Signal-protocol claim is made.
- No custom ratchet, attachment nonce schedule, TLS-only E2EE or plaintext history fallback exists.
- The transport identity and recipient are inside the signed bundle; both are checked against the authenticated paired endpoints. Changed pinned Ed25519/Curve25519 identities are refused, not reset.
- Ratchet advancement + immutable outbox envelope and authenticated inbox + receipt are protected aggregate transactions. No transaction result is released after an unconfirmed guard/database write.
- Library session/skipped-key limits and the aggregate size quota bound state. Key loss and ambiguous state fail closed.
- A 72-byte guard catches mismatched partial state. It cannot detect a matching rollback of the complete protected state, key wrapper and guard with the same identity. Backup/restore is not a ratchet reset mechanism.
- A post-pass correction retains the local public content-identity fingerprint in metadata and separates fresh enrollment from existing-only opening. This aims to block total-store-loss reinitialization; it is unrebuilt and untested, and is not a monotonic anti-rollback counter. See [correction details](desktop-5-9-corrections.md).
- Plaintext exists in authorized process memory and the owner UI. This does not protect a compromised endpoint, an authorized owner, administrator memory access or a malicious recipient who has already received content.

## Review before release

An independent reviewer must examine bundle-to-transport binding, TOCTOU/lease boundaries, Olm session selection/simultaneous initiations, MAC strength, retry/dedup windows, cancellation and commit interruption, key provisioning, memory exposure, export publication, retention dependencies, upstream advisories and cross-implementation attachment compatibility. The project owner must approve dependency/license policy. No such approval is fabricated in this source handoff.
