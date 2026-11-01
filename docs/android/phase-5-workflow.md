# Phase 5 — workflow, process and practical tips

[Index](README.md) · [Recovery](chat-recovery.md) · [Build](../../apps/android/README.md)

## End-to-end use

1. On a **disposable, qualified test setup**, finish the existing identity/pairing flow. Compare fingerprints on both devices and explicitly approve desktop permissions. “Connected” still requires durable pairing ACK plus authenticated health; chat does not bypass this.
2. Keep the phone foreground on an allowed private Wi-Fi/Ethernet route. The desktop must grant chat and advertise `chat.olm.v1`. No phone content listener or owner API is opened.
3. Open **Chat**, choose the approved desktop and explicitly enable protected text messaging. Account creation and transport-bound bundle/pin installation are protected transactions, not onboarding side effects.
4. Load the private conversation. Type a text. The counter measures **UTF-8 bytes**; emoji/non-Latin text can use several bytes per character. Limit4096 bytes. There is no attachment button in this phase.
5. Choose **Save encrypted draft** to persist typing, or **Queue & sync text** to commit its exact ID/scope and start a bounded foreground batch. Typing alone is not durable.
6. Native crypto computes a candidate ratchet/envelope. The protected repository commits both **before push**. Only a validated, durably saved matching receipt becomes Delivered.
7. In the same explicit batch, eligible older receipts can be re-ACKed and desktop texts pulled. Decrypted history, candidate ratchet and receipt commit **before ACK**. Delivered does not mean read.
8. Use **Sync this conversation** again when you want more bounded work. There is no background listener, polling service or automatic resend after restart.

## Errors and decisions

| Visible situation | Safe next step | Do not infer |
|---|---|---|
| Private view is closed / foreground changed | Reopen and explicitly load protected history | That plaintext was durably auto-saved |
| Offline / grant missing | Review current desktop grant, private route and pairing | That saved local history was erased or TLS alone authorizes chat |
| Delivery unconfirmed | Inspect the retained message and review exact retry/cancel | That the desktop did not receive it |
| Key changed / invalid session | Stop and compare retained identities/evidence | That deleting identity/store data is a repair |
| History/session/aggregate full | Preserve existing records; review the bounded retention design | That the app will silently prune old/unknown records |
| Protected storage unavailable | Keep networking disabled; preserve evidence privately | That software keys/plaintext can be substituted |
| Attachment unsupported | Use the later, separately qualified attachment feature | That ignoring the attachment is an acceptable ACK |

## Retry and cancellation tips

A reviewed retry re-enables only the **same retained ID/ciphertext/pairing** within its remaining policy. Follow with explicit sync if eligible. It does not modify the message, create a fresh session, reset attempts or promise delivery. Cancelling stops future local work, not an already admitted remote effect. Lost ACK and lost push responses have distinct recovery paths.

Re-pairing requires renewed consent; retained history/pins remain, but earlier-pairing outbox messages cannot replay under the new grant. Treat remote revocation as stopping future authority, not erasing previously received private data.

## Privacy and user experience

The private composer/history does not enter saved UI state. Switching tabs/backgrounding clears the projection. Save a draft explicitly if you need it later. Notifications are disabled by default and, if enabled, carry only generic activity text. They are not a substitute for an explicit sync or proof of a read receipt.

Home/Files/Chat/Transfers/More remain native, reference-aligned screens. Supplied prototype sample messages are never inserted into production state. Captures19–22 in final host evidence are labeled synthetic.200% text host checks show wrapping/scrollability, not physical IME/TalkBack/inset qualification.

## Development workflow

Build the host and all four Android JNI libraries **before** Gradle; the verifier rejects missing/stale generated native inputs. Run Rust, core, actual-peer, app host, lint and APK stages sequentially on the small host. Preserve each attempt in a new record directory. Do not add filtered/preliminary counts to final totals, overwrite failed evidence or rerun the user's historical desktop single-pass campaign.

See [validation](phase-5-validation.md), [problems/solutions](phase-5-problems-solutions.md) and [native tasks](phase-5-device-checklist.md). The next feature phase is attachments, not a waiver of this phase's independent review.
