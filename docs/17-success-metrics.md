# NARADA — Success Metrics and Acceptance Targets

> [Documentation index](../README.md) · Initial targets, not measured results · 30 October 2026

## 1. Measurement principles

A useful metric includes a definition, denominator, environment, sample size, owner and evidence artifact. Report failures and unsupported conditions separately instead of quietly excluding them from a favorable percentage.

- Record app/protocol/OS versions, device class, filesystem/provider, router, signal conditions and dataset.
- Separate preparation, transfer, verification and external export time.
- Distinguish process-crash recovery from actual power-loss/storage-durability tests.
- Avoid peak-throughput marketing based on a single favorable run.
- Keep measurement local or explicitly consented. No mandatory analytics service is required.

## 2. Proposed targets

| Area | Definition and initial target | Measurement / owner |
|---|---|---|
| Pairing success | At least 98% successful valid attempts on the supported controlled LAN matrix | At least 200 attempts; invalid/expired attacks tracked separately. QA/CORE/AND. |
| Pairing latency | p95 under 15 seconds of system processing after valid scan, excluding user approval delay | Record scan, connection, confirmation and commit timestamps. QA. |
| Discovery | p95 under 5 seconds after an active discovery request on supported multicast-capable networks | At least 200 trials; isolation/multicast-blocked networks reported as a separate failure class. CORE/AND. |
| File browsing | Warm indexed first page of 100 entries p95 under 500 ms | Dataset around 50,000 indexed entries; cold-cache result reported separately. CORE. |
| Integrity | Every file labeled completed passed required final size/hash verification | 100% of completed test transfers; any unverified success is a blocker. QA. |
| Resume | No restart of proven durable completed chunks for the same valid manifest | At least 100 injected disconnect/restart trials; uncertain tail revalidation permitted and measured. QA. |
| Commit safety | Zero duplicate final outcomes or silent overwrite in lost-reply/crash tests | At least 1,000 scripted commit-boundary fault runs before stable release. CORE/QA. |
| Transfer performance | Initial goal: median payload throughput at least 70% of a same-path simple TLS stream baseline | Files of at least 1 GiB; same storage/network/devices; include hash/preparation overhead separately. QA. |
| Chat latency | Connected foreground 1 KiB text message p95 delivered under 750 ms on the declared LAN | At least 1,000 messages; recipient durable commit included. CORE/AND. |
| Chat semantics | Zero false delivered states and zero duplicate visible messages | Lost receipts, retries, reorder and crashes; hard gate. QA/SEC. |
| Pin safety | Existing verified pin stays usable after every failed refresh case | Version-change, disk-full, network-loss and process-death matrix. AND/QA. |
| Sync recovery | Every tested expired cursor or watcher gap recovers without silent loss | Snapshot/journal/conflict tests; correctness is the gate, not one universal scan duration. CORE/QA. |
| Authorization | All protected routes, chunks, previews, events and commit paths enforce current scope | Negative authorization matrix; zero unauthorized success. SEC. |
| Revocation | New authorized work stops immediately at the server's revocation boundary; active workers stop at bounded checks | Measure control processing/chunk cancellation; bytes already in flight are excluded from impossible recall claims. CORE. |
| Windows idle CPU | Provisional goal below 1% average total-machine CPU over 10 quiet minutes | 50k indexed entries, one paired peer, no scan/transfer, UI hidden; record hardware. CORE/QA. |
| Windows idle memory | Provisional agent working-set target at or below 120 MiB | Separate UI/WebView measurement; calibrate after prototype and track regression budget. CORE. |
| Streaming buffers | Initial combined active transfer-buffer budget at or below 64 MiB per endpoint | Large-file tests; report total process memory separately. CORE/AND. |
| Android stability | No unhandled process crash or duplicate job in required lifecycle tests | Rotation, background stop, force-stop/reopen, low-memory death, timeout and URI loss. AND/QA. |
| Usability | At least 90% of test users complete pair → download → offline open without help | Initial study of at least 20 representative users; privacy-preserving observations. PM/QA. |
| Accessibility | All P0 flows operable with keyboard on desktop and TalkBack on Android | Contrast, focus, touch-target, text-scale and IME checklist. PM/QA. |
| Pilot reliability | Initial target at least 99.5% crash-free eligible pilot sessions | Minimum 1,000 consented sessions; define sessions and disclose incomplete reporting. QA/REL. |
| Support readiness | Every common failure category has an error code, safe user action and runbook | Documentation review against the risk/acceptance matrix. REL. |

Resource/performance targets may be recalibrated in Phase 0–4 with published rationale. Data-integrity, authorization and delivery truthfulness are hard correctness requirements, not adjustable percentages.

## 3. Formulas and consistent definitions

```text
Pairing success rate = successful valid attempts / all valid supported attempts
Transfer payload throughput = verified payload bytes / active payload-transfer seconds
Resume waste ratio = retransmitted already-durable bytes / total received bytes
Crash-free session rate = sessions without unhandled crash / eligible recorded sessions
Task success rate = participants completing the task unaided / participants attempting it
Conflict-loss count = detected conflicts with a silently discarded required version
```

- **Session:** For the pilot, define an app-active period with a documented inactivity boundary; record crash attribution consistently across agent/UI/mobile components.
- **p95:** A latency exceeded by 5% of samples; always include sample count and environment.
- **Completion:** End-to-end verification and durable publication in the relevant storage domain. SAF export may be a separate completion event.
- **Resume waste:** Uncertain unflushed tail data is reported separately from known durable chunks.

## 4. Benchmark datasets

- Empty file, 1 KiB, 1 MiB, 100 MiB, 1 GiB and a provisional 20 GiB large-file qualification case where storage permits.
- Thousands of small files, long/deep names, Unicode/case collisions and large directories.
- Compressible text and already-compressed media/archive content.
- Stable sources, busy/mutating sources and non-seekable/unknown-length providers.
- Idle LAN, busy LAN, weak signal, client isolation, internet-disconnected Wi-Fi and network changes.

The 20 GiB case is a test objective, not a promised maximum or minimum until qualified.

## 5. Evidence and review cadence

Store a report with build ID, test method, raw summary, environment, confidence/limitations and deviations. Review metrics after each phase and before changing a resource-sensitive dependency.

Local dashboards should show aggregate operation counters and failure codes without filenames, message text or keys. Pilot exports remain user-inspected and optional.

## 6. Release blockers

No release with a known authentication bypass, high/critical security issue, silent corruption, false-delivery state, conflict overwrite, unsafe crypto-state rollback or untested destructive migration. A green average latency does not compensate for a red correctness gate.
