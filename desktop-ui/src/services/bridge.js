export const isNative = Boolean(window.__TAURI_INTERNALS__);
export const mode = isNative
  ? "Native desktop"
  : import.meta.env.DEV
    ? "Development preview"
    : "Desktop runtime required";

async function rpc(command, nativeCommand, args = {}) {
  if (isNative) {
    const { invoke } = await import("@tauri-apps/api/core");
    try {
      return await invoke(nativeCommand, args);
    } catch (e) {
      throw Object.assign(new Error(e?.message || String(e)), {
        code: e?.code,
      });
    }
  }
  if (!import.meta.env.DEV)
    throw new Error(
      "Open the production build in the NARADA desktop application.",
    );
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  const requestId = crypto.randomUUID();
  try {
    const response = await fetch("/__narada/rpc", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: controller.signal,
      body: JSON.stringify({ version: 1, id: requestId, command }),
    });
    let result;
    try {
      result = await response.json();
    } catch {
      throw new Error(
        "The local agent is starting or unavailable. Try again in a moment.",
      );
    }
    if (!response.ok || !result.ok)
      throw Object.assign(
        new Error(
          result.error?.message ||
            "The local agent could not complete this request.",
        ),
        { code: result.error?.code },
      );
    if (
      result.version !== 1 ||
      result.id !== requestId ||
      result.data === undefined
    )
      throw new Error("The local agent returned an incompatible response.");
    return result.data;
  } catch (e) {
    if (e.name === "AbortError")
      throw new Error("The local agent did not respond in time. Please retry.");
    if (e instanceof TypeError)
      throw new Error(
        "The local agent is unavailable. Your saved workspace has not been reset.",
      );
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
export const agent = {
  snapshot: () => rpc({ type: "get_snapshot" }, "get_snapshot"),
  saveSettings: (settings) =>
    rpc({ type: "save_settings", payload: settings }, "save_settings", {
      settings,
    }),
  diagnostics: () => rpc({ type: "run_diagnostics" }, "run_diagnostics"),
  clearActivity: () => rpc({ type: "clear_activity" }, "clear_activity"),
  configure_connections: (payload) =>
    rpc({ type: "configure_connections", payload }, "configure_connections", {
      payload,
    }),
  cancel_pairing: (payload) =>
    rpc({ type: "cancel_pairing", payload }, "cancel_pairing", { payload }),
  approve_pairing: (payload) =>
    rpc({ type: "approve_pairing", payload }, "approve_pairing", { payload }),
  reject_pairing: (payload) =>
    rpc({ type: "reject_pairing", payload }, "reject_pairing", { payload }),
  join_pairing: (payload) =>
    rpc({ type: "join_pairing", payload }, "join_pairing", { payload }),
  cancel_join: (payload) =>
    rpc({ type: "cancel_join", payload }, "cancel_join", { payload }),
  update_peer_permissions: (payload) =>
    rpc(
      { type: "update_peer_permissions", payload },
      "update_peer_permissions",
      { payload },
    ),
  revoke_peer: (payload) =>
    rpc({ type: "revoke_peer", payload }, "revoke_peer", { payload }),
  reconnect_peer: (payload) =>
    rpc({ type: "reconnect_peer", payload }, "reconnect_peer", { payload }),
  set_peer_endpoint: (payload) =>
    rpc({ type: "set_peer_endpoint", payload }, "set_peer_endpoint", {
      payload,
    }),
  start_pairing: () => rpc({ type: "start_pairing" }, "start_pairing"),
  discover_peers: () => rpc({ type: "discover_peers" }, "discover_peers"),
  content_status: () => rpc({ type: "content_status" }, "content_status"),
  list_conversations: () =>
    rpc({ type: "list_conversations" }, "list_conversations"),
  chat_history: (payload) =>
    rpc({ type: "chat_history", payload }, "chat_history", { payload }),
  send_chat: (payload) =>
    rpc({ type: "send_chat", payload }, "send_chat", { payload }),
  save_chat_draft: (payload) =>
    rpc({ type: "save_chat_draft", payload }, "save_chat_draft", { payload }),
  mark_chat_read: (payload) =>
    rpc({ type: "mark_chat_read", payload }, "mark_chat_read", { payload }),
  chat_control: (payload) =>
    rpc({ type: "chat_control", payload }, "chat_control", { payload }),
  fetch_attachment: (payload) =>
    rpc({ type: "fetch_attachment", payload }, "fetch_attachment", { payload }),
  pause_attachment: (payload) =>
    rpc({ type: "pause_attachment", payload }, "pause_attachment", { payload }),
  export_attachment: (payload) =>
    rpc({ type: "export_attachment", payload }, "export_attachment", {
      payload,
    }),
  cleanup_content: () => rpc({ type: "cleanup_content" }, "cleanup_content"),
  sync_query: (payload) =>
    rpc({ type: "sync_query", payload }, "sync_query", { payload }),
  list_pins: () => rpc({ type: "list_pins" }, "list_pins"),
  create_pin: (payload) =>
    rpc({ type: "create_pin", payload }, "create_pin", { payload }),
  pin_control: (payload) =>
    rpc({ type: "pin_control", payload }, "pin_control", { payload }),
  refresh_sync: () => rpc({ type: "refresh_sync" }, "refresh_sync"),
  support_bundle: () => rpc({ type: "support_bundle" }, "support_bundle"),
  list_transfers: () => rpc({ type: "list_transfers" }, "list_transfers"),
  start_transfer: (payload) =>
    rpc({ type: "start_transfer", payload }, "start_transfer", { payload }),
  transfer_control: (payload) =>
    rpc({ type: "transfer_control", payload }, "transfer_control", { payload }),
  create_transfer_offer: (payload) =>
    rpc({ type: "create_transfer_offer", payload }, "create_transfer_offer", {
      payload,
    }),
  poll_transfer_offers: () =>
    rpc({ type: "poll_transfer_offers" }, "poll_transfer_offers"),
  accept_transfer_offer: (payload) =>
    rpc({ type: "accept_transfer_offer", payload }, "accept_transfer_offer", {
      payload,
    }),
  cleanup_transfers: () =>
    rpc({ type: "cleanup_transfers" }, "cleanup_transfers"),
  list_roots: () => rpc({ type: "list_roots" }, "list_roots"),
  register_root: (payload) =>
    rpc({ type: "register_root", payload }, "register_root", { payload }),
  update_root_policy: (payload) =>
    rpc({ type: "update_root_policy", payload }, "update_root_policy", {
      payload,
    }),
  remove_root: (payload) =>
    rpc({ type: "remove_root", payload }, "remove_root", { payload }),
  reindex_root: (payload) =>
    rpc({ type: "reindex_root", payload }, "reindex_root", { payload }),
  file_query: (payload) =>
    rpc({ type: "file_query", payload }, "file_query", { payload }),
  file_mutation: (payload) =>
    rpc({ type: "file_mutation", payload }, "file_mutation", { payload }),
  remote_file_query: (payload) =>
    rpc({ type: "remote_file_query", payload }, "remote_file_query", {
      payload,
    }),
  remote_file_mutation: (payload) =>
    rpc({ type: "remote_file_mutation", payload }, "remote_file_mutation", {
      payload,
    }),
};

export function exportDiagnostics(snapshot) {
  if (!snapshot?.last_diagnostics)
    throw new Error("Run a health check before exporting diagnostics.");
  // Intentionally omit workspace name, host paths, activity and identifiers.
  const data = {
    application: "NARADA",
    version: snapshot.agent.version,
    phase: 7,
    mode,
    platform: snapshot.agent.platform,
    peer_network_enabled: snapshot.agent.peer_network_enabled,
    storage: snapshot.storage,
    diagnostics: snapshot.last_diagnostics,
    redaction:
      "No workspace or peer names, addresses, identity fingerprints, pairing secrets, paths, keys, file content, or message content included.",
  };
  const blob = new Blob([JSON.stringify(data, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "NARADA-diagnostics.json";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function chooseRoot() {
  if (!isNative)
    throw new Error(
      "Use an explicit agent-host folder path in the browser development preview.",
    );
  const { invoke } = await import("@tauri-apps/api/core");
  return invoke("choose_root");
}
