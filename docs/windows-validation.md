# Native Windows qualification — still an open gate

[README](../README.md) · [Phase 2 implementation](phase-2-implementation.md) · [Developer commands](desktop-development.md) · [Plan](../plan.md)

**Status: not executed on native Windows in this session.** A Linux GNU cross-build or cross-check cannot establish ACL enforcement, WebView2 behavior, tray lifetime, startup registration, or installer correctness.

Use a supported Windows 11 x64 machine or VM with a normal user account. Keep a separate test account for negative authorization checks. Record OS build, Rust/Node/WebView2 versions, binary hashes, exit codes and observed behavior. Never use personal files or real chat keys for these checks.

## Build and capability checks

- [ ] Run `npm ci`, Rust tests and Clippy with the checked-in lockfiles.
- [ ] Run the Windows same-owner IPC/workspace-isolation test (`cargo test -p narada-ipc`).
- [ ] Run `npm run desktop:dev`; verify the UI loads and all sixteen declared frontend commands work.
- [ ] Confirm native requests use Named Pipes; no development loopback HTTP listener is started.
- [ ] Verify generated application permissions allow only the sixteen declared app commands, including typed connection/pairing/permission controls. No generic peer/owner command forwarding is permitted.
- [ ] Test forged/unknown invocation, arbitrary paths, shell commands and malformed settings. All must be rejected.
- [ ] Confirm bundled CSP works; external script, navigation and network access are not required by the shipped UI.
- [ ] Validate diagnostic export in WebView2, not just Chromium. A browser download test is not sufficient evidence for native file delivery.

## Named Pipe authorization and robustness

- [ ] Inspect the protected DACL: current user SID only, no Everyone/other-user grant.
- [ ] Owner process can connect; a different signed-in user cannot read or write requests.
- [ ] Reject remote-machine pipe access, including deliberate remote UNC attempts.
- [ ] Check client verification of server process SID and server verification of client process SID.
- [ ] Confirm first-instance protection against endpoint pre-creation. Another process under the **same account** is not considered a separate security principal; do not claim protection against full account compromise.
- [ ] Check independent metadata directories produce separate SID/workspace-scoped pipe names.
- [ ] Verify partial, oversized, malformed and version-mismatched requests cannot mutate metadata.
- [ ] Exercise connection quota and timeout; failed owner-token lookup must reject that connection, not stop the listener.
- [ ] Inspect user app-data ACLs and reparse-point behavior. SQLite holds operational metadata (including names/endpoints/public identities), not an encrypted secrets store. Private transport keys are in the separate DPAPI-protected blob.

## Phase 2 identity, pairing and LAN qualification

- [ ] Execute native DPAPI round-trip/tamper tests; verify current-user context, missing/corrupt blob, wrong-user unwrap and no silent regeneration.
- [ ] Inspect the protected file and app-data ACLs; prove no plaintext PKCS#8/key material appears in SQLite, logs, crash/support exports or installer artifacts.
- [ ] Pair separate native agents: real QR, offline image decode, full fingerprint comparison, proof-before-approval and final acknowledgement.
- [ ] Verify WebView2 QR creation/readback, PNG/JPEG bounds, clipboard fallback and production CSP without broadening network permissions.
- [ ] Verify actual Private/Domain profile classification, Public/unknown refusal, localized OS output and unavailable/timed-out profile inspection.
- [ ] Exercise Wi-Fi/Ethernet/VPN/multiple NICs, profile changes while connected, DHCP changes and route loss. Measure the five-second polling guard and three-second query deadline; do not promise instantaneous closure.
- [ ] Verify only the selected address is bound and outbound sockets use that source. No automatic wildcard/public interface or firewall fallback.
- [ ] Verify anonymous mDNS on only the selected interface, withdrawn/expired candidates, hostile TXT/address hints, multicast blocking and manual fallback.
- [ ] Pair/reconnect on a real no-internet LAN, not just loopback. Record router, firewall and hotspot configurations, including temporary bootstrap-port reachability.
- [ ] Reject unknown, changed, expired, revoked and replayed identities; no TLS 1.2, resumption, 0-RTT or application-version downgrade.
- [ ] Change permissions/revoke during active and finishing handshakes; verify closure, durable epochs, new-request denial and safe restart.
- [ ] Qualify sleep/wake, long approval waits, connection rollover, retry limits and lost-final-ack recovery.
- [ ] Run independent review/fuzz/dependency checks before calling the pairing implementation security-qualified.

## User-process lifecycle

