import { spawnSync, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
if (process.platform !== "win32") {
  console.error(
    "The native desktop target is Windows. Use npm run dev for the Linux/browser development adapter.",
  );
  process.exit(1);
}
const prep = spawnSync(
  process.execPath,
  ["scripts/prepare-sidecar.mjs", "--debug"],
  { cwd: root, stdio: "inherit" },
);
if (prep.status !== 0) process.exit(prep.status || 1);
const child = spawn("npx", ["tauri", "dev"], {
  cwd: path.join(root, "apps/desktop-shell"),
  stdio: "inherit",
  shell: true,
});
child.on("exit", (c) => process.exit(c || 0));
