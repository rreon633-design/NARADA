import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const debug = process.argv.includes("--debug");
const run = (cmd, args) => {
  const r = spawnSync(cmd, args, {
    cwd: root,
    encoding: "utf8",
    shell: process.platform === "win32",
  });
  if (r.status !== 0) throw Error(r.stderr || `${cmd} failed`);
  return r.stdout;
};
const host = run("rustc", ["-vV"])
  .split("\n")
  .find((l) => l.startsWith("host: "))
  ?.slice(6)
  .trim();
if (!host) throw Error("Could not determine the Rust host target.");
run("cargo", [
  "build",
  "-p",
  "narada-agent",
  ...(debug ? [] : ["--release"]),
  "--locked",
  "--no-default-features",
]);
const ext = process.platform === "win32" ? ".exe" : "";
const dest = path.join(root, "apps/desktop-shell/src-tauri/binaries");
mkdirSync(dest, { recursive: true });
copyFileSync(
  path.join(root, "target", debug ? "debug" : "release", `narada-agent${ext}`),
  path.join(dest, `narada-agent-${host}${ext}`),
);
console.log(
  `Prepared ${debug ? "debug" : "release"} agent sidecar for ${host}. Development HTTP feature is NOT enabled.`,
);