- [ ] Run without elevation; confirm no administrator prompt is required for normal use.
- [ ] Confirm a second agent cannot replace the same workspace owner.
- [ ] With close-to-tray on, close the window, verify agent PID continues, then reopen from tray.
- [ ] With close-to-tray off, close/relaunch UI and verify persisted preference hydrates before the next close.
- [ ] Explicit tray Quit gracefully stops agent and UI and releases the endpoint/lock.
- [ ] Kill only the UI, reconnect, and verify agent state is intact.
- [ ] Kill the agent after a confirmed save, relaunch and verify WAL recovery and unchanged preferences.
- [ ] Exercise sleep/wake, sign-out/login, Windows shutdown, stale endpoint, missing sidecar and unavailable app-data location.
- [ ] Decide and implement startup registration/unregistration. **Login autostart is not implemented**, and its UI row is not an enabled control.
- [ ] Check multiple UI processes, restart races and accurate offline/recovery display. Single-agent ownership is implemented; single-window activation is not yet a qualified behavior.

## Packaging and deployment

- [ ] Run `npm run desktop:build` on Windows MSVC; inspect current-user NSIS output.
- [ ] Verify sidecar is beside the app where the launcher expects it, and that it excludes the `dev-preview` feature.
- [ ] Install/uninstall as a non-admin user. Verify WebView2 prerequisite behavior and per-user install paths.
- [ ] Confirm uninstall never silently destroys user data; define explicit data-retention choices.
- [ ] Test upgrade over existing metadata and preserve data on migration failure.
- [ ] Test paths with spaces/Unicode and install directories outside the source tree.
- [ ] Production app identifier, publisher, code signing, update verification, dependency review and licensing remain release decisions.

## Accessibility and user acceptance

- [ ] Windows Narrator, keyboard-only operation, focus return and modal focus trapping.
- [ ] 100/125/150/200% scaling, supported minimum window size, high contrast and reduced motion.
- [ ] Settings and setup are real/persistent; notification preferences do not imply notification delivery is implemented.
- [ ] No placeholder phone, QR identity, user files, transfer progress or message receipts are shown as live functionality.

Attach evidence to the live tracker. **Do not close native phases 1–2 qualification solely because CI compiles, browser tests pass, or this checklist exists.** The original roadmap's Android foundation and pairing remain separate and open.


## Phase 3 — native file qualification (not executed here)

- [ ] Build the complete MSVC Tauri shell and sidecar with the Windows-only rfd picker.
- [ ] Exercise picker selection/cancellation, Unicode/long paths, protected ancestors, whole disks and multiple dialogs.
- [ ] Confirm only local fixed NTFS roots; refuse UNC/device paths, mapped/network/removable volumes, junctions, reparse/cloud placeholders and unsupported filesystems.
- [ ] Test short-name/case/Unicode aliases, hidden/system ancestors, hardlinks and root replacement.
- [ ] Race readers/writers/renamers against browse, preview and mutation; prove namespace containment and documented version semantics or fail closed.
- [ ] Test directory creation during empty-folder mutation. The current development slice does not establish an atomic compare-and-move against all external writers.
- [ ] Verify relative-handle rename/no overwrite, sharing violations, permissions, disk-full and flush failures without copy/delete fallback.
- [ ] Kill before/after intent, filesystem move and receipt commit; validate idempotency, retained data and ambiguous-state blocking.
- [ ] Verify trash directory identity, quota, minimum retention, missing/corrupt retained items and restore collisions.
- [ ] Verify watcher overflow/missed events and reconciliation on real NTFS; quantify index/preview latency and peak memory.
- [ ] Execute root grants/revocation/exclusions with separate native desktops and actual LAN interfaces.
- [ ] Confirm 26-command native capability behavior, denied remote-document access, current-user ACLs, and no generic frontend filesystem/shell API.

GNU agent/files checks and the source picker/parity check are **not** execution evidence for any checkbox above. Linux file mutation tests use disposable fixtures and do not qualify Windows or hostile same-user races.


## Phase 4 additional unqualified gates

- Execute protected owner-DACL staging creation on NTFS, including inheritance, account changes and denied access; inspect source snapshot permissions.
- Exercise file share modes, pinned ancestor/relative rename, hardlinks/reparse/short-name/case aliases, metadata changes and hostile writers during hash/commit.
- Qualify file flush + no-replace rename + SQLite FULL receipt ordering with real power-cut/disk-full/controller-cache scenarios. GNU linking and simulated crash rows are not proof.
- Verify native Tauri queue/dialog lifecycle, keyboard/screen-reader announcements, renderer RPC bounds and picker flow against two physical devices.
- Measure sustained throughput/RAM/disk quotas, inbound/outbound fairness, permission withdrawal and endpoint rollover under load. Current 64 MiB development caps are intentional.
