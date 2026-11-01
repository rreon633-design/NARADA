import { spawn, spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  chmodSync,
} from "node:fs";
import { randomBytes, createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import path from "node:path";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const env = {
  ...process.env,
  NARADA_DEV_TOKEN: randomBytes(32).toString("hex"),
  NARADA_PREVIEW_PORT: process.env.NARADA_PREVIEW_PORT || "4319",
};
const dataDir = path.resolve(
  process.env.NARADA_DATA_DIR || path.join(root, ".narada-data/preview"),
);
// Explicit development-only wrapping key. It never enters VITE_* or the browser bundle.
// This test vault is NOT OS-backed. Windows always uses DPAPI, even in preview mode.
if (process.platform !== "win32" && !env.NARADA_DEV_VAULT_KEY) {
  const vaultDir = path.join(root, ".cache/development-vaults");
  mkdirSync(vaultDir, { recursive: true, mode: 0o700 });
  chmodSync(vaultDir, 0o700);
  const keyPath = path.join(
    vaultDir,
    createHash("sha256").update(dataDir).digest("hex") + ".key",
  );
  if (existsSync(keyPath))
    env.NARADA_DEV_VAULT_KEY = readFileSync(keyPath, "utf8").trim();
  else if (
    !existsSync(path.join(dataDir, "transport.identity")) &&
    (!existsSync(path.join(dataDir, "narada.db")) ||
      process.env.NARADA_INITIALIZE_DEV_VAULT === "1")
  ) {
    env.NARADA_DEV_VAULT_KEY = randomBytes(32).toString("hex");
    writeFileSync(keyPath, env.NARADA_DEV_VAULT_KEY, {
      mode: 0o600,
      flag: "wx",
      flush: true,
    });
  } else
    console.warn(
      "Development identity key unavailable. Restore the original test-vault key; the agent will block connections rather than replace its identity.",
    );
}
const browserEnv = { ...env };
delete browserEnv.NARADA_DEV_VAULT_KEY;
const children = [];
let stopping = false;
function shutdown(code = 0) {
  if (stopping) return;
  stopping = true;
  for (const child of children) {
    if (process.platform === "win32" && child.pid)
      spawnSync("taskkill", ["/pid", String(child.pid), "/t", "/f"], {
        stdio: "ignore",
      });
    else child.kill("SIGINT");
  }
  setTimeout(() => process.exit(code), 1000).unref();
}
function run(command, args, childEnv = env) {
  const child = spawn(command, args, {
    cwd: root,
    env: childEnv,
    stdio: "inherit",
    shell: command === "npm" && process.platform === "win32",
  });
  children.push(child);
  child.on("error", (e) => {
    console.error(e.message);
    shutdown(1);
  });
  child.on("exit", (code) => {
    if (!stopping) shutdown(code || 0);
  });
  return child;
}
console.log(
  "NARADA · Starting the desktop development agent. Connections and folder grants are opt-in; bounded transfers require explicit destination/receive approval. Chat, attachments and sync require separate explicit consent; this preview is not a signed or independently reviewed release.",
);
run("cargo", [
  "run",
  "-p",
  "narada-agent",
  "--features",
  "dev-preview",
  "--",
  "--data-dir",
  dataDir,
  "--preview-port",
  env.NARADA_PREVIEW_PORT,
]);
// Vite starts independently so the UI can truthfully show starting/offline state while Rust builds.
run("npm", ["--prefix", "desktop-ui", "run", "dev"], browserEnv);
process.on("SIGINT", () => shutdown());
process.on("SIGTERM", () => shutdown());
