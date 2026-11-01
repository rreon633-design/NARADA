# NARADA — Development Setup and Configuration

> **Current implementation:** desktop phases 1–3 have a development implementation; Android and native Windows/LAN/filesystem/security qualification remain open. See [Phase 3 implementation](phase-3-implementation.md), [actual file contract](phase-3-protocol.md), and [live tracker](../plan.md). The wider development setup below includes future features, not claims that those features are shipped.

> **Implementation update:** the actual desktop commands and observed status are in [Desktop development](desktop-development.md), [Phase 1 implementation](phase-1-implementation.md) and the [live root tracker](../plan.md). The broader roadmap below is not a completion claim.
> [Documentation index](../README.md) · Proposed setup for a future application repository · 30 October 2026

## 1. Important scope note

This delivery is a documentation pack. It does **not** contain an initialized Rust/Tauri/Android application. Commands below describe the intended workflow **after the application repository and scripts are created**. Do not expect `cargo build` or Gradle to build this Markdown folder.

Use [Architecture](04-architecture.md) for the proposed source tree and [Task backlog](15-task-backlog.md) for the initialization tasks.

## 2. Windows prerequisites

- A qualified Windows development machine, initially supported Windows 11 x64.
- Git and a pinned Rust toolchain with the required Windows target/native build tools.
- Tauri 2 prerequisites and an appropriate WebView2 runtime/development setup.
- Node.js LTS and a chosen package manager for frontend build tooling only.
- Android Studio, Android SDK/emulator, platform tools and a JDK compatible with the pinned Gradle/Android build stack.
- Additional native tools required by the chosen cryptography/encrypted-storage libraries.
- Real Windows/Android test hardware and a router that can also operate without internet.

Check current official requirements before freezing toolchains. If a crypto library requires a specific JDK/NDK/compiler, reconcile that with the application's build stack during feasibility—not at release time.

## 3. Repository initialization sequence

1. Create the application monorepo and documented licenses/distribution policy.
2. Initialize Cargo workspace, Windows agent and Tauri shell.
3. Initialize the vanilla HTML/CSS/JS Vite project.
4. Initialize the Kotlin/Compose Android project and Gradle wrapper.
5. Commit lockfiles, toolchain definitions, formatting rules and non-secret examples.
6. Add schemas/test vectors and basic CI for both platforms.
7. Add explicit development fixture adapters, isolated from release builds.
8. Add the protected-store adapter, migration harness and contract tests before production network routes.

No source hosting URL, application identifier or release certificate is invented here. Choose real owned identifiers before distribution; use development-only identifiers until then.

## 4. Proposed commands

Toolchain checks:

```powershell
rustc --version
cargo --version
node --version
npm --version
adb version
```

Once the planned Rust workspace exists:

```powershell
cargo build --workspace --locked
cargo test --workspace --locked
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
```

Once the UI package and its scripts exist:

```powershell
cd desktop-ui
npm ci
npm run dev
npm run build
```

Once the Android project exists:

```powershell
cd android
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

The instrumentation task requires an appropriate emulator/device. Production signing keys must not be used in ordinary debug tasks.

An intended agent command might be:

```powershell
cargo run -p narada-agent -- --config .\config\narada.dev.toml --foreground
```

That package/CLI is a design proposal until NAR-009/NAR-011 implement it. Document the actual command after repository initialization rather than leaving stale examples.

## 5. Non-secret configuration example

Illustrative TOML; keys must be implemented/validated by the configuration module:

```toml
[app]
name = "NARADA"
log_level = "info"

[network]
listen_policy = "selected_private_interfaces"
allowed_interface_ids = [] # No LAN listener until an interface is approved.
port = 45821
allow_public_profile = false
discovery_enabled = true
minimum_tls_version = "1.3"

[pairing]
invitation_ttl_seconds = 120
bootstrap_listener_on_demand = true
local_confirmation_required = true

[transfer]
chunk_size_bytes = 4194304
max_active_transfers = 2
max_inflight_chunks_per_transfer = 2
control_json_limit_bytes = 1048576
inactive_session_ttl_days = 7

[sync]
mode = "metadata_and_pin_refresh"
automatic_remote_delete_propagation = false

[privacy]
notification_body_previews = false
diagnostic_upload_mode = "manual_only"
optional_update_checks = false

[storage]
metadata_database_filename = "narada.db"
protected_database_filename = "narada-private.db"
log_max_bytes = 20971520
log_retention_days = 7
```

Do not put invitation secrets, private keys, database keys or update-signing credentials in TOML, `.env`, source control or frontend storage. Resolve data directories through platform APIs and protect them with appropriate ACLs.

## 6. Development safety rules

- Production authorization remains enabled during routine integration tests.
- If an unauthenticated mock adapter exists for UI development, make it impossible to enable accidentally in release builds and do not expose real files through it.
- Use synthetic files and dedicated test identities; never publish their private keys as production fixtures.
- Scope any local development server to the development environment. It is not the product's LAN file service.
- Keep protocol major/app/database versions separate.
- Use deterministic fixtures only at approved test seams; production keys/invitations need cryptographic randomness.
- Keep generated artifacts out of source review unless required; retain build provenance and manifests.

## 7. Offline-capable release prerequisites

A production first-use test must not unexpectedly download a scanner model, font, JavaScript asset or WebView runtime. Plan the offline installation strategy and its licensing/distribution requirements explicitly.

Developer dependency installation can need internet. The product's installed core functionality must not need it. A cloud-backed SAF provider is a separate user-chosen dependency and must be identified as such.
