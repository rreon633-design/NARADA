# NARADA — Complete features list

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

**Priority key:** P0 = NARADA 1.0 requirement; P1 = next-release candidate; P2 = advanced/experimental; P3 = long-term. P0 is broader than the Transfer Preview. Every P0 feature needs tests and the relevant phase gate.

## Trust and connectivity

| ID | Feature | Priority | Acceptance boundary |
|---|---|---|---|
| F01 | Installation-specific device identity | P0 | Private material is OS-protected; identifiers alone never authenticate. |
| F02 | QR invitation and confirmation | P0 | Short-lived, single-use secret; pinned host identity; explicit local approval. |
| F03 | Mutual session authentication | P0 | Each normal connection proves possession of paired keys. |
| F04 | Device list, rename and last seen | P0 | Shows trust, permission profile and actual connection state. |
| F05 | Immediate local revocation | P0 | Closes sessions and rejects subsequent work/commit checks. |
| F06 | Per-root capability profiles | P0 | Read, create/upload, modify and trash are independent grants. |
| F07 | mDNS/DNS-SD discovery | P0 | Minimal public metadata; results treated as untrusted candidates. |
| F08 | Manual IP/port fallback | P0 | Still verifies paired identity; does not bypass network isolation. |
| F09 | Reconnect and network-change handling | P0 | Bounded backoff, no duplicate jobs, no identity downgrade. |
| F10 | No-internet LAN operation | P0 | First-use test after offline-capable installation passes. |
| F11 | Connection/firewall diagnostics | P0 | Explains discovery, routing, identity and permission failures separately. |
| F12 | Fingerprint/identity-change screen | P0 | Unexpected key changes stop automatic connection. |
| F13 | Multiple Android peers | P1 | Independent permissions, quotas and conversation/session state. |
| F14 | Guided hotspot setup | P1 | Only on qualified device combinations; no silent network changes. |
| F15 | UDP discovery alternative | P2 | Added only if evidence shows benefit beyond mDNS/manual entry. |
| F16 | Wi-Fi Direct, USB networking, QUIC | P2/P3 | Independently qualified adapters; never assumed to work everywhere. |

## File browsing and management

| ID | Feature | Priority | Acceptance boundary |
|---|---|---|---|
| F17 | Approved-root picker and root removal | P0 | Local Windows action only; removal cancels access and watcher work. |
| F18 | Paginated folder listing and breadcrumbs | P0 | No absolute host paths exposed; no unbounded enumeration. |
| F19 | Search, sort and metadata details | P0 | Authorized roots only; index freshness is visible. |
| F20 | Accessible list view and multi-select | P0 | Keyboard/screen-reader support and bounded bulk selection. |
| F21 | Safe image preview | P0 | Size/pixel limits; no script execution; lazy generation. |
| F22 | Rename and conflict-safe upload destination | P0 | Requires modify/create permission and version preconditions. |
| F23 | Explicit trash operation | P0 | Separate grant and confirmation; recoverable NARADA-managed retention on supported roots. |
| F24 | Hidden/system/sensitive path exclusions | P0 | Conservative defaults; exclusions apply to search, preview and transfer too. |
| F25 | Multiple roots | P1 | Stable root IDs and isolated policies. |
| F26 | Grid view, favorites and recent items | P1 | No leak of revoked content or stale access. |
| F27 | Create folder, bulk/folder transfer, move/copy | P1 | Per-item results and no claim of whole-folder atomicity. |
| F28 | PDF/audio previews | P1 | Safe viewer and bounded decoding; external viewer warnings where needed. |
| F29 | Video previews and advanced media views | P2 | Explicit formats, resource budgets and failure states. |
| F30 | Permanent remote deletion | P1 | Distinct from trash; separate consent and irreversible-action warning. |

## Transfer engine

| ID | Feature | Priority | Acceptance boundary |
|---|---|---|---|
| F31 | Windows-to-Android download | P0 | Verified content before private-store publication. |
| F32 | Android-to-Windows upload | P0 | Safe destination, current permission and collision policy. |
| F33 | Bounded chunked streaming | P0 | Memory does not grow with entire file contents. |
| F34 | Durable resume and missing-chunk query | P0 | Resume binds to the same source version and manifest. |
| F35 | Per-chunk and whole-file SHA-256 | P0 | Integrity failure prevents final completion. |
| F36 | Pause, resume, cancel and retry | P0 | Idempotent controls and predictable temporary-file lifecycle. |
| F37 | Queue, history, progress and speed | P0 | Durable states; preparation and export shown separately. |
| F38 | Safe publication and commit recovery | P0 | Crash between rename and database update is reconciled. |
| F39 | Duplicate/conflict handling | P0 | Keep-both default; replace requires explicit permission and precondition. |
| F40 | Large-file and storage-limit handling | P0 | Tested sizes published; unsupported destinations fail early. |
| F41 | Bounded concurrency and backpressure | P0 | Control/chat traffic remains responsive during file movement. |
| F42 | ETA and completion notifications | P0 | ETA is approximate; notifications follow durable completion. |
| F43 | Bandwidth controls and user priorities | P1 | Cannot bypass fairness or exhaust another peer's resources. |
| F44 | Compression and advanced transport tuning | P2 | Measured improvement; no default recompression of already-compressed media. |

