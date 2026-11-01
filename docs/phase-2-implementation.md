# Phase 2 — trusted desktop connections

> Historical v0.2.0 scope/evidence. For current v0.3.0 files and health capability additions, see [Phase 3 implementation](phase-3-implementation.md) and [file protocol](phase-3-protocol.md).

[README](../README.md) · [Live plan](../plan.md) · [Wire contract](phase-2-protocol.md) · [Pairing and recovery](pairing-and-recovery.md) · [Windows gates](windows-validation.md)

## Scope and completion meaning

This implements the **desktop-only Phase 2 slice selected by the user**, using separate real agent processes as peers. Android was explicitly deferred. The Windows–Android roadmap is not marked universally complete.

The runnable development implementation is NARADA **0.2.0**. Native Windows/MSVC, DPAPI runtime isolation, physical Private-LAN/mDNS, installer and independent security qualification remain open. Cross-compilation and loopback tests are not substitutes for those gates.

## What now works

| Backlog | Desktop implementation | Evidence boundary |
|---|---|---|
| NAR-017 | P-256 transport identity; per-user Windows DPAPI; explicit non-OS-backed development vault; public identity marker; no silent key replacement | Development persistence/loss/corruption/swap tests. Windows DPAPI code cross-compiles; native execution still required. |
| NAR-018 | Real QR generation and offline PNG/JPEG decoding; 256-bit one-use invitations; 120-second lifetime; private-key proof; explicit owner approval; final activation acknowledgement | Browser QR round-trip, actual separate-agent pairing, independent OpenSSL reference client, real expiry tests. |
| NAR-019 | Exact certificate pins, WebPKI checks, TLS 1.3-only mutual authentication, mandatory capability agreement; no resumption or 0-RTT | Unknown, absent, expired and changed certificates, TLS/application downgrade and replay rejection. |
| NAR-020 | Separate local/remote grants; per-device/root capability checks; grant revisions and session epochs; narrow owner-only IPC | Unit tests cover scope, capability, stale revisions and withdrawn roots. Phase 2 has no root registration or content endpoints. |
| NAR-021 | Selected-address listener and outbound source binding; Windows profile inspection; opt-in anonymous mDNS candidate cache | Implementation and Windows cross-check exist. Linux deliberately refuses LAN/discovery; actual Windows/router/multicast qualification remains open. |
| NAR-022 | Saved pinned endpoint, manual correction, jittered bounded reconnect, stopped-retry state and actionable errors | Separate processes exercise restart, wrong endpoints, identity substitution and real retry limits. |
| NAR-023 | Durable revocation and epoch/grant invalidation before session cancellation; finishing-handshake pin recheck | Live session closure and post-restart rejection tested. File-chunk/commit integration belongs to later phases. |
| NAR-024 | Protocol, storage, process, independent TLS client, browser, QR and accessibility checks | Exact observed results are in the [Phase 2 validation summary](../artifacts/phase-2/validation-summary.json). |

**Not implemented:** Android, user-file browsing, root picker, file operations, transfers, E2EE chat, attachments, sync, notification delivery, login autostart or a qualified installer. Reserved permission flags do not enable nonexistent features.

## Implementation map

| Location | Responsibility |
|---|---|
| `crates/narada-connection/src/identity.rs` | Key/certificate creation, protected blob, marker matching, DPAPI and development wrapper. |
| `tls.rs` | Exact pins plus library certificate/signature validation; TLS 1.3 configs. |
| `wire.rs` | Strict invitation/handshake DTOs, capability agreement, limits and error categories. |
| `store.rs` | Public trust metadata, serialized capacity checks, cancellation tombstones, grants, authorization and revocation. |
| `service.rs` | Owner command routing, invitation state machine, acknowledgement recovery, session registry and reconnect workers. |
| `transport.rs` | Bounded HTTPS handling, authenticated clients, deadlines and sanitized peer errors. |
| `endpoint.rs` | Literal local IPv4 validation, interface inspection and Windows profile guard. |
| `discovery.rs` | Anonymous selected-interface mDNS; bounded untrusted hints. |
| `crates/narada-core/migrations/0003_connections.sql` | Additive trust/config/authorization tables; invalidates obsolete Phase 1 health reports. |
| `crates/narada-protocol/src/connections.rs` | Typed local-owner commands, snapshots and scoped capability model. |
| `apps/windows-agent/src/main.rs` | One shared service dispatcher for OS IPC and the feature-gated development bridge. |
| `apps/desktop-shell/src-tauri/` | Sixteen explicit frontend commands; no generic filesystem, shell or peer-admin forwarding API. |
| `desktop-ui/src/pages/devices.js` | Connection consent, real QR, join/proof/approval states, permissions, endpoint repair and revocation. |
| `desktop-ui/src/lib/pairing.js` | Offline decoder, format/expiry checks and image resource bounds. |
| `scripts/reference.mjs` | Separate reference profile, protected identity, agent process and optional second owner UI. |

