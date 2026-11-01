import test from "node:test";
import assert from "node:assert/strict";
import {
  messageBytes,
  validMessage,
  messageState,
  attachmentState,
  cipherProgress,
  canCompose,
  retainedBytes,
  contentHint,
  pinState,
  selectChatAttachment,
  chatAttachment,
  clearChatAttachment,
} from "../src/lib/content.js";
import { grantForRole } from "../src/lib/files.js";
test("chat enforces UTF-8 bytes rather than misleading character counts", () => {
  assert.equal(messageBytes("é"), 2);
  assert.equal(validMessage("é".repeat(2048)), true);
  assert.equal(validMessage("é".repeat(2049)), false);
  assert.equal(validMessage("తెలుగు"), true);
});
test("empty messages need a selected attachment; display controls are bounded", () => {
  assert.equal(validMessage("  "), false);
  assert.equal(validMessage("", true), true);
  assert.equal(validMessage("a\n\tb"), true);
  for (const c of ["\0", "\r", "\x1b", "\x7f", "\u0080"])
    assert.equal(validMessage(`text${c}`), false);
});
test("only a committed delivery state is labelled Delivered", () => {
  assert.equal(messageState({ state: "delivered" }), "Delivered");
  for (const state of [
    "pending",
    "retry_wait",
    "cancelled",
    "action_required",
    "unknown",
    "sent",
    "sending",
  ])
    assert.notEqual(messageState({ state }), "Delivered");
});
test("message delivery never implies attachment availability", () => {
  assert.equal(attachmentState({ state: "not_requested" }), "Not downloaded");
  assert.match(attachmentState({ state: "available" }), /Authenticated/);
  assert.equal(attachmentState({ state: "delivered" }), "Unavailable");
});
test("ciphertext progress rejects impossible or unsafe counters", () => {
  assert.equal(
    cipherProgress({ cipher_size: "1024", verified_bytes: "256" }),
    25,
  );
  for (const a of [
    {},
    { cipher_size: "0", verified_bytes: "0" },
    { cipher_size: "1", verified_bytes: "2" },
    { cipher_size: "10", verified_bytes: "-1" },
    { cipher_size: "9007199254740992", verified_bytes: "0" },
  ])
    assert.equal(cipherProgress(a), null);
});
test("compose needs a real active consent, protected store, and local agent", () => {
  const p = { state: "active", permissions: { chat: true } };
  assert.equal(canCompose(p, true, true), true);
  assert.equal(canCompose(p, false, true), false);
  assert.equal(canCompose(p, true, false), false);
  assert.equal(canCompose({ ...p, state: "revoked" }, true, true), false);
  assert.equal(
    canCompose({ state: "active", permissions: {} }, true, true),
    false,
  );
});
test("offline pin grants add read scope without mutation permission", () => {
  assert.deepEqual(grantForRole("root", "offline").capabilities, [
    "browse",
    "read",
    "sync_read",
  ]);
  assert.ok(!grantForRole("root", "manage").capabilities.includes("sync_read"));
});
test("recorded publication bytes are not guessed from pending progress", () => {
  assert.equal(
    retainedBytes([
      {
        progress: { verified_bytes: "9999" },
        versions: [{ size: "3" }, { size: "4" }],
      },
    ]),
    7,
  );
  assert.equal(
    retainedBytes([{ versions: [{ size: "NaN" }, { size: "-2" }] }]),
    0,
  );
});
test("source removal says the local copy is kept, not deleted", () => {
  assert.match(pinState({ state: "source_deleted" }), /copy kept/);
  assert.match(contentHint("NAME_COLLISION"), /Nothing was overwritten/);
  assert.match(contentHint("CHAT_KEY_CHANGED"), /blocked/);
});
test("attachment selection is ephemeral memory and can be removed", () => {
  clearChatAttachment();
  assert.equal(chatAttachment(), null);
  const a = { name: "inert.bin", source: { object_id: "fixture" } };
  selectChatAttachment(a);
  assert.equal(chatAttachment(), a);
  clearChatAttachment();
  assert.equal(chatAttachment(), null);
});
