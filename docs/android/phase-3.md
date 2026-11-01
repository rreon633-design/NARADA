# Android Phase 3 — authorized files

[Documentation index](README.md) · [Handoff](../../ANDROID-PHASE3-HANDOFF.md) · [Roadmap](../../android-plan.md)

## Status and scope

**Development implementation delivered, 31 October 2026.** App 0.3.0 (3), Room 3, sealed trust payload 2. The final campaign passed **297 checks** with clean error/warning lint and both APK builds. This is source/host/real-Linux-agent evidence, not device or release qualification. [Exact validation](phase-3-validation.md)

The user’s mobile screenshot and `IFB_Android_Mobile_All_Screens.html` guide monochrome surfaces, native controls, cards and selection/preview flow. Prototype sample files and progress are not production data. Files now has its own Compose route; Home/Chat/Transfers/More remain native. Chat/transfers/sync/offline engines stay in their planned later phases.

## Work packages

| ID | Implemented | Where |
|---|---|---|
| AND-301 | Typed strict peer file requests/replies; opaque IDs; granted-root validation | `core/connection/.../FilesProtocol.kt` |
| AND-302 | Pinned current-permission client; pre/post checks; bounded peer file route | `FilesClient.kt`, `Transport.kt`, `Trust.kt` |
| AND-303 | Folder/breadcrumb navigation, explicit search/sort, bounded page history, stale/error handling | `FileBrowser.kt` |
| AND-304 | JPEG envelope/structure/size checks before native bounds/decode checks | `FilesProtocol.kt`, `ui/screens/FilesScreen.kt` |
| AND-305 | Explicit managed actions, sealed pre-send intent, matching durable receipt, exact retry/dismissal | `FilesClient.kt`, `ProtectedConnections.kt` |
| AND-306 | Serialized foreground file lease under existing connection owner; route/lifecycle/storage fences | `ConnectionsCoordinator.kt`, `files/FilesViewModel.kt` |
| AND-307 | Native reference-aligned Files UI, secure sensitive windows, non-saved drafts, 48dp controls and scrollable confirmations | `FilesScreen.kt`, `WorkspaceApp.kt`, `MainActivity.kt` |
| AND-308 | JVM, real-agent, repository, ownership, UI and compiled native-test source | Core/app test directories; separate Phase 3 artifacts |
| AND-309 | Split workflow/protocol/recovery/validation/device docs and artifact-bound handoff | This documentation set |

All source paths above are under `apps/android`; core Kotlin package is `com.narada.connection`, application package is `com.narada.android`.

## Actual behavior

- **Roots:** only peer-visible, currently granted roots from `/api/v1/files`. Root state/capabilities come from the desktop and must match fresh health grants. Saved pairing alone grants nothing.
- **Browse:** at most 16 current rows. Folder and breadcrumb navigation uses IDs, never concatenated paths. A display-only relative label is validated but never sent back as authority or passed to Android IO.
- **Search/sort:** submit a name query of 1–128 UTF-8 bytes within one root. Sort is name ascending/descending, modified or size, exactly as the server returns it.
- **Pagination:** replace the current page; retain at most 33 cursor positions (32 previous), not an accumulating file list. Index changes, reused cursors and server expiry fail explicitly. Refresh starts again; no cross-epoch merge.
- **Details/preview:** details are re-read and must match the selected snapshot. Only current `read` grants permit pixel previews. Native decode has its own MIME/dimension/allocation checks; no active document or external application is opened.
- **Mutations:** rename uses `modify`; trash uses `trash`; restore uses `trash` and `create`, always with `browse`. Confirmation is separate and shows the selected object. Only matching, saved committed receipts produce success.
- **Unknown outcomes:** one unresolved mutation per peer blocks new mutations. No automatic network retry of mutations. A later explicit retry is same pairing, operation ID and arguments. Dismissal is labeled unconfirmed, not success/cancel.
- **Failure/privacy:** file request failures discard cached roots/rows/selection/pixels. Authority, route and lifecycle changes invalidate owned work. Successful views expire after 60 idle seconds; closing details does not cancel that deadline. No saved-instance-state file names/search/selection/cursors or on-disk content cache.

Read [workflow](phase-3-workflow.md), [protocol/limits](files-protocol.md) and [recovery](files-recovery.md) for exact semantics and caveats.

## Honest non-goals

- A thumbnail is **not a verified download or offline availability**.
- No upload, download queue, resumable byte transfer, original-file sharing or SAF export is implemented.
- No local filesystem path access, new FileProvider content surface or broad storage permission is added. The existing provider remains diagnostic-report-only.
- Local Storage’s persisted read-only SAF grants are unrelated to desktop grants; changing a phone grant cannot repair a remote root/index/ACL error.
- No new Android server/listener/background presence or owner endpoint.
- No automatic deletion, overwrite, nonempty-directory mutation, permanent delete, journal wipe, identity replacement or software-key fallback.

## Host render examples

These are **real Compose host captures with synthetic fixture data**, explicitly labeled in-image; they are not phone screenshots or network proof.

- [Granted roots](../../artifacts/android-phase-3/runs/host-03/host-renders/11-files-roots.png)
- [Browsing/search](../../artifacts/android-phase-3/runs/host-03/host-renders/12-files-browse.png)
- [200% type](../../artifacts/android-phase-3/runs/host-03/host-renders/13-files-font-200.png)
- [Denied state](../../artifacts/android-phase-3/runs/host-03/host-renders/14-files-denied.png)

## Exit criteria and next phase

Actual entries, permission rechecks, denied/offline/revoked/stale cases, hostile identifiers, bounded previews, protected managed actions and recovery are implemented and covered by the measured development campaign. **Native qualification is still open**, not converted into completed checkboxes. Continue with **Phase 4 — verified transfers** using immutable manifests/checkpoints and separate, explicitly approved SAF export; do not expand this preview route into a download engine.
