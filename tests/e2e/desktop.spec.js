import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";

const defaults = {
  device_name: "My workspace",
  close_to_tray: true,
  notifications_enabled: true,
  hide_notification_previews: true,
  reduce_motion: false,
  setup_completed: false,
};
const envelope = (type, payload) => ({
  version: 1,
  id: randomUUID(),
  command: payload === undefined ? { type } : { type, payload },
});
async function rpc(request, type, payload) {
  const response = await request.post("/__narada/rpc", {
    data: envelope(type, payload),
  });
  expect(response.status()).toBe(200);
  return response.json();
}
async function ready(page, route = "home") {
  await page.goto(`/#${route}`);
  await expect(page.locator(".header-status")).toHaveText("Agent online");
}

test.beforeEach(async ({ request }) => {
  expect((await rpc(request, "save_settings", defaults)).ok).toBe(true);
});

test("home reflects actual foundation state without fake peer activity", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await ready(page);
  await expect(
    page.getByRole("heading", { name: "Welcome to your own space." }),
  ).toBeVisible();
  await expect(page.locator(".stat-number").nth(0)).toHaveText("00");
  await expect(page.locator(".stat-number").nth(1)).toHaveText("00");
  await expect(page.locator(".stat-number").nth(2)).toHaveText("00");
  await expect(
    page.getByText("Development preview", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".stat-number").nth(3)).not.toHaveText("—");
  expect(errors).toEqual([]);
});

