# Desktop development — actual phases 1–4 commands

[README](../README.md) · [Live plan](../plan.md) · [Phase 4 implementation](phase-4-implementation.md) · [Windows qualification](windows-validation.md)

This guide describes development commands, not evidence that they all succeeded. **The Phase 4 single pass failed to build the backend; targeted corrections have not been rebuilt or retested. Both previews remain stopped.** See [actual results](phase-4-validation.md) before running the corrected source. Historical phases 1–3 results are separate.

## Prerequisites

- Rust **1.99.1**, Cargo, rustfmt and Clippy. `rust-toolchain.toml` pins the version.
- Node.js ≥20.19 and npm. Prefer a currently supported Node LTS on your workstation; this sandbox was tested with Node 20.20.2.
- A C compiler for the bundled SQLite dependency. On Windows, install Visual Studio Build Tools with **Desktop development with C++**, Windows SDK, and the MSVC Rust toolchain.
- Python 3 and OpenSSL for the Linux separate-process/independent TLS reference suite.
- Native Windows: supported Windows 11 x64 and WebView2 Runtime. Other platforms are development hosts, not qualified desktop release targets.

Do not run the app as administrator. Do not add cloud or cryptographic credentials to configuration files.

## Run the development preview

From the `NARADA` project root:

```sh
npm ci
npm run dev
```

Open `http://localhost:5173`. If a validated preview is later started in Arena, use its live-preview entry instead: the browser is not the sandbox and should not address a sandbox service through its own `localhost`.

The script starts:

1. The real Rust agent, with its optional **development-only** HTTP adapter bound to loopback port 4319.
2. Vite on port 5173. It proxies relative `/__narada` calls and injects an ephemeral server-only token.
3. Persistent development metadata and protected transport identity under `.narada-data/preview/`.
4. On Linux, an explicit test wrapping key outside that profile under `.cache/development-vaults/`; no production/non-Windows fallback. See [pairing and recovery](pairing-and-recovery.md).

The UI clearly identifies the development runtime. Its data is on the development host, **not on the computer opening the browser**. UI preferences and setup completion survive page reload and agent restart. Protected desktop pairing is opt-in. Approved-root browsing, image preview and managed file operations are available after explicit registration/grants. Bounded verified transfers and explicit receive approval are implemented. Sync and chat remain unavailable. Use disposable folders for development. Linux is restricted to loopback with an explicitly labelled development test vault.

First-time Rust compilation takes longer. The UI can truthfully show “Agent offline” until it is ready; select **Retry**. Stop with Ctrl+C. On Unix this is graceful; on Windows the development supervisor terminates its process tree. Native Quit uses the local shutdown RPC instead.

### Optional development environment variables

| Variable | Default | Purpose |
|---|---|---|
| `NARADA_FILE_WATCHER` | Unset | `poll` disables native hints; the 30-second reconciliation fallback remains active. |
| `NARADA_UI_PORT` | `5173` | Vite port. |
| `NARADA_PREVIEW_PORT` | `4319` | Loopback adapter port. |
| `NARADA_DATA_DIR` | `.narada-data/preview` | Alternate isolated development profile. |
| `NARADA_DEV_VAULT_KEY` | Private launcher key for a fresh Linux profile | Optional explicit 32-byte hex **test** wrapping key; never a frontend variable. |
| `NARADA_INITIALIZE_DEV_VAULT` | Unset | Set to `1` only to initialize an existing Phase 1-only Linux profile that never had a transport identity; not a key-loss recovery tool. |

Vite permits local/IP access and Arena’s `.e2b.app` preview hosts; arbitrary DNS hostnames are rejected. Add an explicitly trusted development hostname to the allowlist only when needed.

The launcher creates `NARADA_DEV_TOKEN`; do not put it in a `VITE_` variable or a frontend bundle. Treat the Vite port as a development surface for trusted users, not as a public deployment. The proxy token is not a public-web authentication system.

## Run a separate real reference agent

```sh
npm run dev:reference
```

This starts an independent profile under `.narada-data/reference/`, UI 5175 and development adapter 4321. Follow [Pairing and recovery](pairing-and-recovery.md) to pair it with the first agent. No peer is seeded or auto-approved.

## Run native desktop development on Windows

```powershell
npm ci
npm run desktop:dev
```

This prepares a **default-feature** agent sidecar, then launches Tauri with Vite on port 1420. The native shell uses authenticated local Named Pipes, not the HTTP preview adapter. The agent is a separate user process. The intended native behavior is close-to-tray, reopen from tray, and explicit Quit shutting down the agent. Execute the Windows checklist before treating these behaviors as qualified.

