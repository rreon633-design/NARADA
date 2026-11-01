# NARADA — Phase 6: encrypted attachments

> Source 0.7.0 · [Chat](phase-5-chat.md) · [Cryptography](crypto-content-decision.md) · [Recovery](content-recovery.md)

## Send an attachment

Select an ordinary approved local file in **Files → Attach in Chat**. A bounded RAM snapshot validates the source identity/version before and after reading. The source is not modified. No temporary plaintext source cache is written.

The age library generates a fresh X25519 identity for each encryption attempt and writes the standard streaming format to immutable private ciphertext. The secret identity, original name, complete plaintext hash/length and ciphertext manifest are included **inside the E2EE message**, never the ordinary transfer source table.

A cancelled/interrupted encryption attempt is not resumed by reconstructing nonce state. A retry uses a fresh key and opaque blob ID. Unknown leftover ciphertext is preserved, counted against physical quota and never adopted as a valid attachment.

## Receive, authenticate, export

Message delivery does not fetch the attachment automatically. The owner chooses **Download encrypted file**. A separate bounded binary lane sends only ciphertext to the intended paired peer, with current chat and attachment consent checked on every request.

Chunks are checked against the private manifest, made durable, then checkpointed in the protected store. Restart pauses work; explicit resume revalidates acknowledged chunks. Availability requires all chunks, complete ciphertext length/hash, all age stream/final authentication checks, and full plaintext length/hash.

Only **Export verified copy** can publish plaintext. It uses the existing approved-root, no-replace transfer publication mechanism. The export operation/destination binding is retained so uncertain responses can be retried. A known no-publication filename collision can release the binding; an unknown publication outcome cannot. Existing files and ambiguous recovery data are preserved.

Exports are inert. There is no automatic program launch, document rendering, archive extraction or extension-based execution. Ordinary file-transfer metadata and catalog records can describe an explicitly exported owner file; this is distinct from a private, unexported attachment.

## Bounds and retention

| Resource | Development bound |
|---|---|
| Plaintext attachment | 8 MiB |
| Ciphertext | Plaintext limit + 65,536 bytes |
| Chunk | 256 KiB, separate binary endpoint |
| Private ciphertext disk usage | 128 MiB, including untracked orphans |
| Ciphertext directory entries | 256 |
| Outbound bulk lanes | 2 shared by ordinary transfers and attachments |
| Inbound bulk lanes | 2 shared; control remains separate |
| Eligible ciphertext retention | 7 days; explicit cleanup |
| Terminal history eligibility | 30 days; bounded deduplication retention |

Pending outgoing references, active downloads and unresolved export bindings are protected from cleanup. Published files are never deleted by ciphertext cleanup. Encryption/decryption uses streaming-format APIs over bounded RAM buffers; this is **not** an arbitrary-size, constant-memory file implementation.

## Task mapping

NAR-047–050: immutable snapshot/encryption, authenticated bounded resume, integrity checks, separate message/file UI and explicit safe export are supplied. Native Windows durability, age interoperability with a separately implemented client, independent integration review and Android provider behavior remain open qualification gates. Upstream age 0.12.1 is pre-1.0; the release gate explicitly records this risk.