test("setup saves real preferences and survives reload", async ({
  page,
  request,
}) => {
  await ready(page);
  await page
    .getByRole("button", { name: "Set up this computer", exact: true })
    .click();
  await page.getByLabel("Computer name", { exact: true }).fill("NARADA Studio");
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page.getByRole("checkbox", { name: /Reduce interface motion/ }).check();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page
    .getByRole("button", { name: "Save & open Devices", exact: true })
    .click();
  await expect(page.locator("dialog")).toHaveCount(0);
  const saved = await rpc(request, "get_snapshot");
  expect(saved.data.settings).toMatchObject({
    device_name: "NARADA Studio",
    setup_completed: true,
    reduce_motion: true,
  });
  await expect(page).toHaveURL(/#devices$/);
  await page.reload();
  await page.getByRole("button", { name: "Home", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Computer settings", exact: true }),
  ).toBeVisible();
  await expect(page.locator("body")).toHaveClass(/reduce-motion/);
});

test("settings validation, saving, dirty guarding, and discard are functional", async ({
  page,
  request,
}) => {
  await ready(page, "settings");
  const input = page.getByLabel("Computer name", { exact: true });
  await input.fill("");
  await expect(
    page.getByRole("button", { name: "Save changes" }),
  ).toBeDisabled();
  await expect(
    page.getByText("Enter 1–48 characters, without control characters."),
  ).toBeVisible();
  await input.fill("My desk");
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(input).toHaveValue("My desk");
  await page.getByRole("button", { name: "Home", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "Keep editing" }).click();
  await expect(input).toHaveValue("My desk");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByText("All changes saved locally")).toBeVisible();
  expect((await rpc(request, "get_snapshot")).data.settings.device_name).toBe(
    "My desk",
  );
  await page.getByLabel("Computer name", { exact: true }).fill("Discard this");
  await page.getByRole("button", { name: "Home", exact: true }).click();
  await page.getByRole("button", { name: "Discard & continue" }).click();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(page.getByLabel("Computer name", { exact: true })).toHaveValue(
    "My desk",
  );
});

test("stored text remains text rather than executable markup", async ({
  page,
  request,
}) => {
  await rpc(request, "save_settings", {
    ...defaults,
    device_name: "<img src=x onerror=alert(1)>",
  });
  let alert = false;
  page.on("dialog", async (d) => {
    alert = true;
    await d.dismiss();
  });
  await ready(page, "devices");
  await expect(page.locator(".this-device h2")).toHaveText(
    "<img src=x onerror=alert(1)>",
  );
  expect(await page.locator(".this-device img").count()).toBe(0);
  expect(alert).toBe(false);
});

test("health checks persist and export only redacted diagnostics", async ({
  page,
  request,
}) => {
  await rpc(request, "save_settings", {
    ...defaults,
    device_name: "Secret Workspace Name",
  });
  await ready(page, "diagnostics");
  await page
    .getByRole("button", { name: "Run health check", exact: true })
    .click();
  await expect(
    page.getByText("Database integrity", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".checks-list .badge-success")).not.toHaveCount(0);
  await expect(page.getByText("Not applicable", { exact: true })).toBeVisible();
  const pending = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export report" }).click();
  const download = await pending;
  const content = await fs.readFile(await download.path(), "utf8");
  const report = JSON.parse(content);
  expect(content).not.toContain("Secret Workspace Name");
  expect(content).not.toContain("/home/");
  expect(content).not.toContain("activity");
  expect(report).toHaveProperty("diagnostics.checks");
  await page.reload();
  await expect(
    page.getByText("Database integrity", { exact: true }),
  ).toBeVisible();
});

test("activity history clears with confirmation without resetting settings", async ({
  page,
  request,
}) => {
  await rpc(request, "save_settings", {
    ...defaults,
    device_name: "Preserved name",
  });
  await ready(page);
  await page
    .getByRole("button", { name: "View activity", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Clear history", exact: true })
    .click();
  await expect(page.getByRole("dialog").last()).toContainText(
    "Workspace preferences",
  );
  await page
    .getByRole("dialog")
    .last()
    .getByRole("button", { name: "Clear history", exact: true })
    .click();
  await expect(page.locator("dialog")).toHaveCount(0);
  const snapshot = (await rpc(request, "get_snapshot")).data;
  expect(snapshot.settings.device_name).toBe("Preserved name");
  expect(snapshot.activity).toHaveLength(1);
  expect(snapshot.activity[0].title).toBe("Activity history cleared");
});

test("keyboard command search navigates, dialogs close and restore focus", async ({
  page,
}) => {
  await ready(page);
  await page.getByRole("link", { name: "Skip to main content" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("main")).toBeFocused();
  const trigger = page.getByRole("button", {
    name: "Search pages and settings",
    exact: true,
  });
  await trigger.focus();
  await page.keyboard.press("Control+k");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("searchbox").fill("health");
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("heading", { name: "Workspace health", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Help and local-first guide", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", {
      name: "Help and local-first guide",
      exact: true,
    }),
  ).toBeFocused();
});

test("file, chat and offline destinations expose real agent-backed workspaces", async ({
  page,
}) => {
  const pages = [
    ["chat", "Chat", ".chat-workspace"],
    ["sync", "Sync & offline", ".sync-overview"],
  ];
  for (const [route, title, selector] of pages) {
    await ready(page, route);
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(page.locator(selector)).toBeVisible();
  }
  await ready(page, "files");
  await expect(page.locator(".folder-tree")).toBeVisible();
  await expect(page.locator(".file-table-area")).toBeVisible();
  await expect(page.locator(".file-inspector")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "List view", exact: true }),
  ).toBeEnabled();
  await expect(
    page.getByRole("button", { name: "Add folder", exact: true }),
  ).toBeEnabled();
});

test("offline is a real error state, not a fake successful connection", async ({
  page,
}) => {
  await page.route("**/__narada/rpc", (r) => r.abort("connectionrefused"));
  await page.goto("/");
  await expect(page.locator(".header-status")).toHaveText("Agent offline");
  await expect(page.locator(".connection-banner")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Set up this computer", exact: true }),
  ).toBeDisabled();
  await page.unroute("**/__narada/rpc");
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.locator(".header-status")).toHaveText("Agent online");
});

test("development bridge rejects cross-origin, lifecycle, and oversized requests", async ({
  request,
}, testInfo) => {
  const cross = await request.post("/__narada/rpc", {
    headers: { origin: "https://untrusted.invalid" },
    data: envelope("get_snapshot"),
  });
  expect(cross.status()).toBe(403);
  const rebound = await request.post("/__narada/rpc", {
    headers: { host: "untrusted.invalid", origin: "http://untrusted.invalid" },
    data: envelope("get_snapshot"),
  });
  expect(rebound.status()).toBe(403);
  const shutdown = await request.post("/__narada/rpc", {
    data: envelope("shutdown"),
  });
  expect(shutdown.status()).toBe(403);
  const invalid = await rpc(request, "save_settings", {
    ...defaults,
    device_name: "x".repeat(49),
  });
  expect(invalid.ok).toBe(false);
  expect(invalid.error.code).toBe("INVALID_SETTINGS");
  const unknown = await request.post("/__narada/rpc", {
    data: envelope("execute_shell"),
  });
  expect(unknown.status()).toBe(422);
  const large = await request.post("/__narada/rpc", {
    data: envelope("save_settings", {
      ...defaults,
      device_name: "x".repeat(70000),
    }),
  });
  expect(large.status()).toBe(413);
  const previewPort = testInfo.config.webServer?.env?.NARADA_PREVIEW_PORT;
  expect(
    previewPort,
    "The test server must declare its private RPC port",
  ).toMatch(/^[0-9]+$/);
  const direct = await request.post(`http://127.0.0.1:${previewPort}/rpc`, {
    data: envelope("get_snapshot"),
  });
  expect(direct.status()).toBe(401);
});

for (const route of [
  "home",
  "files",
  "chat",
  "transfers",
  "sync",
  "devices",
  "settings",
  "diagnostics",
]) {
  test(`accessibility: ${route}`, async ({ page }) => {
    await ready(page, route);
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(results.violations).toEqual([]);
  });
}

test("small viewports remain usable and retain accessible navigation", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await ready(page);
  await expect(
    page.getByRole("button", { name: "Settings", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Computer settings", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(
    await page
      .locator("main")
      .evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
  ).toBe(true);
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(results.violations).toEqual([]);
  await page.screenshot({ path: "artifacts/desktop-5-9/desktop-compact.png" });
});

test("setup, help, command search, and build-limitations dialogs pass automated checks", async ({
  page,
}) => {
  await ready(page);
  for (const name of [
    "Set up this computer",
    "Help and local-first guide",
    "Search pages and settings",
  ]) {
    await page.getByRole("button", { name, exact: true }).click();
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(results.violations).toEqual([]);
    await page.keyboard.press("Escape");
  }
  await page.locator(".phase-card").click();
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(result.violations).toEqual([]);
});

test("response version and correlation mismatch cannot become successful UI state", async ({
  page,
}) => {
  await page.route("**/__narada/rpc", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ ok: true, version: 99, id: "wrong-id", data: {} }),
    }),
  );
  await page.goto("/");
  await expect(page.locator(".header-status")).toHaveText("Agent offline");
  await expect(page.locator(".connection-banner")).toContainText(
    "incompatible response",
  );
});

test("concise help keeps verification and upgrade warnings, and About uses the actual agent version", async ({
  page,
  request,
}) => {
  await ready(page, "settings");
  const snapshot = await rpc(request, "get_snapshot");
  await expect(page.locator("#about")).toContainText(
    snapshot.data.agent.version,
  );
  await expect(page.locator("#about")).not.toContainText("Desktop foundation");
  await expect(page.locator("#about")).not.toContainText("no secrets stored");
  await page
    .locator("#about")
    .getByRole("button", { name: "Build limitations", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Do not clear app data");
  await expect(
    page.getByRole("dialog").getByText("Pending", { exact: true }),
  ).toHaveCount(3);
  await page.keyboard.press("Escape");
  await page
    .getByRole("button", { name: "Help and local-first guide", exact: true })
    .click();
  const help = page.getByRole("dialog");
  await expect(help).toContainText("Compare full fingerprints");
  await expect(help).toContainText("Chat does not require a folder");
  await expect(help).not.toContainText("Android comes later");
  await help
    .getByRole("button", { name: "Build limitations", exact: true })
    .click();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("heading", { name: "Safe upgrades", exact: true }),
  ).toBeVisible();
});
