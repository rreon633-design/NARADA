# NARADA — Technology stack

> **Android implementation update, 31 October 2026:** the native [Phase 1 foundation](android/phase-1.md) is delivered with [82 passing host tests](android/validation.md), development APKs and a separate [phase-wise roadmap](../android-plan.md). This specification remains broader than the implemented phase; no device/crypto/LAN qualification is implied.

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Stack rules

- Preserve **HTML + vanilla CSS + vanilla JavaScript ES modules** for the desktop UI.
- Use Tauri 2 as the desktop shell, not an Electron or browser-hosted server substitute.
- Use a Rust background agent for Windows networking, filesystem access and durable work.
- Keep Android native in Kotlin and Jetpack Compose.
- Share protocol contracts, test vectors and concepts; do not force the entire Rust backend into Android through JNI.
- Native bindings required by a selected crypto library are a separate, bounded exception.
- Pin tested versions in lockfiles/toolchain files. No “latest” dependency promise is made in this document.

## Windows desktop and Rust

| Layer | Recommendation | Purpose / caution |
|---|---|---|
| Desktop shell | Tauri 2 + WebView2 | Native window, tray and narrow command bridge; plan an offline runtime installation path. |
| UI | HTML5, CSS custom properties, JavaScript modules | Semantic, framework-free interface. |
| UI build | Vite + Node.js LTS during development/build | Node is not a production backend or required runtime for end users. |
| Core language | Pinned stable Rust toolchain | Systems code and platform integration. |
| Async runtime | Tokio | Bounded asynchronous I/O; isolate blocking filesystem/hash work. |
| API | Axum | Typed HTTPS routes and authenticated WebSocket handling. |
| TLS | rustls, with reviewed certificate/key adapter | TLS 1.3 and paired identity validation; no insecure verifier shortcuts. |
| Serialization | Serde + JSON for control messages | Bounded schemas; binary bodies for file chunks. |
| General metadata DB | SQLite + SQLx | Transactions, migrations and durable job/query state. |
| Sensitive chat/session store | Encrypted SQLite adapter, preferably SQLCipher if integration passes | Separate gate for Rust binding, Android Room driver, license, WAL/backup behavior and key management. SQLx is not assumed to provide transparent SQLCipher support. |
| File watch | `notify` + periodic reconciliation | Watchers are hints, not a complete durable change log. |
| Discovery | Maintained mDNS/DNS-SD implementation | Benchmark Windows interface and firewall behavior. |
| Integrity | Maintained SHA-256 implementation | Corruption detection; not a replacement for authentication. |
| IDs | Random UUIDs or equivalent stable opaque IDs | No security based on secrecy of an ID. |
| Logging/errors | `tracing`, `thiserror`, `anyhow` as appropriate | Structured, redacted diagnostics and typed domain errors. |
| Native Windows API | Maintained Windows bindings | Named Pipes, file handles, DPAPI, notifications and lifecycle events. |
| Local IPC | Named Pipes with restrictive DACL and peer checks | Same-user typed commands; reject remote pipe clients. |
| Packaging | Tauri-compatible signed Windows installer | Qualify installer tooling, WebView2 strategy and uninstall behavior. |
| Optional UI helpers | Small QR and icon packages | Bundle assets locally; review licenses. |

Do not introduce React, Vue, Angular, Next.js, Tailwind, Redux, a Node server, Redis, PostgreSQL or Docker as product runtime dependencies without a measured requirement and approved ADR.

Plain text chat is the baseline. If Markdown rendering is added later, use an actively maintained parser and sanitizer, disable raw HTML and remote image loading, and test hostile content.

## Android

| Layer | Recommendation | Purpose / caution |
|---|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3 | Native UI, accessibility and lifecycle. |
| Architecture | MVVM with domain/repository boundaries | UI does not own networking, filesystem or cryptographic state. |
| Dependency injection | Hilt | Explicit scoped dependencies. |
| Async | Coroutines + Flow | Structured cancellation and reactive state. |
| Metadata storage | Room/SQLite | Durable jobs, indexes, cursors and state transitions. |
| Sensitive storage | Validated encrypted database adapter | Same security guarantees as Windows; no assumption that Room itself encrypts data. |
| Preferences | DataStore | Non-secret settings only. |
| Network client | OkHttp for HTTPS and WebSocket | One default network stack; qualify mTLS and Keystore integration. |
| Serialization | Kotlinx Serialization | Matches versioned protocol contracts. |
| QR scanning | CameraX + bundled decoder, or fully bundled ML Kit | Must scan without downloading a model on first use. |
| Image loading | Coil | Bounded decode and cache behavior. |
| Discovery | Android NSD | Register connectivity/lifecycle handling; stop unnecessary discovery. |
| Storage access | Storage Access Framework and app-private files | Work with content URIs; never assume every URI is a local path. |
| User-visible long transfer | Version-appropriate transfer job or justified foreground-service type | Validate user-initiated jobs / connected-device use case and fallback; comply with permissions and limits. |
| Deferrable refresh | WorkManager | Interrupted/deferred work is normal; not a permanent socket host. |
| Secret protection | Android Keystore | Protect supported keys or wrap application key material. Hardware backing is not universal. |
| Tests | JUnit, instrumentation, Compose tests | Real devices are required for networking, storage and background restrictions. |

Android's official background-transfer guide distinguishes user-initiated jobs, WorkManager and foreground services. Do not select `dataSync` merely to keep the process alive. If that service type is used, implement its applicable timeout behavior. See [R1–R3](27-references.md).

## Cryptography selection policy

| Need | Decision direction | Gate |
|---|---|---|
| Transport | TLS 1.3 with mutually authenticated paired identities | Interoperable Windows/Android prototype and downgrade tests. |
| Pairing trust | Out-of-band host key pin in QR + high-entropy single-use invitation + explicit approval | Security review of bootstrap and activation. |
| Chat E2EE | Mature maintained protocol/library with documented security properties | Platform support, distribution rights, persistence semantics, test vectors and recovery review. |
| Attachment encryption | Reviewed streaming AEAD construction/library format | No handwritten ratchet, nonce scheme or encryption format. |
| Stored secrets | DPAPI user scope on Windows; Keystore-backed wrapping on Android | Correct user context, backup exclusions and invalidation handling. |
| At-rest sensitive DB | Proven encrypted storage adapter | Wrong-key, crash, WAL, migration and restore tests. |

`libsignal` is a candidate to investigate, **not a selected or externally supported dependency**. Its upstream documentation says use outside Signal is unsupported and APIs may change. A famous protocol name does not automatically establish the suitability or audit status of NARADA's integration. See [R6](27-references.md).

If the chosen encrypted store requires a different Rust database binding, record that change before building repositories around the wrong transaction model. Do not build a custom encryption format to avoid the integration work.

## Development and verification tooling

- Git, a review-based CI service, protected release branches and reproducible build scripts.
- `rustfmt`, Clippy, Rust tests, dependency/license/advisory checks and fuzzing.
- JVM/Android lint, unit tests, instrumentation and emulator smoke tests.
- JSON Schema/OpenAPI contract validation and cross-language fixtures.
- UI DOM tests, accessibility checks and end-to-end desktop/Android tests.
- Network tracing, throughput tools and fault injection using synthetic data only.
- SBOM generation, artifact signing, checksum publication and release provenance.

Tool adoption is a recommendation; no connected repository, CI account or signing certificate is assumed to exist.
