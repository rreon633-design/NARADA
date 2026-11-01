# Pairing workflow, tips and troubleshooting

[Handoff](../../ANDROID-PHASE2-HANDOFF.md) · [Protocol](connection-protocol.md) · [Security](connection-security.md)

## Prepare a disposable test installation

1. Extract `NARADA_Android_Phase2_APKs.zip`; verify its `SHA256SUMS.txt`.
2. Install `NARADA_Android_Phase2_Debug.apk` on Android API 29+. ADB example: `adb install -r NARADA_Android_Phase2_Debug.apk`.
3. Use the current Windows agent/source with its native private-interface/firewall setup. **The Linux `dev-preview` agent used by the host harness binds only loopback; its QR cannot pair a real phone.** Windows runtime qualification has not been executed here.
4. Use a private IPv4 Wi-Fi/Ethernet route without VPN/cellular fallback. Avoid guest-network client isolation. Do not expose the listener on public interfaces or port-forward it.
5. Complete normal onboarding; open **More → Devices**. Preparing an identity is explicit and offline; no camera prompt or socket is needed for that step. Then enable local connections.

The supplied APK preserves the Phase 1 debug certificate, so it is a candidate for install-over testing—not evidence that a device upgrade has been executed. A rebuild signed with another key cannot update it in place. Never uninstall/clear data as a supposedly lossless repair.

## Pair and approve

1. On the desktop, explicitly enable its chosen private interface and create a fresh two-minute invitation.
2. On the phone, choose **Scan desktop QR**, **Import QR image**, or explicitly paste the code. Camera permission is asked only from the scanner; denial leaves the other paths.
3. Review the full host fingerprint against the intended desktop, not merely its displayed name. Confirm the comparison and request pairing while the app is visible.
4. The phone proves possession over mTLS. On the desktop, compare the **phone's full fingerprint** and explicitly approve its grants. Discovery does not approve anything.
5. On the phone, review the offered grants and confirm that phone fingerprint comparison; finish pairing.
6. Wait for **Connected · authenticated health**. `Trust saved · not connected` is not a failed identity, and neither a QR scan nor owner approval alone implies connectivity.

No file browsing, transfer, chat, attachment or sync functionality becomes available as a side effect.

## Interruption and recovery

| Visible state | Safe action |
|---|---|
| CLAIMING / unfinished one-use claim | Do not replay the old code. Cancel the local attempt and request a fresh desktop invitation; clear/revoke the old desktop attempt when necessary. |
| WAITING / APPROVED | Keep visible and use **Check approval again** to explicitly resume proof. Expired unfinished invitations need cancel/new invitation. |
| ACK_PENDING | Use **Recover acknowledgement** for this same saved pinned attempt. The desktop may already have committed it. Do not invent a new identity or token. |
| ACTIVE, offline | Check route/desktop/firewall. Edit the saved private address:port and reconnect; pins remain unchanged. |
| Auth/pin/protocol error | Stop and compare both devices. No insecure verifier, automatic pin update or automatic re-pair is attempted. |
| Storage/key error | Preserve data; **Reopen without resetting** only retries a read. Do not clear keys/files/preferences. See recovery precautions. |
| Locally revoked | Also revoke the phone on the desktop when appropriate. Re-pair only through a new explicit invitation/approval. |

Backgrounding the main app cancels live transport and discovery and removes in-memory invitation preview. Saved ACTIVE trust can reconnect when the app is foreground again; pending attempts require an explicit recovery action. Opening the QR scanner intentionally pauses the main connection owner.

## Troubleshooting tips

- **No eligible network:** ensure Android's selected active route is private IPv4 Wi-Fi/Ethernet, not a VPN or cellular default. The app does not bypass Android's local-network restrictions.
- **No discovery:** NSD is optional and untrusted; use QR pairing or the saved manual endpoint. Router multicast filtering/client isolation can block hints.
- **QR unavailable:** enlarge/brighten the code, reduce glare, use a clean image or paste explicitly. A URL QR is not a NARADA invitation.
- **Expired invitation / clock error:** correct both clocks and make a new invitation, not a longer-lived token workaround.
- **Wrong identity after address change:** an IP is not identity. Verify the desktop certificate; a different machine at the same IP must fail the pin.
- **Six failed retries:** review the listener and network, then explicitly retry. Activity recreation must not reset the persisted budget.
- **Permission updates:** change grants on the desktop; the phone observes authenticated revisions/epochs. It cannot grant itself access.
- **Support:** share only the allowlisted report after review. Never paste an invitation, private store, keystore, raw database or full device backup into a ticket.

## Developer reproduction

See [project setup](../../apps/android/README.md). The separate `:core:connection:interopTest` task requires a real freshly built Rust binary; missing input is a failure, not a skipped success. Do not run the historical desktop validators as a substitute for the new Android campaign.
