# NARADA — Security, trust and privacy

> **Current implementation:** desktop phases 1–3 have a development implementation; Android and native Windows/LAN/filesystem/security qualification remain open. See [Phase 3 implementation](phase-3-implementation.md), [actual file contract](phase-3-protocol.md), and [live tracker](../plan.md). The wider security below includes future features, not claims that those features are shipped.

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Assets and trust boundaries

Protect original files, private messages, attachment keys, transport identities, E2EE session state, shared-root grants, durable jobs and local diagnostic data.

| Boundary / adversary | Required control | Remaining limit |
|---|---|---|
| Untrusted LAN participant | TLS, paired-key verification, authentication and rate limits | Traffic timing, approximate volume and discovery presence can remain visible. |
| Malicious or lost paired phone | Per-operation/per-root grants, quotas and revocation | Previously downloaded files cannot be remotely erased reliably. |
| Hostile filenames, metadata or documents | Strict schema/path checks, safe rendering and constrained previews | Opening content in another app inherits that app's risks. |
| Another Windows user | Per-user storage ACLs and authenticated local IPC | Administrator access or a compromised OS is outside the confidentiality guarantee. |
| Compromised UI/content renderer | Narrow Tauri capabilities and domain-side checks | Same-user native malware can attack the endpoint; E2EE is not antivirus. |
| Interrupted network/process/storage | Durable journals, integrity checks and recovery state machines | Hardware may misreport durable writes; test and document the storage assumptions. |
| Malicious update/dependency | Signed artifacts, pinned dependencies, SBOM and review | A compromised release key requires a separate recovery process. |

**E2EE scope:** Android's chat endpoint and Windows' chat endpoint encrypt/decrypt their own conversation. The Windows agent includes the chat endpoint and is trusted with its keys. Network routers and any future ciphertext-only relay are not intended recipients. NARADA does not claim that a compromised endpoint or the recipient cannot read messages.

## Capability and authorization model

Permissions are scoped by `(peer_device_id, root_id, grant_revision)` and checked in the domain service, not only in routing middleware.

| Capability | Permits | Does not imply |
|---|---|---|
| `browse` | Authorized names and metadata | Reading file contents. |
| `read` | Download/preview authorized content | Upload, rename or delete. |
| `create` | New files in the allowed destination | Replacing an existing file. |
| `modify` | Version-checked replacement and rename | Permanent delete or access outside the root. |
| `trash` | Confirmed reversible removal under retention policy | Arbitrary OS deletion. |
| `sync_read` | Journal/snapshot access and selected-content refresh | Two-way synchronization. |
| `chat` | One-to-one E2EE exchange with this endpoint | Browsing all host files. |
| `attachment` | Sending/receiving permitted attachment blobs | Bypassing file-root permissions to create a source attachment. |

- Default profile: `browse + read + sync_read`; chat/attachments are explicit device-level choices.
- Root selection, grant editing, pairing approval and revocation are **local owner actions**, not normal phone API permissions.
- Revocation increments the grant/session epoch, closes connections, cancels leases and rechecks authorization before publication.
- Long transfers check revocation/cancellation between bounded chunks. Data already in flight cannot be “unsent.”
- File metadata, search, thumbnails, events and error responses use the same permission filter as full-file access.
- A path supplied by an authorized caller is still hostile input.

## Pairing bootstrap and normal authentication

**Recommended design:** short-lived, invitation-only bootstrap TLS listener plus a normal mutually authenticated TLS listener. Final certificate validation and activation behavior must pass ADR-03 before release.

