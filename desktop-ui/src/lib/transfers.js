import { validMutationName } from "./files.js";
export const MAX_FILE_BYTES = 64 * 1024 * 1024;
export const RUNNING = new Set([
  "queued",
  "running",
  "retry_wait",
  "awaiting_approval",
  "receiving",
  "committing",
]);
export const ATTENTION = new Set(["blocked", "failed", "recovery_required"]);
export function transferBytes(value) {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,9})$/.test(value))
    throw new Error("Invalid transfer byte count.");
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n > MAX_FILE_BYTES)
    throw new Error("Transfer exceeds the 64 MiB development limit.");
  return n;
}
export function progressOf(job) {
  const size = transferBytes(job.size),
    verified = transferBytes(job.verified_bytes);
  if (job.state === "completed" && verified !== size)
    throw new Error("Completion requires all bytes and a publication receipt.");
  if (verified > size) throw new Error("Checkpoint exceeds the manifest size.");
  return {
    size,
    verified,
    percent:
      job.state === "completed"
        ? 100
        : size
          ? Math.floor((verified / size) * 100)
          : 0,
  };
}
export function transferLabel(job) {
  if (job.state === "running")
    return (
      {
        preparing: "Preparing snapshot",
        manifest: "Verifying manifest",
        transferring: "Transferring",
        verifying: "Verifying & publishing",
      }[job.phase] || "Preparing"
    );
  return (
    {
      queued: "Queued",
      completed: "Completed",
      paused: "Paused",
      retry_wait: "Retry scheduled",
      awaiting_approval: "Awaiting approval",
      receiving: "Receiving",
      committing: "Publishing",
      cancelled: "Cancelled",
      cleaned: "Staging cleaned",
      failed: "Failed",
      blocked: "Needs attention",
      recovery_required: "Recovery required",
      ready: "Ready to collect",
    }[job.state] || "Unavailable"
  );
}
export function filterTransfers(jobs, filter) {
  if (filter === "active")
    return jobs.filter((j) => RUNNING.has(j.state) || j.state === "paused");
  if (filter === "completed")
    return jobs.filter((j) => j.state === "completed");
  if (filter === "attention") return jobs.filter((j) => ATTENTION.has(j.state));
  return jobs;
}
export function transferHint(code) {
  return (
    {
      NAME_COLLISION:
        "Nothing was overwritten. The receiver can choose a different filename, then retry the same transfer.",
      HASH_MISMATCH:
        "Integrity verification failed. Resume only the same manifest; never treat this as delivered.",
      RECEIVE_NOT_READY:
        "The receiver must approve or resume this receive. Then retry this job.",
      RESTART_PAUSED:
        "Paused safely after restart. Resume rechecks saved chunk checkpoints.",
      COMMIT_OUTCOME_UNKNOWN:
        "Publication is uncertain. Preserve both locations and inspect recovery; do not assume delivery.",
      TRANSFER_NOT_READY:
        "The source is unavailable or finished. Refresh its current state; a changed source needs a new transfer.",
      TRANSFER_EXPIRED:
        "The retained snapshot expired. Start a newly reviewed transfer.",
      TRANSFER_QUOTA:
        "The bounded reservation or retained history is full. Cancel unused work and clean eligible staging.",
      STORAGE_FULL:
        "Staging space is insufficient. Free space without removing unknown files, then retry.",
      ACCESS_DENIED:
        "Current folder permissions or exclusions no longer allow this operation.",
      PERMISSION_DENIED:
        "The current peer grant does not permit this operation.",
      SESSION_INVALIDATED:
        "Permissions or trust changed. Review both devices before retrying.",
      SOURCE_CHANGED:
        "The approved source moved or changed. No other version was substituted.",
      STAGING_CHANGED:
        "The saved staging identity changed. Unknown data has been preserved, not adopted.",
      NETWORK_DISABLED:
        "Enable an approved connection before retrying. No background internet fallback is used.",
    }[code] ||
    (code
      ? `Stopped safely: ${String(code)
          .replace(/[^A-Z0-9_]/g, "")
          .slice(0, 64)}. Review the peer and retry explicitly.`
      : "")
  );
}
export function validateDestination(root, name) {
  if (
    !root ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      root.root_id,
    ) ||
    !root.capabilities?.includes("browse") ||
    !root.capabilities?.includes("create")
  )
    throw new Error(
      "Choose an approved folder with Browse + Create permission.",
    );
  if (!validMutationName(name))
    throw new Error(
      "Use a normal filename without paths, reserved names or hidden prefixes.",
    );
  return { root_id: root.root_id, directory_id: null, name };
}
export function eligibleRecipients(peers, rootId, offer = false) {
  return (peers || []).filter(
    (p) =>
      p.state === "active" &&
      (offer
        ? p.permissions?.roots?.some(
            (r) =>
              r.root_id === rootId &&
              r.capabilities.includes("browse") &&
              r.capabilities.includes("read"),
          )
        : Boolean(p.endpoint)),
  );
}
