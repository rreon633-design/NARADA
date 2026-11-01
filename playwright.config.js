import { defineConfig } from "@playwright/test";
import path from "node:path";
export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30000,
  expect: { timeout: 8000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ["list"],
    ["json", { outputFile: "artifacts/phase-4/browser-results.json" }],
  ],
  outputDir: ".cache/playwright-results",
  use: {
    baseURL: "http://127.0.0.1:5174",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://127.0.0.1:5174/__narada/health",
    timeout: 180000,
    reuseExistingServer: !process.env.CI,
    env: {
      NARADA_UI_PORT: "5174",
      NARADA_PREVIEW_PORT: "4320",
      NARADA_DATA_DIR: path.resolve(".narada-data/e2e-phase4"),
    },
  },
});