## Offline files and synchronization

| ID | Feature | Priority | Acceptance boundary |
|---|---|---|---|
| F45 | Pin and unpin | P0 | Unpin affects the local managed copy, not the laptop original. |
| F46 | Private persistent pin store | P0 | Completed pins survive ordinary cache cleanup and app restart. |
| F47 | Metadata change journal | P0 | Monotonic cursor, filtering and snapshot fallback. |
| F48 | Selected-content refresh | P0 | One-way laptop-to-phone version refresh; old usable pin retained until new copy verifies. |
| F49 | Freshness and offline status | P0 | Distinguishes current, stale, unavailable and source-deleted content. |
| F50 | Conflict preservation | P0 | Detects stale upload bases and preserves changed local/exported copies. |
| F51 | Tombstones and reconciliation | P0 | Source deletion does not silently erase the last pinned copy. |
| F52 | Cache quotas and storage cleanup | P0 | Pins, unsent attachments and unresolved conflicts are protected from automatic eviction. |
| F53 | Automatic bidirectional synchronization | P1 | Three-way comparison, explicit opt-in and conflict UI. |
| F54 | Automatic deletion propagation | P1 | Separate opt-in, retention and restore safeguards. |
| F55 | Scheduling and version history | P2 | Quotas, lifecycle limits and retention policy are explicit. |

## Chat and attachments

| ID | Feature | Priority | Acceptance boundary |
|---|---|---|---|
| F56 | One-to-one E2EE text chat | P0 | Maintained protocol implementation; reviewed endpoint key lifecycle. |
| F57 | Durable protected outbox | P0 | Offline messages survive supported restarts; no delivery claim while unreachable. |
| F58 | Protected history on both devices | P0 | Pagination and documented at-rest protection, backup and deletion limits. |
| F59 | Pending/sending/accepted/delivered/failed states | P0 | Durable recipient acknowledgment is the delivery boundary. |
| F60 | Duplicate suppression and retry | P0 | Stable message ID; same-ID/different-content replay is rejected. |
| F61 | Unread count and private notifications | P0 | No body previews by default; permission denial handled. |
| F62 | Optional read receipts | P1 | Off by default; user-controlled and distinct from delivery. |
| F63 | Immutable encrypted attachments | P0 | Random per-attachment content key; key carried only inside E2EE content. |
| F64 | File/image/document attachment save | P0 | Ciphertext authentication and plaintext verification before safe export. |
| F65 | Separate attachment transfer status | P0 | Delivered message does not imply attachment downloaded. |
| F66 | Reply, mute, local search and shared-media view | P1 | References and search stay within the authorized local conversation. |
| F67 | Original-file references | P1 | Explicitly labeled references; later source access still requires permission. |
| F68 | Edit, delete events, reactions and typing | P2 | Defined event/version semantics; no promise of remote erasure. |
| F69 | Audio/video attachment UX and voice notes | P1/P2 | Arbitrary allowed bytes may transfer already; richer playback/recording is separate. |
| F70 | Group chat and calls | P3 | New security and networking designs required. |

## Platform, security and maintenance

| ID | Feature | Priority | Acceptance boundary |
|---|---|---|---|
| F71 | Windows tray, startup choice and independent agent | P0 | User understands window close versus agent exit. |
| F72 | Authenticated, scoped local IPC | P0 | No arbitrary path execution or remotely reachable admin commands. |
| F73 | Android storage/lifecycle adapters | P0 | Persistable URI grants, supported job APIs and interruption handling. |
| F74 | Native notifications and share-sheet entry | P0/P1 | Notifications are P0; extended share-sheet shortcuts are P1. |
| F75 | Key protection and encrypted sensitive state | P0 | Verified adapter on both platforms; no plaintext secrets in logs/backups. |
| F76 | Threat tests, quotas and abuse limits | P0 | Authentication, path, parser and resource-abuse tests pass. |
| F77 | Privacy and retention controls | P0 | Explicit pin/message/diagnostic cleanup behavior. |
| F78 | Redacted diagnostics and local audit events | P0 | Export is user-triggered, inspectable and secret-free. |
| F79 | Migrations, repair and crash recovery | P0 | No blind recreation of a corrupted database or loss of original files. |
| F80 | Signed releases and verified update path | P0 | Manual offline update works; automatic update checks are optional. |
| F81 | Accessibility and complete error states | P0 | No color-only state, adequate contrast, usable large text and focus order. |
| F82 | Dark theme and extended personalization | P1 | No security or status ambiguity introduced by theme changes. |

Features not in P0 remain visible in the backlog but must not consume the security/recovery budget of 1.0.

## Longer-term exploration, not release commitments

After the qualified Windows–Android core is stable, separately evaluate photo backup, voice/video calls, screen sharing, media streaming, a Windows virtual drive, Linux/macOS/iOS clients, plugin/developer APIs, automation rules and richer retained version history. Public-internet access or any relay would require a new threat model, operating model and explicit product decision—not merely turning off a “local only” switch.
