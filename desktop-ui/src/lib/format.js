export function bytes(value) {
  if (!Number.isFinite(value) || value < 0) return "—";
  if (value < 1024) return `${value} B`;
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 3);
  return `${(value / 1024 ** index).toFixed(index === 1 ? 0 : 1)} ${["B", "KB", "MB", "GB"][index]}`;
}
export function uptime(seconds) {
  if (!Number.isFinite(seconds)) return "—";
  if (seconds < 60) return "Just started";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}
export function relativeTime(iso, now = Date.now()) {
  const elapsed = Math.max(0, (now - Date.parse(iso)) / 1000);
  if (!Number.isFinite(elapsed)) return "—";
  if (elapsed < 60) return "Just now";
  if (elapsed < 3600) return `${Math.floor(elapsed / 60)} min ago`;
  if (elapsed < 86400) return `${Math.floor(elapsed / 3600)}h ago`;
  return new Date(iso).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
  });
}
export function validateName(value) {
  const trimmed = String(value).trim();
  if (
    !trimmed ||
    Array.from(trimmed).length > 48 ||
    /[\x00-\x1f\x7f-\x9f]/.test(trimmed)
  )
    return "Enter 1–48 characters, without control characters.";
  return null;
}
export function platformName(value) {
  return (
    {
      windows: "Windows",
      linux: "Linux · development",
      macos: "macOS · development",
    }[value] || "Unknown"
  );
}
export function settingsEqual(a, b) {
  return Boolean(
    a &&
    b &&
    [
      "device_name",
      "close_to_tray",
      "notifications_enabled",
      "hide_notification_previews",
      "reduce_motion",
      "setup_completed",
    ].every((key) => a[key] === b[key]),
  );
}
