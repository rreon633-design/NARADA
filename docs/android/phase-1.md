# Android Phase 1 — implementation scope

[Roadmap](../../android-plan.md) · [Project/setup](../../apps/android/README.md) · [Validation](validation.md)

## Completion status — 31 October 2026

**Phase 1 development implementation is complete.** Final source passed **22 domain + 60 Android host tests**, with zero failures/skips, and debug lint plus application/instrumentation APK packaging. Artifact permissions, debug signature and static alignment were checked. No physical device, emulator or remote CI execution is claimed. See the [exact validation and earlier failures](validation.md).

## User request

Start the Android mobile application, write a separate phase-wise Markdown development plan and implement Phase 1 using the existing NARADA architecture and supplied mobile references. This newly authorizes Android work; earlier desktop-only deferral is historical.

## Delivered source surfaces

| Area | Implementation |
|---|---|
| Scaffold | Independent Gradle wrapper/project; platform-free Kotlin domain module; Android application module; exact dependency catalog, lock and verification configuration |
| Native entry | Hilt application/activity, native splash, system insets, Compose/Material 3 light theme |
| Setup | Three native onboarding steps, validated device label, durable completion; no automatic permission prompt |
| Shell | Home, Files, Chat, Transfers, More; nested Sync, Offline files, Devices, Notifications, Settings, Help, Storage, Appearance and Diagnostics |
| State | Lifecycle-aware Flow/ViewModel observation, SavedStateHandle draft, saved navigation/scroll state, unsaved-name Back/tab guard |
| Metadata | Room schema 2, 1→2 migration fixture, bounded generic activity, one local-check slot, eight URI grant records |
| Recovery | No destructive migration/corruption replacement; visible storage failure; preserved corrupt preferences; missing-enrolled-database gate |
| Local access | App-private probe files; native tree picker; exact offered read/persist flags; pending-before-OS grant record and actual retention/revocation reconciliation |
| Background ownership | Stable UUID, unique KEEP scheduling, application-owned command, durable cancellation, stale-worker guards, bounded real local checks |
| Notifications | User-contextual permission, private channel/content, immutable pending intent, optional test notification and Android settings action |
| Support | Typed allowlisted report, review-before-share, scoped FileProvider and temporary read-only URI grant |
| Tests | Domain policies, Room/migration/loss/corruption, DataStore, SAF policy, WorkManager ownership/worker, ViewModel and Compose tests; native instrumentation smoke source |

Implementation status must be read together with the validation record. A source surface is not automatically a device-qualified acceptance check.

## Honest empty states

| Destination | Phase 1 behavior | Future owner |
|---|---|---|
| Devices | This phone is identified correctly; no paired laptop/QR/PIN | Phase 2 |
| Files | No remote tree; real link to local grant settings | Phase 3 |
| Transfers | No file queue/progress; diagnostics remain separate | Phase 4 |
| Chat | No message composer, demo conversation or delivery status | Phase 5 |
| Attachments | No plaintext substitute | Phase 6 |
| Sync / Offline files | No fake watcher, freshness badge or pinned content | Phase 7 |

## Safety-focused acceptance

- No Internet, camera, broad storage or foreground-service capability in this phase.
- No plaintext content keys/chat store; sensitive-content capability is explicitly unavailable.
- No destructive reset masquerading as recovery.
- No report field accepts a device label, file path, URI, raw exception, key or message.
- No side effect is triggered merely by a Composable rendering or a tab reopening.
- Native-only permission, lifecycle, accessibility, backup and provider checks remain explicitly unexecuted until a real Android environment is used.

## Success measures

Development foundation acceptance requires a debug APK build, passing available tests/lint and honest native-gate documentation. Release acceptance additionally requires an installed/emulated/device-tested application, supported-device matrix, physical interoperability, security review, signing custody, dependency/legal review and a pilot. Neither a checksum nor a screenshot proves those release gates.
