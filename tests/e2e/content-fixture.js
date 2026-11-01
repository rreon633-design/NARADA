import { expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createConnection } from "node:net";
import fs from "node:fs/promises";
import path from "node:path";
export async function rpc(request, type, payload) {
  const value = await (
    await request.post("/__narada/rpc", {
      data: {
        version: 1,
        id: randomUUID(),
        command: payload === undefined ? { type } : { type, payload },
      },
    })
  ).json();
  expect(value.ok, JSON.stringify(value.error)).toBe(true);
  return value.data;
}
function ipc(dir, type, payload) {
  return new Promise((resolve, reject) => {
    const socket = createConnection(path.join(dir, "agent.sock")),
      id = randomUUID();
    let bytes = "";
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
    socket.on("data", (data) => {
      bytes += data;
      if (bytes.endsWith("\n")) {
        clearTimeout(timer);
        socket.end();
        const result = JSON.parse(bytes);
        result.ok ? resolve(result.data) : reject(Error(result.error.code));
      }
    });
    socket.on("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });
}
export async function resetContent(request) {
  for (const p of (await rpc(request, "list_pins")).pins)
    await rpc(request, "pin_control", { pin_id: p.pin_id, action: "unpin" });
  for (const j of (await rpc(request, "list_transfers")).jobs)
    if (!["completed", "cancelled"].includes(j.state))
      await rpc(request, "transfer_control", {
        transfer_id: j.transfer_id,
        target: "job",
        action: "cancel",
        name: null,
      });
  const s = await rpc(request, "get_snapshot");
  if (s.connections.invitation)
    await rpc(request, "cancel_pairing", {
      invitation_id: s.connections.invitation.invitation_id,
    });
  for (const p of s.connections.peers.filter((p) => p.state !== "revoked"))
    await rpc(request, "revoke_peer", {
      peer_id: p.peer_id,
      expected_revision: p.grant_revision,
    });
  for (const r of (await rpc(request, "list_roots")).roots)
    await rpc(request, "remove_root", {
      root_id: r.root_id,
      expected_revision: r.revision,
    });
  await rpc(request, "configure_connections", {
    enabled: false,
    bind_ip: "127.0.0.1",
    api_port: 0,
    discovery: false,
  });
}
export async function fixture(request, consent = true) {
  await resetContent(request);
  await fs.mkdir("test-fixtures", { recursive: true });
  const base = await fs.mkdtemp(path.resolve("test-fixtures/browser-content-"));
  const local = path.join(base, "local"),
    remote = path.join(base, "remote"),
    dir = path.join(base, "reference");
  await fs.mkdir(local);
  await fs.mkdir(remote);
  const payload = Buffer.from(
    "SYNTHETIC_BROWSER_ATTACHMENT_61723".repeat(12000),
  );
  await fs.writeFile(path.join(remote, "release-notes.txt"), payload);
  await fs.writeFile(
    path.join(local, "local-note.txt"),
    "A local file selected explicitly.",
  );
  const process = spawn(
    path.resolve("target/debug/narada-agent"),
    ["--data-dir", dir],
    {
      env: { ...globalThis.process.env, NARADA_DEV_VAULT_KEY: "39".repeat(32) },
      stdio: "ignore",
    },
  );
  const ref = (type, body) => ipc(dir, type, body);
  const value = {
    base,
    local,
    remote,
    process,
    ref,
    payload,
    close: async () => {
      try {
        await resetContent(request);
      } finally {
        try {
          if (process.exitCode === null && process.signalCode === null)
            await new Promise((done) => {
              const deadline = setTimeout(() => process.kill("SIGKILL"), 5000);
              process.once("exit", () => {
                clearTimeout(deadline);
                done();
              });
              process.kill("SIGTERM");
            });
        } finally {
          await fs.rm(base, { recursive: true, force: true });
        }
      }
    },
  };
  try {
    await expect
      .poll(async () => {
        try {
          return (await ref("get_snapshot")).connections.identity.state;
        } catch {
          return null;
        }
      })
      .toBe("ready");
    const settings = (await ref("get_snapshot")).settings;
    await ref("save_settings", {
      ...settings,
      device_name: "Reference desktop",
      setup_completed: true,
    });
    value.localRoot = (
      await rpc(request, "register_root", {
        path: local,
        label: "Local offline shelf",
        exclusions: [],
      })
    ).root_id;
    value.remoteRoot = (
      await ref("register_root", {
        path: remote,
        label: "Reference documents",
        exclusions: [],
      })
    ).root_id;
    for (const call of [ref, (t, b) => rpc(request, t, b)])
      await call("configure_connections", {
        enabled: true,
        bind_ip: "127.0.0.1",
        api_port: 0,
        discovery: false,
      });
    await rpc(request, "join_pairing", {
      code: (await ref("start_pairing")).code,
    });
    await expect
      .poll(
        async () =>
          (await ref("get_snapshot")).connections.invitation?.pending
            ?.proof_verified,
      )
      .toBe(true);
    await ref("approve_pairing", {
      attempt_id: (await ref("get_snapshot")).connections.invitation.pending
        .attempt_id,
      permissions: {
        chat: true,
        attachment: true,
        roots: [
          {
            root_id: value.remoteRoot,
            capabilities: ["browse", "read", "sync_read"],
          },
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
    let peer = (await rpc(request, "get_snapshot")).connections.peers.find(
      (p) => p.state === "active",
    );
    value.peer = peer.peer_id;
    value.reverse = (await ref("get_snapshot")).connections.peers.find(
      (p) => p.state === "active",
    ).peer_id;
    if (consent)
      await rpc(request, "update_peer_permissions", {
        peer_id: peer.peer_id,
        expected_revision: peer.grant_revision,
        permissions: { chat: true, attachment: true, roots: [] },
      });
    value.source = async () => {
      await ref("reindex_root", { root_id: value.remoteRoot });
      const e = (
        await ref("file_query", {
          kind: "browse",
          root_id: value.remoteRoot,
          directory_id: null,
          cursor: null,
          sort: "name",
        })
      ).entries.find((e) => e.name === "release-notes.txt");
      return {
        root_id: value.remoteRoot,
        object_id: e.object_id,
        expected_version: e.version,
      };
    };
    return value;
  } catch (e) {
    await value.close().catch(() => {});
    throw e;
  }
}