1. The local Windows owner chooses **Pair device** and a proposed least-privilege profile.
2. Windows creates a cryptographically random invitation secret, proposed minimum 256 bits, and stores only its verification hash and bounded session metadata.
3. A QR contains the protocol major version, invitation ID, candidate local endpoints, host public-key fingerprint, invitation secret and a short expiry. Proposed TTL: 120 seconds.
4. Android validates payload size/schema and connects using TLS pinned to the QR's host key. Candidate addresses and advertised names are not identities.
5. The temporary listener exposes **only pairing operations**, not file/chat/diagnostic APIs. The token is sent in the encrypted request body, not in a URL or log.
6. Atomic token claiming reserves the invitation for that attempt and device certificate. Reuse by a different attempt fails. Restart invalidates uncompleted invitations.
7. Windows displays the device name, key fingerprint and requested grants for explicit approval. A label such as “My phone” is not proof of ownership.
8. Before activation, the phone proves possession of its corresponding private key through the reviewed TLS client-authentication flow. A submitted public certificate alone is insufficient.
9. A pending certificate, if temporarily accepted to finalize pairing, is restricted to that finalization operation. It cannot access protected content.
10. Both sides persist trust state; retries of the same pairing attempt return its status rather than creating another device. Unconfirmed records expire.
11. The bootstrap listener closes when pairing ends. Subsequent sessions use paired transport identities, not the invitation secret.
12. E2EE identities/prekey material are initialized using the selected protocol and bound to the authenticated pairing relationship. Unexpected E2EE key changes require user review.

For self-signed/local certificates, use a reviewed **pin-aware verifier** that validates the expected key and TLS proof of possession. Never deploy an “accept all certificates” callback to make local IP addresses work. DNS names, IP addresses and certificate display labels are not substitutes for the paired key.

Disable TLS early data/0-RTT for application operations in 1.0. An unpaired manual IP entry may begin explicit pairing, but never silently trusts an unknown certificate.

## Keys, encrypted storage and identity replacement

- Use different key roles for transport identity, E2EE protocol identity, database encryption and attachment content.
- Windows secrets use the signed-in user's protected storage context; Android uses Keystore-supported keys or wrapping for library-required key material.
- Do not assume every E2EE algorithm is directly supported by a non-exportable Android Keystore key.
- Protect drafts, outbox bodies, decrypted history, ratchet state, attachment keys and sensitive thumbnails—not just messages while on the wire.
- Keep cryptographic session updates and related inbox/outbox records in the **same transactional store** required by the selected library.
- If the protected store cannot be opened, stop the affected feature. Never replace it with plaintext storage or silently generate a new identity.
- Ordinary app restart keeps identity. App-data deletion, keystore invalidation or intentional identity reset requires a documented re-pairing flow.
- Transport-key rotation requires authenticated continuity or re-pairing. Do not accept key changes merely because the device name/IP matches.
- A new device does not automatically recover past messages. Backup/recovery of content is a separate feature from trust/session recovery.

## Filesystem containment and hostile input

The supported 1.0 host baseline is a qualified local filesystem, initially NTFS. Network shares, unusual providers and reparse-heavy roots need separate qualification.

- Accept opaque `root_id` and object IDs or validated relative paths, never arbitrary absolute/UNC/device paths.
- Reject traversal, alternate data stream syntax, unsupported path namespaces, reserved names and unsafe trailing characters.
- Validate case-insensitive collisions, long names, Unicode display ambiguities and ancestor relationships.
- Do not implement containment as a string prefix check: `C:\Share2` is not inside `C:\Share`.
- Canonicalize-then-open alone is vulnerable to races. Use reviewed handle-based resolution and revalidation at mutation/commit boundaries.
- Reject unsupported reparse points, junctions and symlink traversal by default. Consider multiply linked files explicitly; do not mutate existing content in place through unchecked hard links.
- Stage new content in a controlled location on the destination filesystem. Never use a client-supplied temporary path.
- Retain the original application's permissions policy; do not let uploaders supply arbitrary NTFS ACLs.
- Treat archives as files in 1.0. Do not automatically extract untrusted archives into shared roots.

## Privacy commitments and limits

- No content telemetry, advertising identifier, mandatory analytics or remote account service.
- Optional update checks are separate, visible and disableable; core operation must survive blocked internet egress.
- Discovery advertises a generic service instance and minimal version hints—not file lists, full usernames or identity secrets.
- Notification body previews default to off. Opening a conversation may reveal plaintext on that device by design.
- Diagnostics exclude message bodies, tokens, keys, content URIs, full local paths and QR payloads. Users inspect an export before sharing it.
- Treat logs, databases, WAL files, crash dumps and temporary plaintext as sensitive artifacts.
- A user-selected SAF provider can itself be cloud-backed. NARADA must not describe content routed through that provider as necessarily local-only.
- Secure deletion cannot be guaranteed on SSDs, backups or third-party providers. State the limitation rather than promising forensic erasure.
