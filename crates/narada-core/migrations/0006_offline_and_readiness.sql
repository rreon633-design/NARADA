-- Sensitive messaging state is in content.db as authenticated ciphertext, never
-- in this metadata database. These tables contain ordinary file metadata only.
ALTER TABLE file_roots ADD COLUMN sync_floor INTEGER NOT NULL DEFAULT 0;
ALTER TABLE transfer_sources ADD COLUMN sync_required INTEGER NOT NULL DEFAULT 0;
CREATE TABLE file_journal (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT,root_id TEXT NOT NULL REFERENCES file_roots(root_id),
 kind TEXT NOT NULL,object_id TEXT NOT NULL,entry TEXT,created_at INTEGER NOT NULL
);
CREATE INDEX file_journal_scope ON file_journal(root_id,sequence);
CREATE TABLE sync_cache_roots (
 peer_id TEXT NOT NULL,root_id TEXT NOT NULL,cursor TEXT,updated_at INTEGER NOT NULL,
 PRIMARY KEY(peer_id,root_id)
);
CREATE TABLE sync_cache_entries (
 peer_id TEXT NOT NULL,root_id TEXT NOT NULL,object_id TEXT NOT NULL,entry TEXT NOT NULL,
 PRIMARY KEY(peer_id,root_id,object_id)
);
CREATE TABLE pins (
 pin_id TEXT PRIMARY KEY,peer_id TEXT NOT NULL,request_hash TEXT NOT NULL,
 source TEXT NOT NULL,destination TEXT NOT NULL,state TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,
 pending_transfer_id TEXT,pending_request TEXT,current_version TEXT,current_receipt TEXT,
 reservation INTEGER NOT NULL DEFAULT 0,error_code TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL
);
CREATE TABLE pin_versions (
 pin_id TEXT NOT NULL REFERENCES pins(pin_id),ordinal INTEGER NOT NULL,version TEXT NOT NULL,
 receipt TEXT NOT NULL,size INTEGER NOT NULL,PRIMARY KEY(pin_id,ordinal)
);
DELETE FROM diagnostics;