The supplied visual references remain unchanged. Devices uses the same restrained desktop layout, typography, panels and contrast direction; no placeholder device is seeded.

## Storage and migration

- The existing settings/activity database is migrated, not reset. Schema version is now **3**.
- SQLite holds operational metadata: device labels, public certificates/fingerprints, endpoints, grants, revisions and timestamps. **This metadata is not encrypted** and may identify devices.
- Private PKCS#8 bytes are stored only in the protected `transport.identity` blob outside SQLite. Owned serialized-key buffers are zeroized; this is not a claim of complete process-memory secrecy.
- Windows always uses the current user's DPAPI context with UI forbidden, including preview builds. It does not select the development wrapper on Windows.
- On non-Windows development builds, AES-256-GCM wraps the identity using an explicitly supplied test key. This is **not an OS-backed vault or production Linux secret-store implementation**.
- Protected-file publication is no-clobber and flushed; a public database marker binds the expected identity. Missing, malformed, mismatched or unwrappable identities block connections rather than regenerating a new identity.
- Revoked trust rows retain their counters so later explicit re-pairing cannot reset old epochs. The overview shows a bounded recent subset; the short recent-certificate index is not the authorization source of truth.
- Invitation tokens and pending host approvals are memory-only. Restart invalidates unfinished invitations. A joining-side durable pending record can recover a final acknowledgement only when the pinned host still confirms that exact attempt.
- `cancel_join` durably records cancellation before stopping work, preventing late trust publication by that attempt.

See [recovery instructions](pairing-and-recovery.md) before moving, restoring or deleting any data.

## User-visible behavior

1. Devices initially shows the real local identity and **Connections disabled**.
2. Enabling requires a selected eligible interface. This is separate from discovery consent.
3. “Pair a device” creates an actual, expiring QR and text code, not artwork.
4. “Join with a code” accepts pasted text or a locally decoded PNG/JPEG. Images are not uploaded.
5. The owner sees a self-reported label and full certificate fingerprint. Approval is disabled until the mTLS private-key proof succeeds; the UI also requires a fingerprint-comparison acknowledgement.
6. Approval alone is not active trust. The joining endpoint first records pending trust, then sends the final authenticated acknowledgement; the host commits and the joiner confirms activation.
7. “Connected” follows a successful authenticated health exchange. Refused, pending, offline, revoked and retry-stopped states are distinct.
8. Permission reset changes durable revisions/epochs and closes existing sessions. It does not expose files or enable chat.
9. Revocation is committed before pin removal and cancellation. Reconnection cannot replace the saved pin or implicitly re-pair.
10. Disabling connections cancels the network parent token, including bootstrap, join and outbound workers; identity and existing trust remain stored.

## Security boundaries that remain important

- A QR secret permits one claim, not owner authority. Device names and discovery results are not identity evidence.
- All ordinary API requests require an active mTLS identity and a current authorization lease. The bootstrap listener has only one claim route.
- Every future content handler must bind authorization inputs to its authenticated TLS session, then re-check grants/epochs at the relevant operation/commit boundary. A tested helper is not proof that unwritten file/transfer code is safe.
- Private IPv4 does not prove a Windows Private network profile. Windows queries OS profile categories; unknown/public profiles fail closed. Profile changes are checked on a five-second monitor with a bounded OS-query timeout, not instant OS-event enforcement.
- mDNS announces an ephemeral generic instance and version, not the workspace label, key or stable fingerprint. An enabled TLS listener still presents its public transport certificate to active clients; do not claim network untrackability.
- Same-account compromise is outside the isolation promise. Another process with the owner's OS authority may access owner IPC or DPAPI. The development proxy is for trusted development users, not public hosting.
- There is no fallback to plaintext identity storage, permissive TLS, public interfaces, arbitrary DNS, HTTP redirects or unauthenticated owner controls.

## Evidence and remaining gates

See [validation evidence](../artifacts/phase-2/README.md) and the [native checklist](windows-validation.md). Keep the original Phase 1 ZIP and documentation ZIP as historical handoffs; Phase 2 has a separate source package and manifest.

Before declaring a Windows release qualified: execute an MSVC Tauri build, native DPAPI/SID/ACL checks, WebView2/CSP/QR/export checks, Private/Public profile transitions, firewall/router/mDNS cases, sleep/wake and packaging tests; perform independent security review and dependency/license review. Android remains a separate future implementation.
