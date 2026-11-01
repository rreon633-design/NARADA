CREATE TABLE settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    document TEXT NOT NULL CHECK (json_valid(document)),
    updated_at TEXT NOT NULL
);
CREATE TABLE activity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX activity_recent ON activity(id DESC);
