-- Public identities, authorization and connection metadata only. No private keys or invitation secrets.
CREATE TABLE local_identity (
 id INTEGER PRIMARY KEY CHECK (id=1), device_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
 created_at TEXT NOT NULL
);
CREATE TABLE connection_config (id INTEGER PRIMARY KEY CHECK(id=1), document TEXT NOT NULL);
INSERT INTO connection_config VALUES (1,'{"enabled":false,"bind_ip":"127.0.0.1","api_port":0,"discovery":false}');
CREATE TABLE trusted_peers (
 peer_id TEXT PRIMARY KEY, name TEXT NOT NULL, platform TEXT NOT NULL, certificate BLOB NOT NULL,
 fingerprint TEXT NOT NULL UNIQUE, state TEXT NOT NULL CHECK(state IN ('pending','active','revoked')),
 direction TEXT NOT NULL CHECK(direction IN ('inbound','outbound')), endpoint TEXT,
 permissions TEXT NOT NULL, remote_permissions TEXT NOT NULL, grant_revision INTEGER NOT NULL CHECK(grant_revision BETWEEN 1 AND 4294967295),
 session_epoch INTEGER NOT NULL CHECK(session_epoch BETWEEN 1 AND 4294967295),
 attempt_id TEXT NOT NULL, invitation_id TEXT NOT NULL, paired_at TEXT NOT NULL, last_seen TEXT
);
CREATE TABLE revoked_certificates (fingerprint TEXT PRIMARY KEY, revoked_at TEXT NOT NULL);
-- Phase 3 may register real roots here through local owner commands. Phase 2 exposes none.
CREATE TABLE authorization_scopes (root_id TEXT PRIMARY KEY, active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)));
CREATE INDEX trusted_peers_state ON trusted_peers(state);

CREATE TABLE cancelled_pairing_attempts (attempt_id TEXT PRIMARY KEY, cancelled_at TEXT NOT NULL);
-- Old Phase 1 diagnostics described a pre-identity runtime; require a fresh health check.
DELETE FROM diagnostics;