Windows identities use per-user DPAPI. Eligible LAN selection requires actual Windows Private/Domain profile inspection; the app never adds firewall rules. Native metadata uses the user-local app-data location resolved by `directories::ProjectDirs::from("com", "narada", "NARADA")`. It is separate from the browser preview database.

## Build

```sh
# Production frontend; this is a Tauri asset bundle, not a standalone web service.
npm run build

# Default agent: development HTTP feature is absent.
cargo build --locked -p narada-agent
```

On Windows:

```powershell
npm run desktop:build
```

The build script prepares the release sidecar and invokes Tauri's NSIS bundler. The configuration requests a current-user installer. Candidates under `target/release/bundle/nsis/` are **unsigned and unqualified** until the release gates pass. No installer from a native Windows run is supplied in this implementation handoff.

## Test

```sh
cargo fmt --all --check
cargo clippy --locked --workspace --exclude narada-desktop --all-targets --features narada-agent/dev-preview -- -D warnings
cargo test --locked --workspace --exclude narada-desktop --features narada-agent/dev-preview
cargo build --locked -p narada-agent --features dev-preview
python3 tests/process/agent_lifecycle.py
# Include real two-minute expiry, session rollover, and reconnect-backoff checks (~3–4 min).
NARADA_SLOW_TESTS=1 python3 tests/process/pairing_security.py
# Include periodic reconciliation without native hints (~40 sec).
NARADA_SLOW_TESTS=1 python3 tests/process/files_security.py
npm test
npm run format:check
npm run check:commands
npm run build
npx playwright install --with-deps chromium
npm run test:e2e
```

- The Python suites target separate **Unix development agents**, not native Windows. Their keys and profiles are disposable fixtures. The fast peer suite skips three real-time checks unless `NARADA_SLOW_TESTS=1` is set.
- Playwright starts its own real agent with `.narada-data/e2e/`, Vite port 5174 and adapter port 4320. It does not modify the normal preview workspace. Tests deliberately reset their isolated preferences.
- Current test artifacts go to `artifacts/phase-3/`; historical Phase 1/2 evidence is preserved separately. Temporary browser traces go to `.cache/playwright-results/`.
- `.github/workflows/phase-1.yml` defines phases 1–3 Linux checks and a Windows MSVC/DPAPI/IPC job. The file is supplied; this session did not execute a remote CI run.
- Automated accessibility checks supplement, not replace, screen-reader, high-contrast and native keyboard testing.

### Configuration and status

```sh
cargo run -p narada-agent -- --config config/narada.example.toml --check-config
cargo run -p narada-agent -- --data-dir .narada-data/preview --status
```

Do not run a second agent over an active workspace. An exclusive lock rejects it without replacing the owner. The status command is an explicit local inspection and includes the workspace name; logs and exported diagnostic reports do not include it.

## Troubleshooting

| Symptom | Action |
|---|---|
| `cargo` missing | Install Rust, reopen the terminal, and verify `rustc --version`. |
| Agent offline during first start | Wait for compilation, read the launcher error, then select Retry. |
| `ALREADY_RUNNING` | Use the already-running agent, or stop that process. Do not delete its live lock/database. |
| Port already in use | Stop the previous development session or choose the two port overrides above. Bind failure is surfaced and stops the new agent. |
| Browser cannot launch for tests | Install Chromium **and its OS dependencies** with the Playwright command above. |
| Metadata/storage error | Preserve the original directory. The agent intentionally does not delete/reinitialize corrupt or incompatible data. |
| Production files opened as a web page | Run the native app. A production browser without Tauri is deliberately refused. |
| Changes appear in preview, not native | These are intentionally separate metadata locations. |

Never reset user data as an automatic troubleshooting step. The source archive excludes databases, tokens, caches and generated binaries.


## Phase 4 single-pass validation

After writing the implementation and test source, run `npm run validate:phase4` on the prepared Linux development host. It runs each suite once, with `NARADA_SLOW_TESTS=1` and Playwright retries disabled. Logs and status go to `artifacts/phase-4/`; an existing start marker intentionally refuses a silent second pass. This user-requested policy does not mean failed checks are passed or native qualification is complete.

Targeted transfer source is `tests/process/transfers_security.py`, `crates/narada-files/src/transfers/tests.rs`, `desktop-ui/tests/transfers.test.js` and `tests/e2e/transfers.spec.js`. Native CI definitions are provided but remote execution is unobserved. Never use real user profiles or valuable files for destructive fixture tests.
