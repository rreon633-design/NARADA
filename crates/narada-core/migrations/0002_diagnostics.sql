CREATE TABLE diagnostics (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    document TEXT NOT NULL CHECK (json_valid(document)),
    checked_at TEXT NOT NULL
);
