# NARADA — Security and compatibility qualification matrix

> Source/test design is not evidence that every cell passed. See [actual results](desktop-5-9-validation.md).

## Observed status

The recorded pass has **342 passes and 6 failures**, including one real chat contrast defect. Ten subsequent source corrections and four additional regression definitions have not been validated. Empty-page automated accessibility passes do not certify real-content contrast or native screen-reader behavior. See [actual results](desktop-5-9-validation.md) and [correction details](desktop-5-9-corrections.md).

## Host automation supplied

| Area | Executable source | What it can establish |
|---|---|---|
| Protected chat | Connection content/vault unit tests; `tests/process/content_offline.py` | Library exchange, transactional state, replay/digest rules, restart/key-loss behavior on the test host |
| Attachments | age tamper/EOF/hash tests and actual two-agent fixtures | Bounded ciphertext flow, checkpoint revalidation, separate export state |
| Offline | Files sync/pin tests and real processes | Snapshot fences, scoped tokens, cache transaction, preserved versions/tombstones |
| Boundary regression | Existing lifecycle/peer/file/transfer suites | Actual host process/TLS/IPC/path/transfer checks, not native equivalence |
| Parser resilience | Shared corpus, deterministic mutations, `fuzz/` | Regression inputs and an executable fuzz target; not proof of a long fuzz campaign |
| Frontend | Node tests, new browser spec, axe, screenshots | Real agent-backed UI, keyboard/semantic checks within automated coverage |
| Release tools | `tests/tooling/release_tools.py` | Synthetic signature verification, no execution, downgrade/tamper refusal, blocked missing evidence |
| Resource probe | `scripts/measure_desktop.py` | Narrow Linux startup/RSS/CPU/owner-RPC/protected-write observations only |

## Required native/external matrix — not executed here

| Configuration | Required checks | Status |
|---|---|---|
| Windows 11 x64, MSVC, WebView2, NTFS | Install/uninstall, sidecar, picker, tray, IPC ACL, current-user permissions | Open |
| Windows DPAPI, another user/account/reinstall | Wrapper access denial, key-loss UX, matching-context recovery | Open |
| Windows sharing/reparse namespace | Junctions, hardlinks, rename/delete races, readonly/AV locks, share modes | Open |
| Real device/storage interruption | Power loss, controller full, removable/network roots, ambiguous rename outcomes | Open |
| Two physical desktops / real LAN | Discovery, firewall, AP isolation, no internet, sleep/wake, endpoint change | Open |
| Keyboard/screen reader/high DPI | Focus, announcements, IME, high contrast, zoom, multiple UI windows | Open |
| Independent cryptographic/security review | Olm/age selection, integration transactions, parsers, authority and retention | Open |
| Android and SAF/providers | Original mobile P0 acceptance | Deferred, not implemented |

Windows GNU cross-compilation, when successful, is a source/link check—not MSVC, DPAPI, filesystem or installer execution. Windows 10 ordinary support ended in 2025; do not advertise it as a newly qualified ordinary-support baseline without an explicit support decision.

## Privacy checklist

No chat plaintext, session/key material, private unexported manifests or drafts may enter metadata SQLite/WAL, logs, diagnostics or browser storage. Support export is an explicit allowlist. Notifications contain no implemented private previews. The native notification-delivery feature remains unqualified rather than simulated.

Independent review, license approval, active advisory monitoring and genuine pilot ownership remain release blockers even if every host test passes.
