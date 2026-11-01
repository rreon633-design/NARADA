# NARADA — Product definition and release scope

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Problem and objective

People repeatedly move documents, photos and work files between a Windows computer and Android phone. Cables, cloud uploads, messaging services and ad hoc network shares add friction, dependence on internet access, or unclear permissions.

NARADA brings these activities into one controlled local experience: **connect securely, access only approved content, move it reliably, and communicate privately**.

## Initial users and jobs to be done

| User/context | Job | Desired outcome |
|---|---|---|
| Student or professional | Retrieve a laptop document from a phone | Find the right version without a cloud upload. |
| Creator | Move a large media file | Resume after interruption without corruption. |
| Privacy-conscious personal user | Exchange notes and attachments | No mandatory account or third-party storage. |
| Traveler or low-connectivity user | Open selected files without the laptop | Predictable pinned content and visible freshness. |
| Shared-network user | Pair on an untrusted LAN | Other network participants cannot browse or impersonate devices. |

The initial trust model is personal devices, not enterprise multi-tenancy. A “trusted device” still receives only explicitly granted capabilities.

## Release boundaries

| Milestone | Includes | Explicitly not promised |
|---|---|---|
| M0 — internal vertical slice | Two real devices, authenticated connection, one safe folder and one verified file | Production security, unattended background behavior, full UI or release installers. |
| M1 — Transfer Preview | Pairing, permissions, browsing, upload/download, resume, recovery, lifecycle handling and diagnostics | Full NARADA 1.0 chat/sync feature set; present it as a preview. |
| M2 — NARADA 1.0 beta | M1 plus E2EE chat, encrypted attachments, pin/unpin and selected-content refresh | General two-way sync, universal SAF semantics or uninterrupted mobile background presence. |
| M3 — NARADA 1.0 stable | Beta scope plus all hardening, support and release gates | Unsupported network transports and platforms. |
| Later releases | Multi-device management, richer file operations, two-way sync and advanced UX | Delivery dates until separately estimated and approved. |

If the E2EE feasibility gate fails, do not ship ordinary TLS chat labeled E2EE. Either resolve the dependency or formally release only the clearly named Transfer Preview.

## Non-goals for 1.0

- Public-internet remote access, cloud backup or relay operation.
- Public profiles, contact discovery, account registration or monetization infrastructure.
- Group chat, calls, screen sharing or remote desktop.
- A general Windows drive/filesystem provider.
- Automatic two-way folder editing or destructive deletion mirroring.
- Universal media transcoding, executable previews or automatic archive extraction.
- Linux, macOS or iOS clients.
- Enterprise administration, cross-user Windows hosting or a plugin marketplace.

## Product-level invariants

- A network-discovered endpoint is never automatically trusted.
- A paired device never gains access to an unapproved root.
- A partial or unverified file is never labeled complete.
- Retrying a completed logical operation does not create another committed result.
- A conflicting version is preserved unless a user explicitly resolves it.
- A message is never marked delivered merely because bytes were written to a socket.
- Revocation stops future authorized access but cannot erase copies already received.
- The system never promises live communication when neither endpoint can reach the other.
