# Phase 4 — single-pass results and open gates

## Outcome: not validated end to end

The user-requested **single consolidated validation pass ran once**, with zero retries, on 30 October 2026. It completed with failures: **27 JavaScript tests passed; the Rust agent build failed, so dependent suites did not run.** Phase 4 is not complete or release-qualified.

The immutable [validation summary](../artifacts/phase-4/validation-summary.json) records exact commands, durations and results. The [start marker](../artifacts/phase-4/pass-started.json) prevents silent reruns. Historical Phase 3's 200 passes are not counted as Phase 4 evidence.

## Observed results

| Check | Observed result |
|---|---|
| JavaScript unit tests | **27 passed**, 0 failed, 0 skipped; includes the 8 new transfer-helper cases |
| Production UI build | **Passed**; 75 modules; CSS 67.97 kB, main JavaScript 272.08 kB, before gzip |
| Native/bridge command parity | **Passed**; 33 source entries across wrappers, handler, manifest, capabilities and bridge |
| npm audit | **Passed**; 0 reported vulnerabilities; not a security certification |
| Rust formatting | **Passed** for the source at the start of the pass |
| UI/test formatting | **Failed**; `tests/e2e/transfers.spec.js` needed Prettier formatting |
| Development agent build | **Failed**; five `E0624` private-method visibility errors in `narada-connection` |
| Rust unit tests and strict Linux Clippy | **Not run**; blocked by the agent build |
| Lifecycle, peer-security, file-security, transfer-security | **Not run**; no old Phase 3 binary substituted |
| Browser/end-to-end/accessibility suites | **Not run**; no Phase 4 screenshots generated |
| Windows GNU Clippy/link | **Not run**; native Windows execution also remains unqualified |
| Default-feature build/boundary probe | **Not run** |
| Live-preview startup/verification | **Not run**; both previews remain stopped |

The runner recorded **5 passed checks, 2 failed checks and 12 not-run stages**. `failed_tests: 0` and `skipped_tests: 0` in its aggregate are test-case counters for executed suites; they do **not** mean that blocked suites passed or that nothing was skipped at stage level.

## Narrow corrections after the failed pass — not revalidated

1. Changed `kick_transfer_jobs`, `stop_transfer_workers`, `start_transfer`, `create_transfer_offer` and `poll_transfer_offers` to `pub(super)` so their parent service module can call them. Applied rustfmt.
2. Applied Prettier to the flagged browser transfer spec; no test assertion was changed.

**No compiler, lint check, test suite or preview was rerun.** A successful backend build is not yet confirmed, and further errors may remain. Original failed logs and results are preserved unchanged. [Tested-source hashes](../artifacts/phase-4/tested-source-hashes.json) identify the source at pass start; [post-pass corrections](../artifacts/phase-4/post-pass-corrections.json) record both changed files and their before/after hashes. The source-package manifest describes the corrected handoff, not an all-passing build.

## Written coverage versus executed evidence

The 18 new Files-domain cases, 3 protocol cases, 1 connection case, 24 independent-peer/separate-agent process cases and 8 new browser cases are **written but unrun**. Existing backend/process/browser regression suites also remain unrun for Phase 4. The 8 new JavaScript helper cases ran as part of the 27 passing JavaScript tests.

Written cases cover immutable snapshots/manifests, quota/headroom bounds, approvals, empty/tail chunks, wrong digest/length/index, full-hash mismatch, checkpoints/restart, collisions, current grants/exclusions, explicit cleanup, cancelled/publication races, receipt retention, unapplied intents, real-agent pause/resume, upload approval, pull offers and concurrent job progress. Test source alone proves none of these behaviors.

The headroom/quota fixtures are not real disk/controller-full or power-cut tests. Injected intent states are not hardware durability proof. Throughput and whole-process RAM require measurement. Source parity is not native compilation or WebView execution.

## Next gate

A separately authorized, separately recorded validation pass is needed for the corrected backend and its dependent suites. Preserve the current marker, failed logs and summary; do not overwrite them or call a later run this same pass. Do not start previews with a stale 0.3 binary.

NAR-033–036 and 038–040 remain at **source-written, validation-incomplete** status. NAR-037 / Android SAF is deferred. Native Windows/MSVC/Tauri/DPAPI/ACL/share-mode/durability, physical LAN, actual disk-full/power-loss, large-file/resource measurement, installer and independent security qualification remain open. CI source is supplied; remote CI execution is unobserved.
