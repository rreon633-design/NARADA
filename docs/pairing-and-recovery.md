# Pairing two desktop agents and recovering safely

[Implementation](phase-2-implementation.md) · [Developer guide](desktop-development.md) · [Protocol](phase-2-protocol.md)

## Quick local demonstration

Install prerequisites and dependencies as described in the developer guide, then use two terminals:

```sh
# First owner UI and agent: UI 5173, development adapter 4319
npm run dev
```

```sh
# Separate identity, database, process and UI: UI 5175, adapter 4321
npm run dev:reference
```

On your workstation, open those localhost UI ports. In Arena use their live-preview entries; `localhost` in your own browser is not the sandbox.

The reference is another **real NARADA agent**, not a mock response provider. Automated security tests also use an independent OpenSSL/Python TLS peer. The Linux preview is intentionally loopback-only: these codes connect processes on the same development host, not an Android phone or another physical computer.

## Pair

1. Open **Devices** in both workspaces. Give the reference a different name in Settings if helpful.
2. In each, choose **Enable connections**. Select the loopback test interface in the development preview. Use port `0` for automatic selection.
3. On the owner, choose **Pair a device**. The QR and expandable text code are real and expire in two minutes.
4. On the other desktop, choose **Join with a code**. Paste the text or choose a PNG/JPEG of the QR. Image decoding is offline; no image upload or camera permission is used.
5. Choose **Request pairing**. The owner verifies the joining device's private key through mutual TLS.
6. On the owner, choose **Review request**. Compare the complete requester fingerprint with the joining desktop's displayed local fingerprint. A matching name is not enough.
7. Confirm the comparison and choose **Approve this device**. Wait for the final acknowledgement and **Connected** status.
8. A fresh profile still has **zero shared roots**, and pairing alone grants no file access. Phase 3 file access requires a separately registered root and device grant; chat/attachments remain unavailable.

If a code was claimed by an unexpected device, reject/cancel it and generate a new code. Do not approve an unfamiliar fingerprint because the label looks right. Closing the QR dialog erases that window's copy of the secret; it does not grant trust. An already-open invitation can still be cancelled from Devices.

## Permissions, pause and revocation

- **Details & permissions** shows the pinned certificate, local grants and authorization counters.
- **Reset to connection-only** clears content grants and invalidates existing sessions. Reserved flags never turn on unavailable features.
- **Disable connections** closes bootstrap, pending, active and outbound transports and stops discovery. Stored identity and trust remain.
- **Revoke device → Revoke trust** durably removes authorization before closing sessions. The old identity cannot reconnect through its revoked trust.
- A fresh pairing after revocation requires another one-time invitation, key proof and owner approval. Revocation is not silently undone by retry or discovery.

## Reconnect and manual routes

For an outbound peer, **Retry connection** starts a bounded attempt series using the saved pin. An unfinished durable record can use **Retry confirmation**; it can activate only if the pinned host confirms that exact approved attempt.

Use **Details & permissions → Update saved endpoint** when a trusted host's address/port changed. Enter a private IPv4 address and port. The saved identity pin cannot be edited through this form. A different server at that address is rejected.

When joining a new invitation, the optional route override takes the **bootstrap port from that live invitation**, not the host's normal API port. The same QR fingerprint remains mandatory.

A peer marked “Connects to this desktop” initiates the connection from its own agent. The owner does not probe guessed addresses for it.

## Windows LAN operation: implementation, not qualification

The Windows source supports an explicitly selected Private or Domain-authenticated IPv4 interface. The app reads OS profile categories rather than trusting an RFC1918 address. Public, absent or unknown profiles are denied; a detected profile change closes connections. It does not automatically reopen on a newly private interface.

Discovery is a separate opt-in. It advertises an anonymous ephemeral `_narada._tcp.local.` service/version on the selected interface. Results are untrusted hints, not paired identities. Manual pairing/reconnect does not depend on discovery.

The host's normal port and short-lived bootstrap port must be reachable. NARADA **does not elevate or modify firewall rules**. Have the machine/network owner review narrowly scoped Private-profile application/port rules where necessary; never turn off the firewall or expose the listener on a public/WAN interface as a workaround.

Real Windows, router, firewall, multicast, hotspot and no-internet-LAN tests remain on the [native checklist](windows-validation.md). Do not claim they were performed merely because loopback pairing passes.

