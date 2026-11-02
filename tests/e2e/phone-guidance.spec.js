import { test, expect } from "@playwright/test";
import { rpc } from "./content-fixture.js";

// Browser DOM plus an explicitly stubbed native IPC boundary: NOT a Tauri/Windows/LAN run.
const loopback = {
  name: "Loopback",
  ip: "127.0.0.1",
  eligible: true,
  profile: "loopback",
};
const lan = (ip) => ({
  name: "Trusted Wi-Fi",
  ip,
  eligible: true,
  profile: "private",
});
async function nativeFixture(page, request, interfaces, options = {}) {
  const snapshot = await rpc(request, "get_snapshot");
  snapshot.connections.config = {
    enabled: false,
    bind_ip: "127.0.0.1",
    api_port: 35680,
    discovery: false,
    ...options.config,
  };
  snapshot.connections.identity.state = "ready";
  snapshot.connections.interfaces = interfaces;
  snapshot.connections.listening = options.listening || false;
  snapshot.connections.peers = [];
  snapshot.connections.invitation = null;
  snapshot.connections.join = null;
  await page.addInitScript((value) => {
    window.nativeFixtureCalls = [];
    window.nativeFixtureState = value;
    window.__TAURI_INTERNALS__ = {
      invoke: async (cmd, args) => {
        window.nativeFixtureCalls.push({ cmd, args });
        if (window.nativeFixturePause === cmd)
          await new Promise((resolve) => {
            window.nativeFixtureContinue = resolve;
          });
        if (cmd === "get_snapshot") return structuredClone(value);
        if (cmd === "file_query" && args.payload.kind === "roots")
          return { roots: [] };
        if (cmd === "choose_root") return null;
        if (cmd === "configure_connections") {
          value.connections.config = args.payload;
          value.connections.listening = true;
          return null;
        }
        if (cmd === "start_pairing") {
          value.connections.invitation = {
            invitation_id: crypto.randomUUID(),
            expires_at: new Date(Date.now() + 120000).toISOString(),
            pending: null,
          };
          return {
            ...value.connections.invitation,
            code: "DISPOSABLE_UI_FIXTURE_NOT_A_REAL_PAIRING_CODE",
          };
        }
        if (cmd === "cancel_pairing" || cmd === "reject_pairing") {
          value.connections.invitation = null;
          return null;
        }
        if (cmd === "approve_pairing") {
          value.connections.invitation.pending.approved = true;
          return null;
        }
        throw {
          code: "TEST_BOUNDARY",
          message: "Fixture stops before real pairing",
        };
      },
    };
  }, snapshot);
  await page.goto("/#devices");
  await expect(
    page.getByRole("heading", { name: "Your devices", exact: true }),
  ).toBeVisible();
}
const openPhone = (page) =>
  page
    .getByRole("button", { name: /^(Enable & show QR|Connect your phone)$/ })
    .click();
const calls = (page, cmd) =>
  page.evaluate(
    (command) => window.nativeFixtureCalls.filter((c) => c.cmd === command),
    cmd,
  );
async function provenRequest(page) {
  await page.evaluate(() => {
    window.nativeFixtureState.connections.invitation.pending = {
      attempt_id: crypto.randomUUID(),
      fingerprint: "b".repeat(64),
      name: "Fixture phone",
      platform: "android",
      proof_verified: true,
      approved: false,
    };
  });
}
async function showQr(page, request) {
  await nativeFixture(page, request, [loopback, lan("192.168.1.20")]);
  await openPhone(page);
  await expect(page.locator("dialog.pairing-modal canvas")).toBeVisible();
  return page.locator("dialog.pairing-modal");
}
const configured = (page) =>
  page.evaluate(() =>
    window.nativeFixtureCalls.filter((c) => c.cmd === "configure_connections"),
  );
test("one explicit Enable & show QR action chooses the sole private adapter without enabling on page load", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [loopback, lan("192.168.1.20")]);
  expect(await configured(page)).toEqual([]);
  expect(await calls(page, "start_pairing")).toEqual([]);
  await page
    .getByRole("button", { name: "Enable & show QR", exact: true })
    .click();
  await expect(page.locator("dialog.pairing-modal canvas")).toBeVisible();
  expect((await configured(page))[0].args.payload).toEqual({
    enabled: true,
    bind_ip: "192.168.1.20",
    api_port: 35680,
    discovery: false,
  });
  expect(await configured(page)).toHaveLength(1);
  expect(await calls(page, "start_pairing")).toHaveLength(1);
  await expect(page.getByLabel("Network interface")).not.toBeVisible();
});
test("a phone cannot be configured using only loopback or an ineligible public adapter", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [
    loopback,
    { ...lan("192.168.1.20"), eligible: false, profile: "public" },
  ]);
  await openPhone(page);
  await expect(page.getByLabel("Network interface")).toHaveValue("");
  await expect(page.locator('option[value="127.0.0.1"]')).toBeDisabled();
  await expect(page.getByRole("dialog")).toContainText(
    "No eligible private network",
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  expect(await configured(page)).toEqual([]);
});
test("multiple private adapters require an explicit selection", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [
    loopback,
    lan("192.168.1.20"),
    lan("10.0.0.20"),
  ]);
  await openPhone(page);
  await expect(page.getByLabel("Network interface")).toHaveValue("");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  expect(await configured(page)).toEqual([]);
  await page.getByLabel("Network interface").selectOption("10.0.0.20");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  await expect.poll(async () => (await configured(page)).length).toBe(1);
  expect((await configured(page))[0].args.payload.bind_ip).toBe("10.0.0.20");
});

