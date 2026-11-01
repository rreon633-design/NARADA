# NARADA — Tips and Tricks

> [Documentation index](../README.md) · Practical guidance, not security bypasses · 30 October 2026

## 1. For everyday users

1. **Start small.** Share a dedicated test folder and transfer one harmless file before exposing important documents.
2. **Use read-only first.** Enable upload/modify/trash only when you actually need them.
3. **Keep the laptop available.** Closing the window can leave the agent running; sleep, shutdown or logout cannot guarantee connectivity.
4. **Use a direct, stable LAN.** A trusted non-guest network usually avoids client-isolation surprises. Do not change an untrusted public network's security profile simply to make sharing work.
5. **Try manual endpoint entry for discovery failure.** It will not fix isolation, wrong identity or a blocked firewall rule.
6. **Pin before leaving.** Confirm a file is verified and available offline, not merely queued for pinning.
7. **Read freshness labels.** An offline copy can be useful but older than the source.
8. **Leave free space.** Preparation, encryption, verified staging and export may need additional temporary storage.
9. **Know what Delete means.** Laptop trash, local-download removal and unpin are different actions affecting different copies.
10. **Watch message status.** Pending means the message remains on the sender; delivered requires the recipient's durable confirmation.
11. **Reopen Android when necessary.** Force-stop or OS restrictions can delay work; NARADA does not use a mandatory push cloud to bypass this.
12. **Keep independent backups.** Synchronization is not protection against every accidental edit, device loss or ransomware event.
13. **Review paired devices.** Revoke lost/retired peers promptly, while understanding that previously received data cannot be recalled.
14. **Use local providers for fully offline work.** A cloud-backed picker entry may need internet even though NARADA itself does not.
15. **Share diagnostics carefully.** Inspect the redacted export; do not attach personal files or secrets to support requests.

## 2. For developers

- Build one real two-device vertical slice before implementing every mockup surface.
- Keep a development-only fixture adapter so the UI can be built without inventing successful network state in production.
- Share protocol schemas and negative test vectors before writing separate Windows/Android interpretations.
- Inject clock, network, filesystem and random/test boundaries where appropriate; never replace production cryptographic randomness with a deterministic test generator.
- Test zero-byte files and one-chunk files early; they reveal manifest/state assumptions cheaply.
- A lost final response is an essential test, not an exotic edge case.
- Use stable object IDs in UI selection and commands; filenames can change during an operation.
- Show preparation, transfer, verification and save/export separately. This prevents “stuck at 100%” confusion.
- Benchmark hash/storage/network stages independently before adding compression or a new transport.
- Debounce watcher noise, but never use debounce as a substitute for reconciliation.
- Keep attachment ciphertext immutable so network resume does not need to recreate encryption state.
- Run privacy checks on logs and crash reports as automated tests.
- Use one permission-aware action model to drive desktop menus, Android sheets and command validation tests.
- Prefer a smaller well-tested P0 release over multi-device/sync/voice features with ambiguous safety guarantees.

## 3. Useful development-only diagnostics

These examples assume the planned application is running on your own test devices. Replace the example address/port with the actual configured endpoint; never treat reachability as identity verification.

```powershell
# Windows: check whether the expected local port is listening.
Get-NetTCPConnection -LocalPort 45821 -State Listen

# Windows: test a known test endpoint's TCP reachability.
Test-NetConnection -ComputerName 192.168.1.105 -Port 45821

# Inspect toolchain versions in a configured development environment.
rustc --version
cargo --version
adb version
```

A successful TCP test proves only that something accepts the connection. NARADA must still validate the paired identity and grants. A failed test may be routing, process availability or firewall—not automatically a certificate problem.

Avoid `curl -k` or accept-all TLS code as a permanent “fix.” If using test certificates in a harness, isolate them from production identity and never ship their private keys.

## 4. UX details that pay off

- Use a single connection badge component with the same state vocabulary everywhere.
- Add “Show on this device” and “Send to phone” labels instead of ambiguous duplicate Download buttons.
- Preserve selection when a background file list refreshes.
- Keep the previous pin visible during refresh; show a small freshness badge rather than blanking the file.
- Include a copyable error/operation ID, not a long raw internal stack trace.
- Make long filenames available in details and accessible labels without breaking layout.
- On mobile, keep the composer above the keyboard and the cancel action easy to reach.
- Use native notifications sparingly; do not notify for every chunk or every minor reconnect.

## 5. What not to “optimize” away

Do not remove final verification, durable receipts, version checks, authorization, safe staging or recovery records because a demo feels faster without them. Optimize their implementation only after measuring and preserving the same guarantees.
