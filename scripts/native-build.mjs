import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
if (process.platform !== "win32") {
  console.error(
    "Build the Windows application on a qualified Windows build machine.",
  );
  process.exit(1);
}
for (const [cmd, args, cwd] of [
  [process.execPath, ["scripts/prepare-sidecar.mjs"], root],
  ["npx", ["tauri", "build"], path.join(root, "apps/desktop-shell")],
]) {
  const r = spawnSync(cmd, args, {
    cwd,
    stdio: "inherit",
    shell: cmd === "npx",
  });
  if (r.status !== 0) process.exit(r.status || 1);
}