test("native folder entry is picker-only and cancellation grants no access", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [loopback, lan("192.168.1.20")]);
  expect(await configured(page)).toEqual([]);
  await page.getByRole("button", { name: "Files", exact: true }).click();
  await expect(
    page.getByRole("heading", {
      name: "A folder. Chosen by you.",
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.locator(".file-error")).toHaveCount(0);
  await page.getByRole("button", { name: "Add folder", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByLabel("Selected folder", { exact: false }),
  ).not.toBeEditable();
  await dialog
    .getByRole("button", { name: "Choose folder…", exact: true })
    .click();
  await expect(
    dialog.getByLabel("Selected folder", { exact: false }),
  ).toHaveValue("");
  await dialog
    .getByRole("button", { name: "Register folder", exact: true })
    .click();
  await expect(dialog).toContainText(
    "Choose a folder and give it a display label",
  );
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  const calls = await page.evaluate(() => window.nativeFixtureCalls);
  expect(calls.filter((c) => c.cmd === "choose_root")).toHaveLength(1);
  expect(
    calls.filter((c) =>
      ["register_root", "update_peer_permissions"].includes(c.cmd),
    ),
  ).toEqual([]);
});

test("saved private interface, port and discovery preferences survive the fast path", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [lan("192.168.1.20"), lan("10.0.0.20")], {
    config: { bind_ip: "10.0.0.20", api_port: 45678, discovery: true },
  });
  await openPhone(page);
  await expect(page.locator("dialog.pairing-modal canvas")).toBeVisible();
  expect((await configured(page))[0].args.payload).toEqual({
    enabled: true,
    bind_ip: "10.0.0.20",
    api_port: 45678,
    discovery: true,
  });
});
test("already listening private connection goes straight to QR without reconfiguration", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [lan("192.168.1.20")], {
    config: { enabled: true, bind_ip: "192.168.1.20" },
    listening: true,
  });
  await openPhone(page);
  await expect(page.locator("dialog.pairing-modal canvas")).toBeVisible();
  expect(await configured(page)).toEqual([]);
  expect(await calls(page, "start_pairing")).toHaveLength(1);
});
test("duplicate taps are single-flight and leaving during setup cannot open a late QR", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [lan("192.168.1.20")]);
  await page.evaluate(() => {
    window.nativeFixturePause = "configure_connections";
  });
  await page
    .getByRole("button", { name: "Enable & show QR", exact: true })
    .evaluate((button) => {
      button.dispatchEvent(new Event("click"));
      button.dispatchEvent(new Event("click"));
    });
  await expect(
    page.getByRole("button", { name: "Preparing QR…", exact: true }),
  ).toBeDisabled();
  expect(await configured(page)).toHaveLength(1);
  await page.getByRole("button", { name: "Home", exact: true }).click();
  await page.evaluate(() => window.nativeFixtureContinue());
  await expect
    .poll(async () => (await calls(page, "get_snapshot")).length)
    .toBeGreaterThan(1);
  await page.getByRole("button", { name: "Devices", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Connect your phone", exact: true }),
  ).toBeEnabled();
  expect(await calls(page, "start_pairing")).toEqual([]);
  await expect(page.locator("dialog[open]")).toHaveCount(0);
});
test("an invitation completed after its page closes is cancelled without displaying its code", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [lan("192.168.1.20")]);
  await page.evaluate(() => {
    window.nativeFixturePause = "start_pairing";
  });
  await openPhone(page);
  await expect
    .poll(async () => (await calls(page, "start_pairing")).length)
    .toBe(1);
  await page.getByRole("button", { name: "Home", exact: true }).click();
  await page.evaluate(() => window.nativeFixtureContinue());
  await expect
    .poll(async () => (await calls(page, "cancel_pairing")).length)
    .toBe(1);
  await expect(page.locator("dialog[open]")).toHaveCount(0);
});
test("closing the adapter chooser during configuration suppresses the follow-on invitation", async ({
  page,
  request,
}) => {
  await nativeFixture(page, request, [lan("192.168.1.20"), lan("10.0.0.20")]);
  await openPhone(page);
  await page.getByLabel("Network interface").selectOption("10.0.0.20");
  await page.evaluate(() => {
    window.nativeFixturePause = "configure_connections";
  });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enable connections", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Not now", exact: true })
    .click();
  await page.evaluate(() => window.nativeFixtureContinue());
  await expect(
    page.getByRole("button", { name: "Connect your phone", exact: true }),
  ).toBeEnabled();
  expect(await calls(page, "start_pairing")).toEqual([]);
  await expect(page.locator("dialog[open]")).toHaveCount(0);
});
test("proof opens one unchecked review, dismissal does not reopen it, and final phone confirmation is still required", async ({
  page,
  request,
}) => {
  const qr = await showQr(page, request);
  await page.bringToFront();
  expect(await page.evaluate(() => document.hasFocus())).toBe(true);
  await provenRequest(page);
  let approval = page.getByRole("dialog", {
    name: "Trust Fixture phone?",
    exact: true,
  });
  await expect(approval).toBeVisible();
  await expect(page.locator("dialog[open]")).toHaveCount(2);
  await expect(
    approval.getByRole("checkbox", { name: /I compared/ }),
  ).not.toBeChecked();
  await expect(
    approval.getByRole("checkbox", { name: /Allow private text chat/ }),
  ).not.toBeChecked();
  await expect(
    approval.getByRole("button", { name: "Approve this device" }),
  ).toBeDisabled();
  expect(await calls(page, "approve_pairing")).toEqual([]);
  await approval
    .getByRole("button", { name: "Close dialog", exact: true })
    .click();
  const reads = (await calls(page, "get_snapshot")).length;
  await expect
    .poll(async () => (await calls(page, "get_snapshot")).length)
    .toBeGreaterThan(reads + 1);
  await expect(page.locator("dialog[open]")).toHaveCount(1);
  await qr.getByRole("button", { name: "Review request", exact: true }).click();
  await expect(
    approval.getByRole("checkbox", { name: /I compared/ }),
  ).not.toBeChecked();
  await approval.getByRole("checkbox", { name: /I compared/ }).check();
  await page.evaluate(() => {
    window.nativeFixturePause = "approve_pairing";
  });
  await approval
    .getByRole("button", { name: "Approve this device", exact: true })
    .click();
  await expect(
    approval.getByRole("button", { name: "Approve this device" }),
  ).toBeDisabled();
  await expect(
    approval.getByRole("checkbox", { name: /Allow private text chat/ }),
  ).toBeDisabled();
  await page.evaluate(() => window.nativeFixtureContinue());
  await expect(approval).not.toBeVisible();
  await expect(qr).toContainText(
    "Approved. Waiting for final acknowledgement.",
  );
  expect(await calls(page, "approve_pairing")).toHaveLength(1);
  expect(
    (await calls(page, "approve_pairing"))[0].args.payload.permissions,
  ).toEqual({ chat: false, attachment: false, roots: [] });
  expect(
    await page.evaluate(() => window.nativeFixtureState.connections.peers),
  ).toEqual([]);
});
test("unfocused or closed QR flows never raise an automatic approval dialog", async ({
  page,
  request,
}) => {
  const qr = await showQr(page, request);
  await page.evaluate(() => {
    document.hasFocus = () => false;
  });
  await provenRequest(page);
  await expect(
    qr.getByRole("button", { name: "Review request", exact: true }),
  ).toBeVisible();
  await expect(page.locator("dialog[open]")).toHaveCount(1);
  await page.evaluate(() => {
    history.pushState(null, "", "#home");
    window.dispatchEvent(new PopStateEvent("popstate"));
    document.hasFocus = () => true;
  });
  await expect(page.locator("dialog[open]")).toHaveCount(0);
  await page.getByRole("button", { name: "Devices", exact: true }).click();
  const reads = (await calls(page, "get_snapshot")).length;
  await expect
    .poll(async () => (await calls(page, "get_snapshot")).length)
    .toBeGreaterThan(reads + 1);
  await expect(page.locator("dialog[open]")).toHaveCount(0);
  expect(await calls(page, "approve_pairing")).toEqual([]);
});
test("a request that changes during review is disabled rather than approving stale identity", async ({
  page,
  request,
}) => {
  await showQr(page, request);
  await provenRequest(page);
  const approval = page.getByRole("dialog", {
    name: "Trust Fixture phone?",
    exact: true,
  });
  await expect(approval).toBeVisible();
  await approval.getByRole("checkbox", { name: /I compared/ }).check();
  await page.evaluate(() => {
    window.nativeFixtureState.connections.invitation.pending.fingerprint =
      "c".repeat(64);
  });
  await expect(
    approval.getByRole("button", { name: "Approve this device" }),
  ).toBeDisabled();
  expect(await calls(page, "approve_pairing")).toEqual([]);
});
