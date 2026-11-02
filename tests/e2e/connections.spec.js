import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createConnection } from "node:net";
import fs from "node:fs/promises";
import path from "node:path";

const noContent = { chat: false, attachment: false, roots: [] };
async function rpc(request, type, payload) {
  const result = await request.post("/__narada/rpc", {
    data: {
      version: 1,
      id: randomUUID(),
      command: payload === undefined ? { type } : { type, payload },
    },
  });
  return result.json();
}
async function ready(page) {
  await page.goto("/#devices");
  await expect(page.locator(".header-status")).toHaveText("Agent online");
  await expect(
    page.getByRole("heading", { name: "Your devices", exact: true }),
  ).toBeVisible();
}
async function enable(page) {
  await page
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("Network interface")).toHaveValue("127.0.0.1");
  await expect(dialog.getByLabel("Discover nearby devices")).toBeDisabled();
  await dialog
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  await expect(page.locator("dialog")).toHaveCount(0);
  await expect(
    page.getByText("Connections enabled", { exact: true }),
  ).toBeVisible();
}
function ipc(dir, type, payload) {
  return new Promise((resolve, reject) => {
    const socket = createConnection(path.join(dir, "agent.sock"));
    let data = "";
    const id = randomUUID();
    const timer = setTimeout(() => {
      socket.destroy();
      reject(Error("Reference IPC timeout"));
    }, 6000);
    socket.once("connect", () =>
      socket.write(
        JSON.stringify({
          version: 1,
          id,
          command: payload === undefined ? { type } : { type, payload },
        }) + "\n",
      ),
    );
    socket.on("data", (chunk) => {
      data += chunk;
      if (data.endsWith("\n")) {
        clearTimeout(timer);
        socket.end();
        const result = JSON.parse(data);
        if (result.id !== id || !result.ok)
          reject(Error(result.error?.code || "Mismatched reference response"));
        else resolve(result.data);
      }
    });
    socket.on("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });
}
let reference, dir;
test.beforeEach(async ({ request }) => {
  const snapshot = (await rpc(request, "get_snapshot")).data;
  if (snapshot.connections.invitation)
    await rpc(request, "cancel_pairing", {
      invitation_id: snapshot.connections.invitation.invitation_id,
    });
  if (
    snapshot.connections.join &&
    ["connecting", "proving", "awaiting_approval", "finalizing"].includes(
      snapshot.connections.join.state,
    )
  )
    await rpc(request, "cancel_join", {
      attempt_id: snapshot.connections.join.attempt_id,
    });
  for (const peer of snapshot.connections.peers.filter(
    (p) => p.state !== "revoked",
  ))
    await rpc(request, "revoke_peer", {
      peer_id: peer.peer_id,
      expected_revision: peer.grant_revision,
    });
  await rpc(request, "configure_connections", {
    enabled: false,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  });
});
test.afterEach(async ({ request }) => {
  if (reference) {
    reference.kill("SIGTERM");
    await new Promise((resolve) => {
      if (reference.exitCode !== null) resolve();
      else reference.once("exit", resolve);
    });
    reference = null;
  }
  if (dir) {
    await fs.rm(dir, { recursive: true, force: true });
    dir = null;
  }
  const result = await rpc(request, "get_snapshot");
  if (result.ok) {
    for (const p of result.data.connections.peers.filter(
      (p) => p.state !== "revoked",
    ))
      await rpc(request, "revoke_peer", {
        peer_id: p.peer_id,
        expected_revision: p.grant_revision,
      });
    await rpc(request, "configure_connections", {
      enabled: false,
      bind_ip: "127.0.0.1",
      api_port: 0,
      discovery: false,
    });
  }
});
test("networking requires explicit consent and disabling retains the identity", async ({
  page,
  request,
}) => {
  await ready(page);
  const original = (await rpc(request, "get_snapshot")).data.connections
    .identity.fingerprint;
  await expect(
    page.getByRole("button", { name: "Pair a device", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByText("Development test vault · not OS-backed", { exact: true }),
  ).toBeVisible();
  await enable(page);
  await page
    .getByRole("button", { name: "Disable connections", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Disable connections", exact: true })
    .click();
  await expect(
    page.getByText("Connections disabled", { exact: true }),
  ).toBeVisible();
  expect(
    (await rpc(request, "get_snapshot")).data.connections.identity.fingerprint,
  ).toBe(original);
});
test("real QR is decoded from a chosen image offline, without uploading it", async ({
  page,
  request,
}) => {
  await ready(page);
  await enable(page);
  await page
    .getByRole("button", { name: "Pair a device", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.locator("canvas")).toBeVisible();
  await expect
    .poll(() => dialog.locator("canvas").evaluate((c) => c.width))
    .toBeGreaterThanOrEqual(348);
  const code = await dialog.locator("textarea").inputValue();
  const secret = JSON.parse(code).token;
  const image = await dialog.locator("canvas").screenshot();
  expect(
    JSON.stringify((await rpc(request, "get_snapshot")).data),
  ).not.toContain(secret);
  await dialog
    .getByRole("button", { name: "Cancel invitation", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Join with a code", exact: true })
    .click();
  const requests = [];
  page.on("request", (r) => requests.push(r.url()));
  await page.getByLabel("Choose QR image", { exact: false }).setInputFiles({
    name: "invitation.png",
    mimeType: "image/png",
    buffer: image,
  });
  await expect(page.getByLabel("Pairing code", { exact: true })).toHaveValue(
    code,
  );
  expect(requests.every((url) => new URL(url).hostname === "127.0.0.1")).toBe(
    true,
  );
  await page
    .getByRole("button", { name: "Request pairing", exact: true })
    .click();
  await expect(page.locator(".connection-form-error")).toContainText(
    "belongs to this workspace",
  );
  expect((await rpc(request, "get_snapshot")).data.paired_devices).toBe(0);
});
test("join form rejects malformed codes and unsafe image formats", async ({
  page,
}) => {
  await ready(page);
  await enable(page);
  await page
    .getByRole("button", { name: "Join with a code", exact: true })
    .click();
  await page
    .getByLabel("Pairing code", { exact: true })
    .fill("not a pairing code");
  await page
    .getByRole("button", { name: "Request pairing", exact: true })
    .click();
  await expect(page.locator(".connection-form-error")).toContainText(
    "complete NARADA pairing code",
  );
  await page.getByLabel("Choose QR image", { exact: false }).setInputFiles({
    name: "untrusted.svg",
    mimeType: "image/svg+xml",
    buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'),
  });
  await expect(page.locator(".connection-form-error")).toContainText(
    "Only PNG and JPEG",
  );
});
test("complete UI approval and revocation against a separate real reference process", async ({
  page,
  request,
}) => {
  test.skip(
    process.platform === "win32",
    "This browser fixture uses Unix IPC; native Windows has a separate qualification checklist.",
  );
  await fs.mkdir(".narada-data/browser-peers", { recursive: true });
  dir = await fs.mkdtemp(path.resolve(".narada-data/browser-peers/ref-"));
  reference = spawn(
    path.resolve("target/debug/narada-agent"),
    ["--data-dir", dir],
    {
      env: { ...process.env, NARADA_DEV_VAULT_KEY: "7d".repeat(32) },
      stdio: "ignore",
    },
  );
  await expect
    .poll(async () => {
      try {
        return (await ipc(dir, "get_snapshot")).connections.identity.state;
      } catch {
        return "starting";
      }
    })
    .toBe("ready");
  const settings = (await ipc(dir, "get_snapshot")).settings;
  await ipc(dir, "save_settings", {
    ...settings,
    device_name: "Reference desktop",
  });
  await ipc(dir, "configure_connections", {
    enabled: true,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  });
  await ready(page);
  await enable(page);
  await page
    .getByRole("button", { name: "Pair a device", exact: true })
    .click();
  const invitation = page.getByRole("dialog");
  await expect(invitation.locator("canvas")).toBeVisible();
  const code = await invitation.locator("textarea").inputValue();
  await ipc(dir, "join_pairing", { code });
  // The visible QR flow advances after proof; approval itself must remain explicit.
  const approval = page.getByRole("dialog").last();
  await expect(
    approval.getByRole("button", { name: "Approve this device" }),
  ).toBeVisible();
  expect((await rpc(request, "get_snapshot")).data.paired_devices).toBe(0);
  await expect(
    approval.getByRole("button", { name: "Approve this device" }),
  ).toBeDisabled();
  const chatConsent = approval.getByRole("checkbox", {
    name: /Allow private text chat/,
  });
  await expect(chatConsent).not.toBeChecked();
  await chatConsent.check();
  // Only the approval dialog is captured; no bootstrap code/QR secret is archived.
  await approval.screenshot({
    path: "artifacts/desktop-5-9/desktop-approval.png",
  });
  await approval
    .getByRole("checkbox", {
      name: "I compared the requester fingerprint on the other device.",
    })
    .check();
  await approval
    .getByRole("button", { name: "Approve this device", exact: true })
    .click();
  await expect
    .poll(async () => (await rpc(request, "get_snapshot")).data.paired_devices)
    .toBe(1);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Close", exact: true })
    .click();
  const card = page
    .locator(".peer-card")
    .filter({
      has: page.getByRole("heading", {
        name: "Reference desktop",
        exact: true,
      }),
    })
    .first();
  await expect(card.getByText("Connected", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "artifacts/desktop-5-9/desktop-paired.png",
    fullPage: true,
  });
  const approved = (
    await rpc(request, "get_snapshot")
  ).data.connections.peers.find((p) => p.state === "active");
  expect(approved.permissions).toEqual({
    chat: true,
    attachment: false,
    roots: [],
  });
  await card.getByRole("button", { name: "Details & permissions" }).click();
  await expect(page.getByRole("dialog")).toContainText("Never delegated");
  await page.screenshot({
    path: "artifacts/desktop-5-9/desktop-permissions.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Reset to connection-only", exact: true })
    .click();
  const peer = (await rpc(request, "get_snapshot")).data.connections.peers.find(
    (p) => p.state === "active",
  );
  expect(peer.permissions).toEqual(noContent);
  expect(peer.session_epoch).toBeGreaterThan(1);
  await card.getByRole("button", { name: "Details & permissions" }).click();
  await page
    .getByRole("button", { name: "Revoke device", exact: true })
    .click();
  await page.getByRole("button", { name: "Revoke trust", exact: true }).click();
  await expect(card.getByText("Revoked", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "artifacts/desktop-5-9/desktop-revoked.png",
    fullPage: true,
  });
  expect((await rpc(request, "get_snapshot")).data.paired_devices).toBe(0);
  await expect
    .poll(
      async () =>
        (await rpc(request, "get_snapshot")).data.connections.active_sessions,
    )
    .toBe(0);
});
test("pairing and network dialogs meet automated accessibility checks", async ({
  page,
}) => {
  await ready(page);
  await page
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  let results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(results.violations).toEqual([]);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Pair a device", exact: true })
    .click();
  results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(results.violations).toEqual([]);
});
