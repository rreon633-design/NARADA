// A genuinely separate profile, protected identity, Rust process and owner UI.
import { fileURLToPath } from "node:url";
import path from "node:path";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
process.env.NARADA_DATA_DIR =
  process.env.NARADA_DATA_DIR || path.join(root, ".narada-data/reference");
process.env.NARADA_UI_PORT = process.env.NARADA_UI_PORT || "5175";
process.env.NARADA_PREVIEW_PORT = process.env.NARADA_PREVIEW_PORT || "4321";
await import("./dev.mjs");
