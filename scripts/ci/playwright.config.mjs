import { defineConfig } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
if (process.env.CI !== "true" || process.env.NARADA_CI_JOB !== "desktop-linux") {
  throw new Error("Use this configuration only in the fresh desktop-linux CI checkout.");
}
const evidence = path.join(root, ".cache/ci-evidence/desktop-linux");
export default defineConfig({
  testDir: path.join(root, "tests/e2e"),
  testMatch: "**/*.spec.js",
  timeout: 60000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  reporter: [
    ["list"],
    ["json", { outputFile: path.join(evidence, "browser-results.json") }],
    ["junit", { outputFile: path.join(evidence, "browser-results.xml") }],
  ],
  outputDir: path.join(evidence, "browser-traces"),
  use: {
    baseURL: "http://127.0.0.1:5176",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev",
    cwd: root,
    url: "http://127.0.0.1:5176/__narada/health",
    timeout: 180000,
    reuseExistingServer: false,
    env: {
      NARADA_UI_PORT: "5176",
      NARADA_PREVIEW_PORT: "4322",
      NARADA_DATA_DIR: path.join(root, ".narada-data/ci-browser"),
    },
  },
});
