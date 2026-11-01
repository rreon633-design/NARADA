-- Sensitive operational metadata only. Staged ordinary file bytes are outside SQLite.
ALTER TABLE file_roots ADD COLUMN transfer_identity TEXT;
CREATE TABLE transfer_storage (id INTEGER PRIMARY KEY CHECK(id=1), identity TEXT NOT NULL);
CREATE TABLE transfer_sources (
 transfer_id TEXT PRIMARY KEY,actor TEXT NOT NULL,root_id TEXT NOT NULL REFERENCES file_roots(root_id),
 object_id TEXT NOT NULL,relative_path TEXT NOT NULL,source_identity TEXT NOT NULL,source_version TEXT NOT NULL,
 name TEXT NOT NULL,size INTEGER NOT NULL,request_hash TEXT NOT NULL,manifest TEXT,hashes TEXT,
 snapshot_identity TEXT,state TEXT NOT NULL,error_code TEXT,offered INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL
);
CREATE INDEX transfer_sources_actor ON transfer_sources(actor,state);
CREATE TABLE transfer_receives (
 transfer_id TEXT PRIMARY KEY,actor TEXT NOT NULL,root_id TEXT NOT NULL REFERENCES file_roots(root_id),
 parent_path TEXT NOT NULL,parent_identity TEXT NOT NULL,name TEXT NOT NULL,manifest TEXT NOT NULL,
 hashes TEXT NOT NULL,bitmap TEXT NOT NULL,request_hash TEXT NOT NULL,stage_identity TEXT,
 state TEXT NOT NULL,error_code TEXT,verified_bytes INTEGER NOT NULL DEFAULT 0,result TEXT,
 intent_identity TEXT,completed_at INTEGER,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL
);
CREATE INDEX transfer_receives_actor ON transfer_receives(actor,state);
CREATE TABLE transfer_jobs (
 transfer_id TEXT PRIMARY KEY,peer_id TEXT NOT NULL,direction TEXT NOT NULL,request TEXT NOT NULL,
 request_hash TEXT NOT NULL,name TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'queued',
 phase TEXT NOT NULL DEFAULT 'queued',size INTEGER NOT NULL DEFAULT 0,verified_bytes INTEGER NOT NULL DEFAULT 0,
 manifest TEXT,error_code TEXT,result TEXT,attempts INTEGER NOT NULL DEFAULT 0,
 ack_pending INTEGER NOT NULL DEFAULT 0,from_offer INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL
);
CREATE TABLE transfer_remote_offers (
 peer_id TEXT NOT NULL,transfer_id TEXT NOT NULL,manifest TEXT NOT NULL,source TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'offered',seen_at INTEGER NOT NULL,PRIMARY KEY(peer_id,transfer_id)
);
-- Force a current report for the changed content surface, retaining all other history.
DELETE FROM diagnostics;
