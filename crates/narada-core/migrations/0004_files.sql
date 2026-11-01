-- Local operational catalog. Paths/names are sensitive metadata, not encrypted content.
CREATE TABLE file_roots (
 root_id TEXT PRIMARY KEY REFERENCES authorization_scopes(root_id), path TEXT NOT NULL,
 identity TEXT NOT NULL, label TEXT NOT NULL, exclusions TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1,
 revision INTEGER NOT NULL DEFAULT 1, epoch INTEGER NOT NULL DEFAULT 1, signature TEXT NOT NULL DEFAULT '',
 state TEXT NOT NULL DEFAULT 'indexing', error_code TEXT, indexed_at TEXT, omitted INTEGER NOT NULL DEFAULT 0,
 trash_identity TEXT, created_at TEXT NOT NULL
);
CREATE UNIQUE INDEX file_roots_active_path ON file_roots(path) WHERE active=1;
CREATE TABLE file_entries (
 object_id TEXT PRIMARY KEY, root_id TEXT NOT NULL REFERENCES file_roots(root_id),
 relative_path TEXT NOT NULL, parent_path TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
 identity TEXT NOT NULL, version TEXT NOT NULL, size INTEGER NOT NULL, modified TEXT NOT NULL,
 UNIQUE(root_id,relative_path)
);
CREATE INDEX file_entries_parent ON file_entries(root_id,parent_path);
CREATE TABLE file_trash (
 trash_id TEXT PRIMARY KEY, root_id TEXT NOT NULL REFERENCES file_roots(root_id), object_id TEXT NOT NULL,
 original_path TEXT NOT NULL, stored_path TEXT NOT NULL, identity TEXT NOT NULL, version TEXT NOT NULL,
 kind TEXT NOT NULL, size INTEGER NOT NULL, trashed_at TEXT NOT NULL, retain_until TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'retained'
);
CREATE TABLE file_operations (
 actor TEXT NOT NULL, operation_id TEXT NOT NULL, root_id TEXT NOT NULL REFERENCES file_roots(root_id),
 request_hash TEXT NOT NULL, action TEXT NOT NULL, source_path TEXT NOT NULL, destination_path TEXT NOT NULL,
 identity TEXT NOT NULL, object_id TEXT NOT NULL, expected_version TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('prepared','committed','failed','recovery_required')),
 result TEXT, error_code TEXT, created_at TEXT NOT NULL,
 PRIMARY KEY(actor,operation_id)
);
CREATE INDEX file_operations_root_state ON file_operations(root_id,state);
-- Phase 2 checks did not describe registered file content. Require a fresh diagnostic run.
DELETE FROM diagnostics;
