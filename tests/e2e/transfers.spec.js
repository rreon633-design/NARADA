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

test("sharing connection shortcut navigates without pairing or granting folders", async ({
  page,
  request,
}) => {
  await ready(page);
  await expect(
    page.getByText(
      "Original bytes. No compression. Every destination chosen by you.",
    ),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Devices & connection", exact: true })
    .click();
  await expect(page).toHaveURL(/#devices$/);
  const snapshot = await rpc(request, "get_snapshot");
  expect(snapshot.connections.invitation).toBeNull();
  expect(
    snapshot.connections.peers.filter((p) => p.state === "active"),
  ).toHaveLength(0);
});

// Synthetic UI snapshots only. These cases do not claim network throughput or publication.
function progressFixture(verified = "0") {
  return {
    jobs: [
      {
        transfer_id: "a0a0a0a0-1234-4234-8234-123456789abc",
        peer_id: "a".repeat(64),
        direction: "download",
        name: "Synthetic progress fixture.bin",
        state: "running",
        phase: "transferring",
        size: "16",
        verified_bytes: verified,
        sha256: "b".repeat(64),
        result: null,
        ack_pending: false,
      },
    ],
    receives: [],
    offers: [],
    sent_offers: [],
    limits: {},
  };
}
test("synthetic progress respects native style policy, reduced motion and finite animation", async ({
  page,
}) => {
  await page.addInitScript(() => {
    window.__transferMotions = [];
    const original = Element.prototype.animate;
    Element.prototype.animate = function (frames, options) {
      const animation = original.call(this, frames, options);
      if (this.classList.contains("transfer-meter-fill"))
        window.__transferMotions.push({ frames, options, animation });
      return animation;
    };
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  let fixture = progressFixture();
  await page.route("**/__narada/rpc", async (route) => {
    const req = route.request().postDataJSON();
    if (req.command?.type !== "list_transfers") return route.continue();
    await route.fulfill({
      json: { version: 1, id: req.id, ok: true, data: fixture, error: null },
    });
  });
  await ready(page);
  const meter = page.getByRole("progressbar", {
    name: "Verified bytes for Synthetic progress fixture.bin",
  });
  await expect(meter).toHaveAttribute("aria-valuenow", "0");
  // Exercise the native CSP's inline-attribute restriction without blocking Vite's
  // already-loaded development style elements. This is a browser policy fixture,
  // not an installed Windows WebView/GUI qualification.
  expect(
    await page.evaluate(() => {
      const policy = document.createElement("meta");
      policy.httpEquiv = "Content-Security-Policy";
      policy.content = "style-src-attr 'none'";
      document.head.append(policy);
      const probe = document.createElement("div");
      probe.setAttribute("style", "opacity: 0.12345");
      document.body.append(probe);
      const observed = getComputedStyle(probe).opacity;
      probe.remove();
      return observed;
    }),
  ).toBe("1");
  fixture = progressFixture("8");
  await expect(meter).toHaveAttribute("aria-valuenow", "50");
  await expect(meter).toHaveAttribute(
    "aria-valuetext",
    "8 of 16 bytes verified",
  );
  await expect(meter.locator(".transfer-meter-fill")).toHaveCSS(
    "transform",
    "matrix(0.5, 0, 0, 1, 0, 0)",
  );
  expect(await page.evaluate(() => window.__transferMotions.length)).toBe(0);
  await page.emulateMedia({ reducedMotion: "no-preference" });
  fixture = progressFixture("4");
  await expect(meter).toHaveAttribute("aria-valuenow", "25");
  expect(await page.evaluate(() => window.__transferMotions.length)).toBe(0);
  fixture = progressFixture("12");
  await expect(meter).toHaveAttribute("aria-valuenow", "75");
  expect(
    await page.evaluate(() =>
      window.__transferMotions.map((m) => ({
        frames: m.frames,
        duration: m.options.duration,
      })),
    ),
  ).toEqual([
    {
      frames: [{ transform: "scaleX(0.25)" }, { transform: "scaleX(0.75)" }],
      duration: 180,
    },
  ]);
  await page
    .getByRole("button", { name: "Devices & connection", exact: true })
    .click();
  expect(
    await page.evaluate(() =>
      window.__transferMotions.some((m) => m.animation.playState === "running"),
    ),
  ).toBe(false);
});
test("synthetic completed state without a receipt cannot become a verified delivery", async ({
  page,
}) => {
  const fixture = progressFixture("16");
  fixture.jobs[0].state = "completed";
  await page.route("**/__narada/rpc", async (route) => {
    const req = route.request().postDataJSON();
    if (req.command?.type !== "list_transfers") return route.continue();
    await route.fulfill({
      json: { version: 1, id: req.id, ok: true, data: fixture, error: null },
    });
  });
  await ready(page);
  await expect(
    page.getByRole("alert").filter({ hasText: "matching publication receipt" }),
  ).toBeVisible();
  await expect(
    page.locator(`[data-transfer-id="${fixture.jobs[0].transfer_id}"]`),
  ).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "Queue unavailable", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".transfer-stat strong")).toHaveText([
    "—",
    "—",
    "—",
  ]);
});
async function openSyntheticDownloadReview(page, request) {
  const registered = await rpc(request, "register_root", {
    path: folder,
    label: "Reviewed destination",
    exclusions: [],
  });
  await page.evaluate(async (root) => {
    window.__reviewNavigations = [];
    const { openTransferDialog } =
      await import("/src/components/transfer-dialog.js");
    await openTransferDialog(
      {
        snapshot: { connections: { peers: [] } },
        navigate: (route) => window.__reviewNavigations.push(route),
      },
      {
        mode: "download",
        device: "a".repeat(64),
        root: { root_id: root },
        entry: {
          object_id: "b0b0b0b0-1234-4234-8234-123456789abc",
          version: "b".repeat(64),
          name: "reviewed.bin",
          size: "16",
        },
      },
    );
  }, registered.root_id);
  await page
    .getByRole("combobox", { name: "Destination folder", exact: true })
    .selectOption(registered.root_id);
}
test("unconfirmed transfer dialog retries the same frozen arguments and ID", async ({
  page,
  request,
}) => {
  await ready(page);
  const attempts = [];
  await page.route("**/__narada/rpc", async (route) => {
    const req = route.request().postDataJSON();
    if (req.command?.type !== "start_transfer") return route.continue();
    attempts.push(req.command.payload);
    await route.fulfill({
      json: {
        version: 1,
        id: req.id,
        ok: false,
        data: null,
        error: { code: "IPC_UNAVAILABLE", message: "Synthetic reply loss" },
      },
    });
  });
  await openSyntheticDownloadReview(page, request);
  const submit = page.getByRole("button", {
    name: "Start download",
    exact: true,
  });
  await submit.click();
  await expect(page.getByRole("dialog")).toContainText(
    "Result unconfirmed. Check Transfers before starting another copy.",
  );
  await expect(
    page.getByRole("textbox", { name: "Destination filename", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("combobox", { name: "Destination folder", exact: true }),
  ).toBeDisabled();
  await page
    .getByRole("textbox", { name: "Destination filename", exact: true })
    .evaluate((el) => {
      el.value = "changed-after-submission.bin";
    });
  await submit.click();
  await expect.poll(() => attempts.length).toBe(2);
  expect(attempts[1]).toEqual(attempts[0]);
  expect(attempts[0].destination.name).toBe("reviewed.bin");
  await page
    .getByRole("button", { name: "Check Transfers", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(await page.evaluate(() => window.__reviewNavigations)).toEqual([
    "transfers",
  ]);
});
test("closed transfer review cannot navigate on a late submission result", async ({
  page,
  request,
}) => {
  await ready(page);
  let release;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  let admitted = 0;
  await page.route("**/__narada/rpc", async (route) => {
    const req = route.request().postDataJSON();
    if (req.command?.type !== "start_transfer") return route.continue();
    admitted++;
    await held;
    await route.fulfill({
      json: {
        version: 1,
        id: req.id,
        ok: true,
        data: { transfer_id: req.command.payload.transfer_id, queued: true },
        error: null,
      },
    });
  });
  await openSyntheticDownloadReview(page, request);
  await page
    .getByRole("button", { name: "Start download", exact: true })
    .click();
  await expect.poll(() => admitted).toBe(1);
  await page.getByRole("button", { name: "Not now", exact: true }).click();
  await page.getByRole("button", { name: "Browse files", exact: true }).click();
  await expect(page).toHaveURL(/#files$/);
  const response = page.waitForResponse(
    (r) =>
      r.url().endsWith("/__narada/rpc") &&
      r.request().postDataJSON()?.command?.type === "start_transfer",
  );
  release();
  await response;
  await page.waitForTimeout(100);
  expect(await page.evaluate(() => window.__reviewNavigations)).toEqual([]);
  await expect(page).toHaveURL(/#files$/);
});
