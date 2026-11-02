import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createConnection } from "node:net";
import fs from "node:fs/promises";
import path from "node:path";
let base, folder, ref, refDir, rootId;
async function raw(request, type, payload) {
  return (
    await request.post("/__narada/rpc", {
      data: {
        version: 1,
        id: randomUUID(),
        command: payload === undefined ? { type } : { type, payload },
      },
    })
  ).json();
}
async function rpc(request, type, payload) {
  const r = await raw(request, type, payload);
  expect(r.ok, JSON.stringify(r.error)).toBe(true);
  return r.data;
}
function ipc(dir, type, payload) {
  return new Promise((resolve, reject) => {
    const socket = createConnection(path.join(dir, "agent.sock"));
    let data = "";
    const id = randomUUID();
    const timer = setTimeout(() => {
      socket.destroy();
      reject(Error("Reference IPC timeout"));
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
    socket.on("data", (c) => {
      data += c;
      if (data.endsWith("\n")) {
        clearTimeout(timer);
        socket.end();
        const r = JSON.parse(data);
        if (!r.ok || r.id !== id)
          reject(Error(r.error?.code || "Invalid response"));
        else resolve(r.data);
      }
    });
    socket.on("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });
}
async function ready(page) {
  await page.goto("/#files");
  await expect(page.locator(".header-status")).toHaveText("Agent online");
  await expect(
    page.getByRole("heading", { name: "Files", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Add folder", exact: true }),
  ).toBeEnabled();
}
async function register(page) {
  await page.getByRole("button", { name: "Add folder", exact: true }).click();
  const d = page.getByRole("dialog");
  await d.getByLabel("Agent-host folder path", { exact: false }).fill(folder);
  await d.getByLabel("Display label").fill("Design library");
  await d
    .getByLabel("Excluded relative prefixes", { exact: false })
    .fill("Private");
  await d.getByRole("button", { name: "Register folder" }).click();
  await expect(d).toHaveCount(0);
  await expect(
    page.locator(".file-row").filter({ hasText: "notes.txt" }),
  ).toBeVisible();
}
async function seeded(request) {
  const root = await rpc(request, "register_root", {
    path: folder,
    label: "Design library",
    exclusions: ["Private"],
  });
  rootId = root.root_id;
  return root;
}
async function reset(request) {
  const roots = await rpc(request, "list_roots");
  for (const r of roots.roots)
    await rpc(request, "remove_root", {
      root_id: r.root_id,
      expected_revision: r.revision,
    });
  const snap = await rpc(request, "get_snapshot");
  if (snap.connections.invitation)
    await rpc(request, "cancel_pairing", {
      invitation_id: snap.connections.invitation.invitation_id,
    });
  for (const p of snap.connections.peers.filter((p) => p.state !== "revoked"))
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
test.beforeEach(async ({ request }) => {
  await reset(request);
  await fs.mkdir("test-fixtures", { recursive: true });
  base = await fs.mkdtemp(path.resolve("test-fixtures/browser-files-"));
  folder = path.join(base, "approved");
  await fs.mkdir(folder);
  await fs.writeFile(
    path.join(folder, "notes.txt"),
    "Actual disposable document.",
  );
  await fs.copyFile(
    "tests/fixtures/file-preview.png",
    path.join(folder, "image.png"),
  );
  await fs.mkdir(path.join(folder, "Sketches"));
  await fs.writeFile(
    path.join(folder, "Sketches", "draft.txt"),
    "nested content",
  );
  await fs.mkdir(path.join(folder, "Private"));
  await fs.writeFile(
    path.join(folder, "Private", "hidden-secret.txt"),
    "excluded fixture",
  );
  await fs.writeFile(path.join(folder, ".env"), "excluded fixture");
});
test.afterEach(async ({ request }) => {
  if (ref) {
    const proc = ref;
    await new Promise((resolve) => {
      if (proc.exitCode !== null) resolve();
      else {
        proc.once("exit", resolve);
        proc.kill("SIGTERM");
      }
    });
    ref = null;
  }
  await reset(request);
  if (base) await fs.rm(base, { recursive: true, force: true });
  base = null;
  refDir = null;
  rootId = null;
});

test("empty Files workspace has no fabricated files and explains explicit registration", async ({
  page,
}) => {
  await ready(page);
  await expect(
    page.getByRole("heading", { name: "A folder. Chosen by you." }),
  ).toBeVisible();
  await expect(page.locator(".file-row")).toHaveCount(0);
  await page.screenshot({
    path: "artifacts/desktop-5-9/files-empty.png",
    fullPage: true,
  });
});
test("register an actual folder through the owner dialog without granting devices", async ({
  page,
  request,
}) => {
  await ready(page);
  await register(page);
  const snap = await rpc(request, "get_snapshot");
  expect(snap.shared_roots).toBe(1);
  expect(
    snap.connections.peers.filter((p) => p.state === "active"),
  ).toHaveLength(0);
  await expect(page.locator(".file-row")).toHaveCount(3);
  await expect(
    page.locator(".file-row").filter({ hasText: "Private" }),
  ).toHaveCount(0);
  await expect(
    page.locator(".file-row").filter({ hasText: ".env" }),
  ).toHaveCount(0);
});
test("tree, breadcrumbs, list and inspector refer to the same actual objects", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  await page
    .getByRole("button", { name: "Sketches", exact: true })
    .last()
    .dblclick();
  await expect(
    page.getByRole("button", { name: "draft.txt", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "draft.txt", exact: true }).click();
  await expect(page.locator(".selected-file-name")).toHaveText("draft.txt");
  await expect(page.locator(".file-detail-list")).toContainText(
    "Sketches/draft.txt",
  );
  await expect(
    page.getByRole("button", { name: "Send to device", exact: true }),
  ).toBeEnabled();
  await page
    .getByRole("button", { name: "Design library", exact: true })
    .last()
    .click();
  await expect(
    page.getByRole("button", { name: "image.png", exact: true }),
  ).toBeVisible();
});
test("search, stable pagination and sorting read the actual index", async ({
  page,
  request,
}) => {
  for (let i = 0; i < 35; i++)
    await fs.writeFile(
      path.join(folder, `sheet-${String(i).padStart(2, "0")}.txt`),
      "catalog fixture",
    );
  await seeded(request);
  await ready(page);
  await expect(page.locator(".file-row")).toHaveCount(16);
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(page.locator(".file-pagination")).toContainText("page 2");
  await page.getByLabel("Search file names").fill("sheet-31");
  await page.getByLabel("Search file names").press("Enter");
  await expect(page.locator(".file-row")).toHaveCount(1);
  await expect(page.locator(".file-row")).toContainText("sheet-31.txt");
  await page.getByLabel("Search file names").fill("");
  await page.getByLabel("Search file names").press("Enter");
  await page.getByLabel("Sort files").selectOption("name_desc");
  await expect(page.locator(".file-row").first()).toContainText("Sketches");
});
test("bounded image preview renders reencoded pixels and the grid is real", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  await page.getByRole("button", { name: "image.png", exact: true }).click();
  await page.getByRole("button", { name: "Load image preview" }).click();
  const img = page.getByRole("img", {
    name: "Re-encoded preview of image.png",
  });
  await expect(img).toBeVisible();
  const size = await img.evaluate((e) => ({
    w: e.naturalWidth,
    h: e.naturalHeight,
    source: e.src,
  }));
  expect(size.w).toBe(32);
  expect(size.h).toBe(20);
  expect(size.source).toMatch(/^data:image\/jpeg;base64,/);
  await page.screenshot({
    path: "artifacts/desktop-5-9/files-browser.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Grid view", exact: true }).click();
  await expect(page.locator(".file-grid .file-row")).toHaveCount(3);
});
test("rename dialog changes real bytes only after confirmation and refuses collisions", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  await page.getByRole("button", { name: "notes.txt", exact: true }).click();
  await page.getByRole("button", { name: "Rename", exact: true }).click();
  await page.getByLabel("New name").fill("image.png");
  await page.getByRole("button", { name: "Rename item", exact: true }).click();
  await expect(page.locator(".form-error")).toContainText(/exists|collision/i);
  expect(await fs.readFile(path.join(folder, "notes.txt"), "utf8")).toBe(
    "Actual disposable document.",
  );
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page.getByRole("button", { name: "Rename", exact: true }).click();
  await page.getByLabel("New name").fill("renamed.txt");
  await page.getByRole("button", { name: "Rename item", exact: true }).click();
  await expect(page.locator("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "renamed.txt", exact: true }),
  ).toBeVisible();
  expect(await fs.readFile(path.join(folder, "renamed.txt"), "utf8")).toBe(
    "Actual disposable document.",
  );
});
test("managed trash and restore preserve bytes and never permanently delete", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  await page.getByRole("button", { name: "notes.txt", exact: true }).click();
  await page
    .getByRole("button", { name: "Move to trash", exact: true })
    .click();
  const d = page.getByRole("dialog");
  await expect(d).toContainText("never automatically purges");
  await d.getByRole("button", { name: "Move to trash", exact: true }).click();
  await expect(d).toHaveCount(0);
  await page
    .getByRole("button", { name: "Managed trash", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Retained notes.txt", exact: true })
    .click();
  await page.getByRole("button", { name: "Restore item", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Restore item", exact: true })
    .click();
  await expect(page.locator("dialog")).toHaveCount(0);
  expect(await fs.readFile(path.join(folder, "notes.txt"), "utf8")).toBe(
    "Actual disposable document.",
  );
});
test("folder policy excludes existing names and unregister never removes originals", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  await page
    .getByRole("button", { name: "Folder policy", exact: true })
    .click();
  await page
    .getByLabel("Excluded relative prefixes", { exact: false })
    .fill("Private\nnotes.txt");
  await page
    .getByRole("button", { name: "Save exclusions", exact: true })
    .click();
  await expect(page.locator("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "notes.txt", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Folder policy", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Unregister folder", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "Original files and retained trash stay on disk",
  );
  await page
    .getByRole("button", { name: "Unregister folder", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "The selected folder is unavailable." }),
  ).toBeVisible();
  await expect(page.locator(".root-node.selected-folder")).toHaveCount(0);
  await expect(page.locator(".file-row")).toHaveCount(0);
  expect(await fs.readFile(path.join(folder, "notes.txt"), "utf8")).toBe(
    "Actual disposable document.",
  );
});
test("resizing supports keyboard and pointer input, persists layout only, and has no critical accessibility issues", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  const sep = page.getByRole("separator", { name: "Resize folder tree" });
  await sep.focus();
  await sep.press("ArrowRight");
  await expect(sep).toHaveAttribute("aria-valuenow", "232");
  const b = await sep.boundingBox();
  await page.mouse.move(b.x + 2, b.y + 30);
  await page.mouse.down();
  await page.mouse.move(b.x + 32, b.y + 30);
  await page.mouse.up();
  await expect(sep).toHaveAttribute("aria-valuenow", "262");
  const stored = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("narada.file-layout")),
  );
  expect(Object.keys(stored).sort()).toEqual(["inspector", "tree"]);
  await page.getByRole("button", { name: "notes.txt", exact: true }).click();
  const a = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(
    a.violations.filter((v) => ["critical", "serious"].includes(v.impact)),
  ).toEqual([]);
});
test("offline transition clears already selected content without fabricating an empty success", async ({
  page,
  request,
}) => {
  await seeded(request);
  await ready(page);
  await page.getByRole("button", { name: "notes.txt", exact: true }).click();
  await page.route("**/__narada/rpc", (route) => route.abort());
  await expect(page.locator(".header-status")).toHaveText("Agent offline", {
    timeout: 12000,
  });
  await expect(page.locator(".file-row")).toHaveCount(0);
  await expect(page.locator(".selected-file-name")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Add folder", exact: true }),
  ).toBeDisabled();
  await expect(page.locator(".file-error")).toContainText("offline");
});
test("folder dialog is keyboard contained and never accepts an entire filesystem", async ({
  page,
}) => {
  await ready(page);
  await page.getByRole("button", { name: "Add folder", exact: true }).click();
  await page.getByLabel("Agent-host folder path", { exact: false }).fill("/");
  await page.getByLabel("Display label").fill("Forbidden");
  await page.getByRole("button", { name: "Register folder" }).click();
  await expect(page.locator(".form-error")).toContainText(
    /specific|disk root|local folder/i,
  );
  await page.keyboard.press("Escape");
  await expect(page.locator("dialog")).toHaveCount(0);
});
test("separate real agent exposes granted roots, then revocation clears the client view", async ({
  page,
  request,
}) => {
  test.skip(
    process.platform === "win32",
    "Unix reference IPC fixture; native Windows qualification is separate.",
  );
  refDir = path.join(base, "reference-profile");
  ref = spawn(
    path.resolve("target/debug/narada-agent"),
    ["--data-dir", refDir],
    {
      env: { ...process.env, NARADA_DEV_VAULT_KEY: "4d".repeat(32) },
      stdio: "ignore",
    },
  );
  await expect
    .poll(async () => {
      try {
        return (await ipc(refDir, "get_snapshot")).connections.identity.state;
      } catch {
        return "starting";
      }
    })
    .toBe("ready");
  const remoteRoot = await ipc(refDir, "register_root", {
    path: folder,
    label: "Reference library",
    exclusions: ["Private"],
  });
  const settings = (await ipc(refDir, "get_snapshot")).settings;
  await ipc(refDir, "save_settings", {
    ...settings,
    device_name: "Reference desktop",
  });
  const enabled = {
    enabled: true,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  };
  await ipc(refDir, "configure_connections", enabled);
  await rpc(request, "configure_connections", enabled);
  const invite = await ipc(refDir, "start_pairing");
  await rpc(request, "join_pairing", { code: invite.code });
  await expect
    .poll(
      async () =>
        (await ipc(refDir, "get_snapshot")).connections.invitation?.pending
          ?.proof_verified,
    )
    .toBe(true);
  const pending = (await ipc(refDir, "get_snapshot")).connections.invitation
    .pending;
  await ipc(refDir, "approve_pairing", {
    attempt_id: pending.attempt_id,
    permissions: {
      chat: false,
      attachment: false,
      roots: [
        { root_id: remoteRoot.root_id, capabilities: ["browse", "read"] },
      ],
    },
  });
  await expect
    .poll(async () =>
      (await rpc(request, "get_snapshot")).connections.peers.some(
        (p) => p.state === "active",
      ),
    )
    .toBe(true);
  await ready(page);
  const peer = (await rpc(request, "get_snapshot")).connections.peers.find(
    (p) => p.state === "active",
  );
  await page.getByLabel("File source").selectOption(peer.peer_id);
  await expect(
    page.getByRole("button", { name: "notes.txt", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "notes.txt", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Rename", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Add folder", exact: true }),
  ).toHaveCount(0);
  const p = (await ipc(refDir, "get_snapshot")).connections.peers.find(
    (p) => p.state === "active",
  );
  await ipc(refDir, "update_peer_permissions", {
    peer_id: p.peer_id,
    expected_revision: p.grant_revision,
    permissions: { chat: false, attachment: false, roots: [] },
  });
  await expect(
    page.getByRole("heading", { name: "The selected folder is unavailable." }),
  ).toBeVisible({ timeout: 12000 });
  await expect(page.locator(".selected-file-name")).toHaveCount(0);
  await expect(page.locator(".file-row")).toHaveCount(0);
  await expect(page.locator(".root-node.selected-folder")).toHaveCount(0);
});

test("owner device-access dialog persists and withdraws an explicit single-root grant", async ({
  page,
  request,
}) => {
  test.skip(
    process.platform === "win32",
    "Unix reference fixture; native Windows qualification is separate.",
  );
  await seeded(request);
  refDir = path.join(base, "grant-reference");
  ref = spawn(
    path.resolve("target/debug/narada-agent"),
    ["--data-dir", refDir],
    {
      env: { ...process.env, NARADA_DEV_VAULT_KEY: "6e".repeat(32) },
      stdio: "ignore",
    },
  );
  await expect
    .poll(async () => {
      try {
        return (await ipc(refDir, "get_snapshot")).connections.identity.state;
      } catch {
        return "starting";
      }
    })
    .toBe("ready");
  const enabled = {
    enabled: true,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  };
  await ipc(refDir, "configure_connections", enabled);
  await rpc(request, "configure_connections", enabled);
  const invite = await rpc(request, "start_pairing");
  await ipc(refDir, "join_pairing", { code: invite.code });
  await expect
    .poll(
      async () =>
        (await rpc(request, "get_snapshot")).connections.invitation?.pending
          ?.proof_verified,
    )
    .toBe(true);
  const pending = (await rpc(request, "get_snapshot")).connections.invitation
    .pending;
  await rpc(request, "approve_pairing", {
    attempt_id: pending.attempt_id,
    permissions: { chat: false, attachment: false, roots: [] },
  });
  await expect
    .poll(async () =>
      (await ipc(refDir, "get_snapshot")).connections.peers.some(
        (p) => p.state === "active",
      ),
    )
    .toBe(true);
  const peer = (await rpc(request, "get_snapshot")).connections.peers.find(
    (p) => p.state === "active",
  );
  await ready(page);
  await page
    .getByRole("button", { name: "Device access", exact: true })
    .click();
  await page.getByLabel("Device to authorize").selectOption(peer.peer_id);
  await page.getByLabel("Folder permission level").selectOption("preview");
  await page.getByRole("button", { name: "Save device access" }).click();
  await expect(page.locator("dialog")).toHaveCount(0);
  const updated = (await rpc(request, "get_snapshot")).connections.peers.find(
    (p) => p.peer_id === peer.peer_id,
  );
  expect(updated.permissions.roots).toEqual([
    { root_id: rootId, capabilities: ["browse", "read"] },
  ]);
  const host = (await ipc(refDir, "get_snapshot")).connections.peers.find(
    (p) => p.state === "active",
  );
  expect(
    (
      await ipc(refDir, "remote_file_query", {
        peer_id: host.peer_id,
        query: { kind: "roots" },
      })
    ).roots,
  ).toHaveLength(1);
  await page
    .getByRole("button", { name: "Device access", exact: true })
    .click();
  await page.getByLabel("Device to authorize").selectOption(peer.peer_id);
  await expect(page.getByLabel("Folder permission level")).toHaveValue(
    "preview",
  );
  await page.getByLabel("Folder permission level").selectOption("none");
  await page.getByRole("button", { name: "Save device access" }).click();
  await expect(page.locator("dialog")).toHaveCount(0);
  expect(
    (
      await ipc(refDir, "remote_file_query", {
        peer_id: host.peer_id,
        query: { kind: "roots" },
      })
    ).roots,
  ).toEqual([]);
});

test("folder selection survives navigation instead of returning to the first root", async ({
  page,
  request,
}) => {
  await seeded(request);
  const other = path.join(base, "other");
  await fs.mkdir(other);
  await fs.writeFile(
    path.join(other, "other-only.txt"),
    "Disposable second folder",
  );
  await rpc(request, "register_root", {
    path: other,
    label: "Other folder",
    exclusions: [],
  });
  await ready(page);
  await page
    .locator(".folder-tree")
    .getByRole("button", { name: "Other folder", exact: true })
    .click();
  await expect(
    page.locator(".file-row").filter({ hasText: "other-only.txt" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Home", exact: true }).click();
  await page.getByRole("button", { name: "Files", exact: true }).click();
  await expect(page.locator(".root-node.selected-folder")).toHaveText(
    "Other folder",
  );
  await expect(
    page.locator(".file-row").filter({ hasText: "other-only.txt" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Folder policy", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Unregister folder", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Unregister folder", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".root-node.selected-folder")).toHaveCount(0);
  await expect(page.locator(".file-row")).toHaveCount(0);
  await expect(
    page
      .locator(".folder-tree")
      .getByRole("button", { name: "Design library", exact: true }),
  ).toBeVisible();
});
test("a removed selection never silently opens the remaining folder", async ({
  page,
  request,
}) => {
  const first = await seeded(request);
  const other = path.join(base, "other");
  await fs.mkdir(other);
  await fs.writeFile(
    path.join(other, "other-only.txt"),
    "Not automatically selected",
  );
  await rpc(request, "register_root", {
    path: other,
    label: "Other folder",
    exclusions: [],
  });
  await ready(page);
  await page
    .locator(".folder-tree")
    .getByRole("button", { name: "Design library", exact: true })
    .click();
  await expect(
    page.locator(".file-row").filter({ hasText: "notes.txt" }),
  ).toBeVisible();
  await rpc(request, "remove_root", {
    root_id: first.root_id,
    expected_revision: first.revision,
  });
  await expect(
    page
      .locator(".folder-tree")
      .getByRole("button", { name: "Design library", exact: true }),
  ).toHaveCount(0);
  await expect(page.locator(".root-node.selected-folder")).toHaveCount(0);
  await expect(page.locator(".file-row")).toHaveCount(0);
  await expect(page.locator(".files-page")).toContainText(
    "None was opened automatically",
  );
  await page
    .locator(".folder-tree")
    .getByRole("button", { name: "Other folder", exact: true })
    .click();
  await expect(
    page.locator(".file-row").filter({ hasText: "other-only.txt" }),
  ).toBeVisible();
});
