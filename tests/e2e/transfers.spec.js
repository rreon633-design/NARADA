import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID, createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { createConnection } from "node:net";
import fs from "node:fs/promises";
import path from "node:path";
let base, folder, refFolder, refDir, ref, rootId, refRoot, peerId;
async function rpc(request, type, payload) {
  const response = await (
    await request.post("/__narada/rpc", {
      data: {
        version: 1,
        id: randomUUID(),
        command: payload === undefined ? { type } : { type, payload },
      },
    })
  ).json();
  expect(response.ok, JSON.stringify(response.error)).toBe(true);
  return response.data;
}
function ipc(type, payload) {
  return new Promise((resolve, reject) => {
    const id = randomUUID(),
      socket = createConnection(path.join(refDir, "agent.sock"));
    let data = "";
    const timer = setTimeout(() => {
      socket.destroy();
      reject(Error("Reference agent timeout"));
    }, 8000);
    socket.on("connect", () =>
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
        const response = JSON.parse(data);
        response.ok
          ? resolve(response.data)
          : reject(Error(response.error.code));
      }
    });
    socket.on("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });
}
async function reset(request) {
  for (const j of (await rpc(request, "list_transfers")).jobs)
    if (!["completed", "cancelled"].includes(j.state))
      await rpc(request, "transfer_control", {
        transfer_id: j.transfer_id,
        target: "job",
        action: "cancel",
        name: null,
      });
  for (const r of (await rpc(request, "list_transfers")).receives)
    if (!["completed", "cancelled", "cleaned"].includes(r.state))
      await rpc(request, "transfer_control", {
        transfer_id: r.transfer_id,
        target: "receive",
        action: "cancel",
        name: null,
      });
  const snapshot = await rpc(request, "get_snapshot");
  if (snapshot.connections.invitation)
    await rpc(request, "cancel_pairing", {
      invitation_id: snapshot.connections.invitation.invitation_id,
    });
  for (const peer of snapshot.connections.peers.filter(
    (p) => p.state !== "revoked",
  ))
    await rpc(request, "revoke_peer", {
      peer_id: peer.peer_id,
      expected_revision: peer.grant_revision,
    });
  for (const root of (await rpc(request, "list_roots")).roots)
    await rpc(request, "remove_root", {
      root_id: root.root_id,
      expected_revision: root.revision,
    });
  await rpc(request, "configure_connections", {
    enabled: false,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  });
}
test.beforeEach(async ({ request }) => {
  await reset(request);
  await fs.mkdir("test-fixtures", { recursive: true });
  base = await fs.mkdtemp(path.resolve("test-fixtures/browser-transfers-"));
  folder = path.join(base, "local-approved");
  await fs.mkdir(folder);
  await fs.writeFile(
    path.join(folder, "project-notes.txt"),
    "Actual transfer fixture. No mocked progress.",
  );
});
test.afterEach(async ({ request }) => {
  if (ref) {
    const p = ref;
    await new Promise((resolve) => {
      if (p.exitCode !== null) resolve();
      else {
        p.once("exit", resolve);
        p.kill("SIGTERM");
      }
    });
    ref = null;
  }
  await reset(request);
  await fs.rm(base, { recursive: true, force: true });
});
async function ready(page) {
  await page.goto("/#transfers");
  await expect(
    page.getByRole("heading", { name: "Transfers", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Loading your queue…", { exact: true }),
  ).toHaveCount(0);
}
async function pair(request) {
  rootId = (
    await rpc(request, "register_root", {
      path: folder,
      label: "Desktop transfers",
      exclusions: [],
    })
  ).root_id;
  refFolder = path.join(base, "reference-approved");
  await fs.mkdir(refFolder);
  await fs.writeFile(
    path.join(refFolder, "reference-notes.txt"),
    "Verified bytes from the separate reference agent.",
  );
  refDir = path.join(base, "reference-profile");
  ref = spawn(
    path.resolve("target/debug/narada-agent"),
    ["--data-dir", refDir],
    {
      env: { ...process.env, NARADA_DEV_VAULT_KEY: "39".repeat(32) },
      stdio: "ignore",
    },
  );
  await expect
    .poll(async () => {
      try {
        return (await ipc("get_snapshot")).connections.identity.state;
      } catch {
        return null;
      }
    })
    .toBe("ready");
  refRoot = (
    await ipc("register_root", {
      path: refFolder,
      label: "Reference transfers",
      exclusions: [],
    })
  ).root_id;
  await rpc(request, "configure_connections", {
    enabled: true,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  });
  await ipc("configure_connections", {
    enabled: true,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  });
  // The visible desktop joins the reference agent, retaining a real outbound endpoint.
  const invite = await ipc("start_pairing");
  await rpc(request, "join_pairing", { code: invite.code });
  await expect
    .poll(
      async () =>
        (await ipc("get_snapshot")).connections.invitation?.pending
          ?.proof_verified,
    )
    .toBe(true);
  const pending = (await ipc("get_snapshot")).connections.invitation.pending;
  await ipc("approve_pairing", {
    attempt_id: pending.attempt_id,
    permissions: {
      chat: false,
      attachment: false,
      roots: [{ root_id: refRoot, capabilities: ["browse", "read", "create"] }],
    },
  });
  await expect
    .poll(async () =>
      (await rpc(request, "get_snapshot")).connections.peers.some(
        (p) => p.state === "active" && p.connection_state === "online",
      ),
    )
    .toBe(true);
  peerId = (await rpc(request, "get_snapshot")).connections.peers.find(
    (p) => p.state === "active",
  ).peer_id;
}
test("real transfer workspace has bounded empty-state controls", async ({
  page,
}) => {
  await ready(page);
  await expect(
    page.getByRole("button", { name: "Browse files", exact: true }),
  ).toBeEnabled();
  await expect(page.getByText("64 MiB", { exact: true })).toBeVisible();
  await expect(
    page.getByText("No loaded offers", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(/100% staged does not mean delivered/),
  ).toBeVisible();
  await page.screenshot({
    path: "artifacts/desktop-5-9/transfers-empty.png",
    fullPage: true,
  });
});
test("cleanup asks for confirmation and promises no published-file deletion", async ({
  page,
}) => {
  await ready(page);
  await page
    .getByRole("button", { name: "Clean eligible staging", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "unknown files and published copies are preserved",
  );
  await page.getByRole("button", { name: "Keep staging", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
test("download dialog creates a real verified copy from the reference agent", async ({
  page,
  request,
}) => {
  await pair(request);
  await page.goto("/#files");
  await page.getByLabel("File source", { exact: true }).selectOption(peerId);
  await page
    .getByRole("button", { name: "reference-notes.txt", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Download file", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "Existing names are never overwritten",
  );
  await page
    .getByLabel("Destination folder", { exact: true })
    .selectOption(rootId);
  await page
    .getByLabel("Destination filename", { exact: true })
    .fill("verified-copy.txt");
  await page
    .getByRole("button", { name: "Start download", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Transfers", exact: true }),
  ).toBeVisible();
  const row = page.locator(".transfer-row-real").filter({
    has: page.getByRole("heading", {
      name: "verified-copy.txt",
      exact: true,
    }),
  });
  await expect(row).toContainText("Completed", { timeout: 18000 });
  await expect(row).toContainText("SHA-256 verified & published");
  expect(
    await fs.readFile(path.join(folder, "verified-copy.txt"), "utf8"),
  ).toBe("Verified bytes from the separate reference agent.");
  await row
    .getByRole("button", { name: "Details for verified-copy.txt", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    createHash("sha256")
      .update("Verified bytes from the separate reference agent.")
      .digest("hex"),
  );
  await page.keyboard.press("Escape");
  await page.screenshot({
    path: "artifacts/desktop-5-9/transfers-completed.png",
    fullPage: true,
  });
});
test("a pull offer from an inbound peer requires local destination consent", async ({
  page,
  request,
}) => {
  await pair(request);
  const source = (
    await ipc("file_query", {
      kind: "browse",
      root_id: refRoot,
      directory_id: null,
      cursor: null,
      sort: "name",
    })
  ).entries[0];
  const recipient = (await ipc("get_snapshot")).connections.peers.find(
    (p) => p.state === "active",
  );
  const transferId = randomUUID();
  await ipc("create_transfer_offer", {
    transfer_id: transferId,
    peer_id: recipient.peer_id,
    source: {
      root_id: refRoot,
      object_id: source.object_id,
      expected_version: source.version,
    },
  });
  await ready(page);
  await page
    .getByRole("button", { name: "Check for offers", exact: true })
    .click();
  await expect(
    page.locator(".transfer-offer").filter({ hasText: "reference-notes.txt" }),
  ).toBeVisible({ timeout: 18000 });
  await page
    .getByRole("button", { name: "Choose destination", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "You are approving a copy",
  );
  await expect(
    page.getByRole("button", { name: "Accept & download", exact: true }),
  ).toBeDisabled();
  await page
    .getByLabel("Destination folder", { exact: true })
    .selectOption(rootId);
  await page
    .getByLabel("Destination filename", { exact: true })
    .fill("accepted-offer.txt");
  await page
    .getByRole("button", { name: "Accept & download", exact: true })
    .click();
  await expect(
    page.locator(`[data-transfer-id="${transferId}"]`),
  ).toContainText("Completed", { timeout: 18000 });
  expect(
    await fs.readFile(path.join(folder, "accepted-offer.txt"), "utf8"),
  ).toBe("Verified bytes from the separate reference agent.");
});
test("offline transfer state never invents progress", async ({ page }) => {
  await ready(page);
  await page.route("**/__narada/rpc", (r) => r.abort("connectionrefused"));
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: /unavailable|last loaded/i })
      .first(),
  ).toBeVisible({ timeout: 10000 });
});
test("transfer filters and cleanup preserve keyboard focus", async ({
  page,
}) => {
  await ready(page);
  const completed = page.getByRole("button", { name: /^Completed \(/ });
  await completed.click();
  await expect(completed).toBeFocused();
  await page
    .getByRole("button", { name: "Clean eligible staging", exact: true })
    .click();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "Clean eligible staging", exact: true }),
  ).toBeFocused();
});
test("transfer workspace remains accessible without fake activity", async ({
  page,
}) => {
  await ready(page);
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(result.violations).toEqual([]);
});
test("compact transfer layout has no horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 800 });
  await ready(page);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "artifacts/desktop-5-9/transfers-compact.png",
    fullPage: true,
  });
});
