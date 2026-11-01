import { defineConfig } from "@playwright/test";
import path from "node:path";
export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: "**/*.spec.js",
  timeout: 45000,
  expect: { timeout: 10000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ["list"],
    ["json", { outputFile: "artifacts/desktop-5-9/browser-results.json" }],
  ],
  outputDir: ".cache/playwright-desktop-5-9",
  use: {
    baseURL: "http://127.0.0.1:5176",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://127.0.0.1:5176/__narada/health",
    timeout: 180000,
    reuseExistingServer: false,
    env: {
      NARADA_UI_PORT: "5176",
      NARADA_PREVIEW_PORT: "4322",
      NARADA_DATA_DIR: path.resolve(".narada-data/e2e-desktop-5-9"),
    },
  },
});
