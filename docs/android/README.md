# NARADA Android documentation

[Current handoff](../../ANDROID-PHASE6-7-HANDOFF.md) · [Build / tech stack](../../apps/android/README.md) · [Phase-wise plan](../../android-plan.md)

## Current — Phases 6 & 7, version 0.7.0

**Development implementations complete; native/independent/release gates remain open.** Two-way age attachments and version-preserving offline copies retain the earlier pairing/files/download/chat behavior. The phone remains outbound-only. The desktop upload extension was explicitly authorized; historical desktop evidence was not rerun or relabeled.

| Requested documentation area | Read next |
|---|---|
| README / scope / features | [Phase 6](phase-6.md), [Phase 7](phase-7.md), [handoff](../../ANDROID-PHASE6-7-HANDOFF.md) |
| Tech stack | [Project pins/build](../../apps/android/README.md), [age/Olm crypto and review gate](attachment-crypto.md) |
| Architecture | [Native ownership and protected state](architecture.md) |
| Workflow / process / end-to-end | [Pair, import, manifest, upload, export, pin and adopt](phase-6-7-workflow.md) |
| Mechanisms / communication | [Attachment protocol](attachments-protocol.md), [scoped metadata/pin protocol](offline-protocol.md) |
| Tips and tricks | [Practical checks](phase-6-7-tips.md) |
| Precautions / recovery | [Attachments](attachments-recovery.md), [offline versions](offline-recovery.md), [general storage/privacy](security-storage.md) |
| Tasks / success metrics | [Phase6 task map](phase-6.md), [Phase7 task map](phase-7.md), [796-check evidence](phase-6-7-validation.md) |
| Problems and solutions | [Failed attempts, corrections and memory/ABI handling](phase-6-7-problems-solutions.md) |
| Phase-wise plan / remaining tasks | [Roadmap](../../android-plan.md), [native/independent/release checklist](phase-6-7-device-checklist.md) |
| Packaging / history | [Source/APK delivery and exact old-artifact recovery](artifact-recovery.md) |

**Measured:** 796 checks passed; 223 Android inputs matched across final stages; 101 current desktop input files/binary fingerprinted. Lint: 0 errors/warnings, 36 Hints. Both APK builds and static/provenance audit passed. **13 instrumentation cases compiled, 0 executed.** No Android/emulator/Windows/remote-CI execution or independent approval. The supplied signer differs from Phase5; no in-place upgrade is qualified.

## Retained stages and mechanisms

| Earlier stage | Preserved documentation |
|---|---|
| Phase5, 581 checks | [Scope](phase-5.md), [validation](phase-5-validation.md), [crypto](chat-crypto.md), [protocol](chat-protocol.md), [recovery](chat-recovery.md), [native checklist](phase-5-device-checklist.md) |
| Phase4, 479 checks | [Scope](phase-4.md), [validation](phase-4-validation.md), [transfers](transfers-protocol.md), [recovery](transfers-recovery.md), [SAF export](saf-export.md), [native checklist](phase-4-device-checklist.md) |
| Phase3, 297 checks | [Scope](phase-3.md), [validation](phase-3-validation.md), [files](files-protocol.md), [recovery](files-recovery.md), [workflow](phase-3-workflow.md) |
| Phase2, 186 checks | [Scope](phase-2.md), [validation](phase-2-validation.md), [connection protocol](connection-protocol.md), [pairing](pairing-workflow.md), [security](connection-security.md) |
| Phase1, 82 checks | [Scope](phase-1.md), [validation](validation.md), [device checklist](device-checklist.md) |

These earlier phase documents retain their statements as of their own handoffs. Their “not yet enabled” features and smaller payload versions are historical boundaries, not conflicting claims about current v0.7.0. Earlier native/review gates are not waived by later features. Desktop single-pass failures and unvalidated corrections retain their original meaning.
