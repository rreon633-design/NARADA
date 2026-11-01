// Source parity check, not a replacement for a native Tauri build/runtime test.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const root = new URL("../", import.meta.url);
const read = (name) => readFileSync(new URL(name, root), "utf8");
const expected = [
  "content_status",
  "list_conversations",
  "chat_history",
  "send_chat",
  "save_chat_draft",
  "mark_chat_read",
  "chat_control",
  "fetch_attachment",
  "pause_attachment",
  "export_attachment",
  "cleanup_content",
  "sync_query",
  "list_pins",
  "create_pin",
  "pin_control",
  "refresh_sync",
  "support_bundle",

  "get_snapshot",
  "save_settings",
  "run_diagnostics",
  "clear_activity",
  "configure_connections",
  "start_pairing",
  "cancel_pairing",
  "approve_pairing",
  "reject_pairing",
  "join_pairing",
  "cancel_join",
  "update_peer_permissions",
  "list_roots",
  "register_root",
  "update_root_policy",
  "remove_root",
  "reindex_root",
  "file_query",
  "file_mutation",
  "remote_file_query",
  "remote_file_mutation",
  "list_transfers",
  "start_transfer",
  "transfer_control",
  "create_transfer_offer",
  "poll_transfer_offers",
  "accept_transfer_offer",
  "cleanup_transfers",

  "revoke_peer",
  "reconnect_peer",
  "set_peer_endpoint",
  "discover_peers",
].sort();
const nativeExpected = [...expected, "choose_root"].sort();
const native = read("apps/desktop-shell/src-tauri/src/main.rs");
const manifest = read("apps/desktop-shell/src-tauri/build.rs");
const capabilities = JSON.parse(
  read("apps/desktop-shell/src-tauri/capabilities/main.json"),
);
const bridge = read("desktop-ui/src/services/bridge.js");
const commands = [
  ...native.matchAll(/#\[tauri::command\]\s+async fn (\w+)/g),
].map((m) => m[1]);
const registered =
  native.match(/generate_handler!\[([\s\S]*?)\]/)?.[1].match(/\b[a-z_]+\b/g) ||
  [];
const declared = [
  ...(manifest.match(/\.commands\(&\[([\s\S]*?)\]/)?.[1] || "").matchAll(
    /"([a-z_]+)"/g,
  ),
].map((m) => m[1]);
const permitted = capabilities.permissions
  .filter((p) => p !== "core:default")
  .map((p) => {
    assert.ok(
      p.startsWith("allow-"),
      "Only explicit app command permissions are allowed",
    );
    return p.slice(6).replaceAll("-", "_");
  });
const mappings = [
  ...bridge.matchAll(/rpc\(\s*\{\s*type:\s*"([^"]+)"[^}]*\},\s*"([^"]+)"/g),
];
for (const [, wire, invoke] of mappings)
  assert.equal(wire, invoke, "Native/IPC mapping diverged");
for (const [surface, values] of Object.entries({
  commands,
  registered,
  declared,
  permitted,
  bridge: mappings.map((m) => m[1]),
})) {
  assert.equal(
    new Set(values).size,
    values.length,
    `${surface} contains duplicates`,
  );
  assert.deepEqual(
    values.sort(),
    surface === "bridge" ? expected : nativeExpected,
    `${surface} is not the reviewed command surface`,
  );
}
assert.deepEqual(capabilities.windows, ["main"]);
assert.equal(
  capabilities.remote,
  undefined,
  "Remote documents must not inherit owner commands",
);
assert.ok(!expected.includes("shutdown"));
console.log(
  JSON.stringify(
    {
      status: "passed",
      commands: nativeExpected,
      surfaces: [
        "typed native wrappers",
        "invoke handler",
        "build manifest",
        "main-window capabilities",
        "frontend bridge",
      ],
      command_count: nativeExpected.length,
      note: "Static source parity only; native compilation, WebView and OS permission tests remain required.",
    },
    null,
    2,
  ),
);
