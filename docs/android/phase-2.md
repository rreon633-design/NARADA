# Android Phase 2 — identity and trusted connections

[Documentation index](README.md) · [Roadmap](../../android-plan.md) · [Handoff](../../ANDROID-PHASE2-HANDOFF.md)

**Development implementation delivered, 31 October 2026.** Version **0.2.0 (2)**, Room **3**, min API 29 / compile-target 36. Available host/interop/build gates passed; physical Android ↔ Windows qualification remains open. Android is an **outbound client**, never a desktop-owner API or content server.

## Work packages

| ID | Delivered source | Evidence boundary |
|---|---|---|
| AND-201 | Nonexportable AndroidKeyStore P-256 identity, independent AES-256-GCM key, atomic sealed trust aggregate, Room enrollment anchor | Cipher/repository tests passed; actual Keystore/fsync/OEM behavior needs devices |
| AND-202 | Strict bounded JSON, canonical private IPv4 endpoints, certificate/SPKI pins, self-signature/profile and standard TLS verification | Parser/certificate tests, adversarial real JVM TLS and Rust interop passed |
| AND-203 | Contextual CameraX + offline ZXing scanner, bounded image import and manual paste | Actual offline decoder and memory/saved-state tests passed; camera/provider qualification open |
| AND-204 | Pinned one-use bootstrap → mTLS proof → desktop owner approval → phone comparison → durable ACK_PENDING → ACTIVE | Owner/no-cert/wrong-key/consumed-token/lost-reply/restart negatives against real Rust passed |
| AND-205 | Selected Wi-Fi/Ethernet binding, TLS 1.3, bounded foreground health sessions and persistent retry budget | Core/coordinator tests passed; Conscrypt/router/lifecycle behavior needs devices |
| AND-206 | Untrusted NSD hints, manual pinned reconnect, read-only remote grants/counters, local tombstones/epochs | State/epoch/revocation tests passed; NSD and native routing matrix open |
| AND-207 | Actual routed Devices screen, ViewModel, Home trust summary and lifecycle state; no fabricated “Connected” | Compose host tests/renders, 200% controls, saved-state privacy and lint passed |
| AND-208 | Locked builds, 186 passing current host/interop checks, APK audit, split documentation and CI definition | APK/instrumentation built, not installed/executed here; remote CI not run |

## Success metrics

- 22 domain + 59 connection + 95 Android host + 10 real-agent interoperability = **186 passed; zero failures/errors/skips in final stages**.
- Seven final stages use the same **108 runtime/test/build inputs**. Earlier preparation failures remain visible.
- Lint: zero errors/warnings, 36 informational dependency-update hints; no application baseline.
- Only authenticated, validated and persisted health can display live connected state. Stored trust alone is offline.
- No token/private key in Room, preferences, navigation saved state, result Intent, logs, support export or backups. Java/Kotlin heap strings are not claimed to be securely erasable.
- No silent re-enrollment, verifier bypass, TLS downgrade, unauthenticated content endpoint or automatic pairing from discovery.

## Deliberate limits

One foreground health connection; one unfinished pairing; at most 16 peer records including tombstones. No permanent background-presence service. No IPv6/DNS/cellular/VPN transport. The Linux development agent is loopback-only; it is not a real-phone LAN host.

Pairing does **not** enable browsing, transfers, E2EE messaging, attachments, pins or synchronization. Their screens remain unavailable. Desktop grants are displayed but do not create Android feature implementations or inbound phone authority.

## Remaining exit gates

Run the [native checklist](phase-2-device-checklist.md), especially real AndroidKeyStore/Conscrypt client authentication against a Windows private-interface agent, process interruption, camera/NSD, backup/restore and physical accessibility. Independent security/dependency review and production signing remain later gates. [Validation](phase-2-validation.md) distinguishes these from host results.
