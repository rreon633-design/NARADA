# Transfers — operation, approval and recovery

> **Development workflow, not validated runtime evidence:** the Phase 4 backend failed to build in the single pass. Corrected source remains unverified and previews are stopped. Use disposable data only after a successful build and separately recorded validation. See [results](phase-4-validation.md).

## Download

1. Pair a real peer. The remote owner grants Browse + Read for a registered root.
2. In **Files**, choose that saved outbound peer and select an ordinary file ≤64 MiB.
3. Choose **Download file**, a local approved destination root, and a filename. No overwrite is available.
4. Open **Transfers** to see actual durable checkpoints. 100% staged can still be verifying/publishing; only **Completed** records publication.

## Upload and receive approval

Select a local file → **Send to device**. Choose a saved outbound peer, a remote Browse + Create root, and the destination filename. The receiver sees **Incoming receives → Review**. It can approve a filename, decline, pause, or explicitly rename a collision before retry. Create permission alone does not authorize unattended acceptance.

## Inbound-only peer / pull offer

The inviter normally has no outbound endpoint for the joining device. **Offer for download** therefore creates an immutable, actor-bound offer only for a peer with existing Browse + Read access. The recipient uses **Check for offers**, chooses a local destination, then pulls over its own saved pinned connection. No inferred reverse connection or new grant is created.

## Pause, cancel, retry

- Pause persists; after agent restart active local jobs are paused for owner review. Resume rehashes saved receive checkpoints before more bytes are accepted.
- Local cancellation stops further local work and marks local staging/snapshots as appropriate. Already admitted IO can settle. It cannot retract published remote files or prove a lost remote commit did not happen. A receiver may retain its separately approved partial receive until its owner cancels/cleans it; there is no promised remote-file undo.
- Retry preserves the transfer ID, manifest and original destination parameters. Receiver-approved rename is a distinct owner review, never an overwrite fallback. Stale-source errors require selecting the current file and starting a new reviewed transfer.
- Recoverable network interruptions get up to three automatic retries with bounded pacing. Permission, integrity, namespace and publication-uncertainty errors block for review. No trust pin is changed by retry.

## Crash decisions

| Observed state | Action |
|---|---|
| ACKed chunk bytes intact | Keep checkpoint |
| ACKed chunk digest uncertain/mismatched | Mark missing; never count as verified |
| Interrupted source preparation | Fail that snapshot; never serve a partial manifest |
| Intent + exact destination identity/full hash, stage absent | Reconcile durable receipt, without publishing again |
| Intent + recognized stage still present, destination absent | Block `COMMIT_NOT_APPLIED`; no automatic rename |
| Destination exists with another identity | Preserve both; explicit filename review |
| Neither location or identities/digests ambiguous | `recovery_required`; preserve evidence, no blind adoption |
| Unrecognized private staging directory | Refuse adoption; preserve it for investigation |

Unresolved file or transfer publication intents also block new publication and ordinary managed mutations in that root. Reads remain subject to current identity and policy; no destructive reset resolves an ambiguous intent.

Cleanup is **explicit** and serialized with managed IO. It removes identity-matched eligible snapshot/stage files only. Live jobs, unresolved intents, originals, published destinations and unknown orphan objects are not purged. Removed/unavailable roots can require restoration of the original directory identity before cleanup. History/quotas can therefore deliberately block new work rather than delete uncertain data. Explicit cleanup may prune terminal receipts at least 24 hours after publication, once no unfinished local job references them. Idempotency history is bounded, not an infinite ledger. Published files are never deleted by that metadata pruning.

Use disposable data until [native qualification](windows-validation.md) and independent review are complete. Do not delete a real profile or regenerate its identity to “fix” a missing vault key.
