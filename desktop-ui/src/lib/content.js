// Pure display rules. Network writes are never inferred to be delivery receipts.
let selectedAttachment = null;
export function selectChatAttachment(value) {
  selectedAttachment = value;
}
export function chatAttachment() {
  return selectedAttachment;
}
export function clearChatAttachment() {
  selectedAttachment = null;
}
export const messageBytes = (text) =>
  new TextEncoder().encode(text || "").length;
export function validMessage(text, attachment = false) {
  return (
    messageBytes(text) <= 4096 &&
    (attachment || Boolean(text?.trim())) &&
    !/[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/u.test(text || "")
  );
}
export function messageState(message) {
  return (
    {
      pending: "Saved locally",
      retry_wait: "Awaiting receipt",
      delivered: "Delivered",
      cancelled: "Cancelled locally",
      action_required: "Needs attention",
    }[message?.state] || "Unconfirmed"
  );
}
export function attachmentState(a) {
  return (
    {
      not_requested: "Not downloaded",
      queued: "Downloading ciphertext",
      allocating: "Reserving space",
      paused: "Paused · data retained",
      available: "Authenticated · ready to export",
      exported: "Exported copy",
      cleaned: "Retention expired",
    }[a?.state] || "Unavailable"
  );
}
export function cipherProgress(a) {
  const total = Number(a?.cipher_size),
    verified = Number(a?.verified_bytes);
  if (
    !Number.isSafeInteger(total) ||
    total <= 0 ||
    !Number.isSafeInteger(verified) ||
    verified < 0 ||
    verified > total
  )
    return null;
  return Math.floor((verified / total) * 100);
}
export function pinState(p) {
  return (
    {
      queued: "Updating copy",
      current: "Copy retained",
      refresh_needed: "New version available",
      check_requested: "Checking source",
      paused: "Refresh paused",
      attention: "Needs attention",
      source_deleted: "Source removed · copy kept",
      source_changed: "Source changed · copy kept",
    }[p?.state] || "Unconfirmed"
  );
}
export function contentHint(code) {
  return (
    {
      CONTENT_STORE_LOCKED:
        "Protected state could not be verified. Restore the matching original key and state; never delete it to reset encryption.",
      CHAT_KEY_CHANGED:
        "The other device changed its encryption identity. Sending is blocked; verify the devices rather than silently resetting sessions.",
      ACCESS_DENIED:
        "Both owners must enable the required chat/attachment or folder permission in Devices and Files.",
      PERMISSION_DENIED:
        "The owner withdrew this permission. Retained local copies are not deleted.",
      MESSAGE_RETRY_LIMIT:
        "Automatic attempts stopped. Review the peer, then explicitly retry the same saved message.",
      CONTENT_QUOTA:
        "Protected history or ciphertext retention is full. Pending and unknown data are never silently purged.",
      PIN_QUOTA:
        "The active-pin budget is full. Unpin deliberately; already-published files remain yours.",
      PIN_VERSION_LIMIT:
        "Three versions are retained. Archive/unpin deliberately before tracking more versions; no older copy was overwritten.",
      BLOB_EXPIRED:
        "The sender no longer retains this encrypted snapshot. Ask for a new attachment message.",
      NAME_COLLISION:
        "That filename already exists. Nothing was overwritten. Choose another name where permitted.",
      ATTACHMENT_DECRYPT_FAILED:
        "An encryption record or final authentication check failed. No plaintext was exported.",
      RESTART_REVIEW:
        "The agent restarted. Resume explicitly to recheck saved ciphertext chunks.",
      CURSOR_EXPIRED:
        "Metadata needs a fresh stable snapshot; your previous local copy is retained.",
      PEER_UNREACHABLE:
        "The other agent is unreachable. This is not a delivery confirmation.",
      DEVICE_REVOKED:
        "Trust was revoked. New data is blocked; previously received content cannot be erased remotely.",
    }[code] ||
    "Review the saved state and permissions. No delivery or publication was assumed."
  );
}
export function canCompose(peer, ready, connected) {
  return Boolean(
    connected && ready && peer?.state === "active" && peer.permissions?.chat,
  );
}
export function retainedBytes(pins) {
  return (pins || []).reduce(
    (sum, p) =>
      sum +
      (p.versions || []).reduce((n, v) => {
        const b = Number(v.size);
        return n + (Number.isSafeInteger(b) && b >= 0 ? b : 0);
      }, 0),
    0,
  );
}
