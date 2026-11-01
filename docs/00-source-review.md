# NARADA — Review findings and how to use this plan

> [Documentation index](../README.md) · Planning baseline v1.0 · 30 October 2026  
> Proposed design; implementation and validation are still required.

## Review conclusion

The supplied plan has a sound foundation: local-first operation, native Android, a Rust backend, a lightweight desktop interface, bounded transfers, explicit permissions, and durable messaging. NARADA preserves that direction while making release boundaries, implementation dependencies, security claims, recovery behavior, and acceptance criteria more precise.

This is a **review and redesign of the plan plus static review of the supplied UI prototypes**, not verification of a production application. No native app build, production dependency manifest, device-test report, penetration test, or benchmark was supplied. All feature completion, compatibility, capacity, and performance claims require implementation evidence.

## Important corrections and clarifications

| Topic | Gap or ambiguity in the source | NARADA planning decision |
|---|---|---|
| Product naming | Previous name throughout code examples and backlog | Use NARADA, `narada-*` packages, and `NAR-*` task identifiers consistently. |
| Release scope | Several capabilities appear both essential and optional | Separate an internal prototype, Transfer Preview, full 1.0, and later releases. |
| Delivery order | File browsing precedes the secure network foundation | Build identity, authorization and pairing before exposing file APIs to a LAN. |
| Windows background process | A per-user process and a machine-wide service are treated interchangeably | Recommend a non-elevated, per-user Rust background agent for 1.0; an SCM service requires a separate decision. |
| Windows compatibility | Windows 10/11 appears as an unconditional target | Prioritize supported Windows 11 builds. Windows 10 is a separately tested exception, not a promise of OS security support. |
| Desktop chat storage | Windows table list omits the chat state needed by a Windows participant | Include conversations, outbox, receipts, attachment state and cryptographic session storage on **both** endpoints. |
| E2EE trust boundary | A Windows server process is said to be unable to read any chat plaintext | The Windows chat endpoint must decrypt its own conversation. E2EE protects between endpoints, not against the recipient device or malware controlling it. |
| Crypto dependency | “Use Signal Protocol” does not settle library support, licensing or persistence integration | Make cross-platform E2EE selection and transaction-safe state storage a Phase 0 release blocker. |
| Android background work | A foreground service can sound like an always-running guarantee | Select lifecycle APIs by OS version and task; handle restrictions, timeouts, process death and force-stop honestly. |
| Android file destinations | Atomic rename and random access are implicitly assumed for every destination | Distinguish app-private files from SAF providers. Stage locally when necessary and report export completion separately. |
| Transfer consistency | A path and timestamp can be mistaken for a stable source version | Bind transfers to an immutable snapshot or a validated stable file handle and content manifest. |
| Sync meaning | Basic sync can be read as unrestricted two-way folder synchronization | 1.0 provides metadata synchronization and one-way refresh of pinned content. Automatic two-way editing/deletion is later and opt-in. |
| Delivery semantics | Transport acceptance can be confused with receipt by the intended endpoint | Require durable, authenticated recipient processing before `delivered`; track attachment availability independently. |
| Filesystem/database commit | A rename and a database transaction are not one atomic operation | Use a recoverable commit-intent protocol and startup reconciliation. |
| Offline-first dependencies | Scanner models, WebView runtimes or cloud-backed storage may still need internet | Test a no-internet first use; bundle required runtime/model dependencies or document offline installation prerequisites. |
| Delivery management | Backlog and success targets are broad | Add task IDs, owners, dependencies, phase exit gates, measurable targets and release blockers. |
| Desktop visual direction | Later supplied images and HTML establish the requested appearance | Use their monochrome visual system and 16-screen structure; the detailed Files image guides the file workspace. |
| UI prototype versus functionality | The HTML contains a decorative QR, static statuses, simulated actions and incomplete navigation/theme behavior | Preserve useful layout/components, replace fake state with typed domain data, and apply the production checklist in [Desktop UI specification](12-desktop-ui.md). |

Microsoft documents the end of standard Windows 10 Home/Pro support in October 2025; edition-specific lifecycle and any applicable update arrangements must be checked separately. Android documentation explicitly describes foreground-service limits and provider-dependent storage operations. These are material planning constraints, not small implementation details. See [references](27-references.md).

## Reading and maintenance conventions

- **Must / P0:** Required for the stated release gate; not a claim that it already works.
- **Should / P1:** Valuable follow-up unless explicitly promoted through scope review.
- **Candidate / validation gate:** A proposed technical choice that needs a prototype, licensing check or security review.
- **Target:** A proposed measurement threshold, never an observed result.
- Examples of schemas, routes, configuration and commands are **design examples**, not a ready-to-run repository.
- Record approved changes in an Architecture Decision Record (ADR), then update scope, tasks, tests and user-facing claims together.
- Prefer cutting a feature over weakening authentication, integrity, permission checks or data-loss safeguards.

## Additional mobile reference review

The subsequently supplied Android image and `IFB_Android_Mobile_All_Screens.html` are the mobile visual reference. Use their monochrome style and bottom navigation in a native Kotlin/Compose application, not a production HTML webview. The HTML labels itself “16 mobile screens” but contains 17 screen sections. Pairing, filters, transfers and statuses are simulated. See the dedicated [Android UI specification](13-android-ui.md) for the corrected screen inventory and production requirements.

Both original UI prototypes are preserved unchanged under `references/`; their earlier branding and sample data are source material, not NARADA implementation claims.