## Protected identity and development-vault care

### Windows

`transport.identity` is protected by the current Windows user's DPAPI context. The SQLite marker records the expected public identity. Copying a blob to another user/machine is not a supported automatic migration. Back up/restore only with a reviewed policy that also preserves the necessary Windows protection context.

### Explicit Linux development vault

For a fresh development profile, `scripts/dev.mjs` creates a random wrapping key under `.cache/development-vaults/`, directory mode 0700 and key-file mode 0600. The filename is derived from the profile path. The key is passed to the agent as `NARADA_DEV_VAULT_KEY`, never as `VITE_*`, and is removed from the Vite child's environment. The blob is authenticated-encrypted, but a locally accessible wrapping key is **not OS-backed production protection**.

For an existing profile the launcher does not replace a missing wrapper key. Restore the original development key, or supply the same 32-byte hex key through `NARADA_DEV_VAULT_KEY` in your own trusted environment. Do not paste it into an issue, screenshot, chat, log or source repository.

When intentionally upgrading a **Phase 1-only Linux development profile that never had a transport identity**, the first initialization may be explicitly requested:

```sh
NARADA_INITIALIZE_DEV_VAULT=1 npm run dev
```

This does not override an existing encrypted identity or bypass its database marker. Do not use it to “recover” a lost existing identity. A first-time fresh profile needs no override. Windows always uses DPAPI and ignores this development-wrapper path.

Runtime profiles and wrapping keys are excluded from source packages. Arena workspace snapshots exclude `.cache`, but a profile may persist without that cached wrapping key. Preserve the original key securely yourself or expect connections to be blocked after cache loss or sandbox restoration. Moving a profile also changes the launcher lookup filename; supply the same original key explicitly rather than generating a replacement. The software intentionally cannot manufacture a replacement for a lost identity.

## Actionable errors

| Symptom/code | Safe response |
|---|---|
| `IDENTITY_KEY_MISSING` | Preserve the workspace; restore its original protected identity. No new identity was generated. |
| `KEYSTORE_LOCKED` | Use the original Windows protection context or original explicit development wrapping key. Do not overwrite the blob. |
| `IDENTITY_CORRUPT` / `IDENTITY_CHANGED` | Keep the original directory for diagnosis. A valid but different key is not silently adopted. |
| `KEYSTORE_UNAVAILABLE` | Start with the supported native protector or explicit development launcher/key. Default non-Windows production builds have no fallback. |
| `INVITATION_USED` / `INVITATION_EXPIRED` | Generate a fresh code; never extend or replay an old token. |
| `PROOF_REQUIRED` | Do not approve yet. The requester has not demonstrated its private key. |
| `IDENTITY_REJECTED` / `PEER_AUTH_UNCONFIRMED` | Check both identities, clocks and revocation state. Do not replace a saved pin simply to make the error disappear. |
| `PEER_UNREACHABLE` / `PEER_TIMEOUT` | Check running agent, selected interface, private route, port and firewall. |
| `RETRY_LIMIT` | Six attempts failed. Fix the cause and explicitly retry. |
| `PUBLIC_NETWORK_BLOCKED` | Select a verified eligible network; never widen to `0.0.0.0` as a workaround. |
| `DISCOVERY_UNAVAILABLE` | Use the one-time code or saved manual endpoint. Loopback preview deliberately has no LAN discovery. |
| `REVISION_CONFLICT` | Refresh/reopen permissions before editing; another owner action changed the state. |
| `ALREADY_PAIRED` | Reconnect the existing identity, retry pending confirmation, or explicitly revoke before a new pairing. |
| Metadata/storage failure | Preserve the data. Neither metadata corruption nor key loss triggers an automatic reset. |

If recovery is impossible, deliberately create a **different empty test workspace** only after preserving the original and understanding that it has a new identity. Other devices must revoke/re-pair explicitly. There is no silent “reset everything” button.


## Phase 3 file access

Pairing still grants no files by default. The root owner now registers folders in **Files**, then uses **Device access** to grant one active peer browse, preview or managed-operation capabilities. See [Files and recovery](files-and-recovery.md). The joining/outbound desktop can select the inviter's saved endpoint as a file source; no reverse endpoint is guessed for an inbound peer. Native Windows/LAN and filesystem qualification remain open.
