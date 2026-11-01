import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fixture, rpc } from "./content-fixture.js";
let f;
test.afterEach(async () => {
  if (f) {
    const current = f;
    f = null;
    await current.close();
  }
});
async function chatReady(page) {
  await page.goto("/#chat");
  await expect(
    page.getByRole("heading", { name: "Chat", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Protected store ready", { exact: true }),
  ).toBeVisible();
}
async function refAttachment() {
  const message_id = randomUUID();
  await f.ref("send_chat", {
    message_id,
    peer_id: f.reverse,
    text: "Here is the reviewed fixture.",
    attachment: await f.source(),
  });
  return message_id;
}

test("real E2EE delivery renders plain text and never writes messages to browser persistence", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  const failures = [];
  page.on("pageerror", (e) => failures.push(e.message));
  await chatReady(page);
  const text =
    '<img src=x onerror="window.unwanted=1"> PRIVATE_BROWSER_FIXTURE_5917';
  await page.getByRole("textbox", { name: "Private message" }).fill(text);
  await page
    .getByRole("button", { name: "Send privately", exact: true })
    .click();
  await expect(
    page.locator(".chat-message.outgoing .message-text").last(),
  ).toHaveText(text);
  await expect(
    page.locator(".chat-message.outgoing .message-state").last(),
  ).toHaveText("Delivered", { timeout: 22000 });
  const history = await f.ref("chat_history", {
    peer_id: f.reverse,
    before: null,
  });
  expect(history.messages.at(-1).text).toBe(text);
  expect(await page.locator(".message-text img").count()).toBe(0);
  expect(await page.evaluate(() => window.unwanted)).toBeUndefined();
  const persisted = await page.evaluate(() =>
    JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }),
  );
  expect(persisted).not.toContain("PRIVATE_BROWSER_FIXTURE_5917");
  expect(failures).toEqual([]);
  await page.screenshot({
    path: "artifacts/desktop-5-9/chat-delivered.png",
    fullPage: true,
  });
});
test("private drafts survive a UI reload through the protected agent store", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  await chatReady(page);
  const draft = "తెలుగు — protected draft 87119";
  await page.getByRole("textbox", { name: "Private message" }).fill(draft);
  await expect
    .poll(
      async () =>
        (await rpc(request, "chat_history", { peer_id: f.peer, before: null }))
          .draft,
    )
    .toBe(draft);
  await page.reload();
  await expect(
    page.getByRole("textbox", { name: "Private message" }),
  ).toHaveValue(draft);
});
test("device content switches grant real consent before composing", async ({
  page,
  request,
}) => {
  f = await fixture(request, false);
  await chatReady(page);
  await expect(
    page.getByRole("button", { name: "Send privately", exact: true }),
  ).toBeDisabled();
  await page.goto("/#devices");
  await page
    .locator(`.peer-card[data-peer-id="${f.peer}"]`)
    .getByRole("button", { name: "Details & permissions" })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("checkbox", { name: /Allow encrypted chat/ }).check();
  await dialog
    .getByRole("checkbox", { name: /Allow encrypted attachments/ })
    .check();
  await page.screenshot({
    path: "artifacts/desktop-5-9/content-consent.png",
    fullPage: true,
  });
  await dialog
    .getByRole("button", { name: "Save content permissions" })
    .click();
  await expect(dialog).toHaveCount(0);
  const peer = (await rpc(request, "get_snapshot")).connections.peers.find(
    (p) => p.peer_id === f.peer,
  );
  expect(peer.permissions.chat).toBe(true);
  expect(peer.permissions.attachment).toBe(true);
  await chatReady(page);
  await page
    .getByRole("textbox", { name: "Private message" })
    .fill("Consent now exists.");
  await expect(
    page.getByRole("button", { name: "Send privately", exact: true }),
  ).toBeEnabled();
});
test("approved Files selection creates an actual encrypted attachment message", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  await page.goto("/#files");
  await page.locator(".file-row").filter({ hasText: "local-note.txt" }).click();
  await page
    .getByRole("button", { name: "Attach in Chat", exact: true })
    .click();
  await expect(page.locator(".attachment-chip")).toContainText(
    "local-note.txt",
  );
  await page
    .getByRole("textbox", { name: "Private message" })
    .fill("Selected explicitly from Files.");
  await page
    .getByRole("button", { name: "Send privately", exact: true })
    .click();
  await expect(
    page.locator(".chat-message.outgoing .message-state").last(),
  ).toHaveText("Delivered", { timeout: 22000 });
  const messages = (
    await f.ref("chat_history", { peer_id: f.reverse, before: null })
  ).messages;
  expect(messages.at(-1).attachment.name).toBe("local-note.txt");
  expect(messages.at(-1).attachment.state).toBe("not_requested");
});
test("incoming attachment downloads separately and needs explicit no-overwrite export", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  const message = await refAttachment();
  await chatReady(page);
  const card = page.locator(`[data-message-id="${message}"]`);
  await expect(card.getByText("Not downloaded", { exact: true })).toBeVisible({
    timeout: 22000,
  });
  await card.getByRole("button", { name: "Download encrypted file" }).click();
  await expect(
    card.getByRole("button", { name: "Export verified copy" }),
  ).toBeVisible({ timeout: 22000 });
  await card.getByRole("button", { name: "Export verified copy" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Local destination folder").selectOption(f.localRoot);
  await dialog.getByLabel("Destination filename").fill("explicit-export.txt");
  await dialog.getByRole("button", { name: "Save verified copy" }).click();
  await expect(dialog).toHaveCount(0);
  await expect
    .poll(async () => {
      try {
        return await fs.readFile(
          path.join(f.local, "explicit-export.txt"),
          "utf8",
        );
      } catch {
        return null;
      }
    })
    .toBe(f.payload.toString());
  await expect(card.getByText("Exported copy", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "artifacts/desktop-5-9/chat-attachment-exported.png",
    fullPage: true,
  });
});
test("remote Files Keep offline publishes a real version and unpin preserves it", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  await page.goto("/#files");
  await page.locator(".file-device-select").selectOption(f.peer);
  await page
    .locator(".file-row")
    .filter({ hasText: "release-notes.txt" })
    .click();
  await page.getByRole("button", { name: "Keep offline", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Local destination folder").selectOption(f.localRoot);
  await dialog.getByLabel("Destination filename").fill("keep-offline.txt");
  await dialog.getByRole("button", { name: "Save verified copy" }).click();
  await expect(
    page.getByRole("heading", { name: "Sync & offline", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".pin-card")).toContainText("Copy published", {
    timeout: 25000,
  });
  expect(
    await fs.readFile(path.join(f.local, "keep-offline.txt"), "utf8"),
  ).toBe(f.payload.toString());
  await page.screenshot({
    path: "artifacts/desktop-5-9/sync-published-pin.png",
    fullPage: true,
  });
  await page
    .locator(".pin-card")
    .getByRole("button", { name: "Unpin", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Unpin · keep files", exact: true })
    .click();
  await expect(page.locator(".pin-card")).toHaveCount(0);
  expect(
    await fs.readFile(path.join(f.local, "keep-offline.txt"), "utf8"),
  ).toBe(f.payload.toString());
});
test("chat and offline controls pass automated accessibility with real content", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  await refAttachment();
  await chatReady(page);
  await expect(page.locator(".message-attachment")).toHaveCount(1, {
    timeout: 22000,
  });
  for (const route of ["chat", "sync"]) {
    await page.goto(`/#${route}`);
    await expect(page.locator(".header-status")).toHaveText("Agent online");
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(result.violations).toEqual([]);
  }
});
test("compact content layouts retain navigation and do not overflow", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  await page.setViewportSize({ width: 390, height: 844 });
  await chatReady(page);
  for (const route of ["chat", "sync"]) {
    await page.goto(`/#${route}`);
    await expect(page.locator(".header-status")).toHaveText("Agent online");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `artifacts/desktop-5-9/${route}-compact.png`,
      fullPage: true,
    });
  }
});
test("safe support summary is allowlisted rather than a dump of private stores", async ({
  page,
  request,
}) => {
  f = await fixture(request);
  const privateText = "SUPPORT_MUST_NOT_INCLUDE_94711";
  await rpc(request, "save_chat_draft", { peer_id: f.peer, text: privateText });
  await page.goto("/#diagnostics");
  const downloaded = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Export safe support summary" })
    .click();
  const file = await downloaded;
  const text = await fs.readFile(await file.path(), "utf8");
  const summary = JSON.parse(text);
  expect(summary.release_qualified).toBe(false);
  expect(summary.protected_content_ready).toBe(true);
  for (const secret of [
    privateText,
    f.peer,
    f.reverse,
    f.local,
    "release-notes.txt",
  ])
    expect(text).not.toContain(secret);
});
