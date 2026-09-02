-- 시각은 전부 UTC ISO-8601 문자열로 저장한다 (문자열 비교로 구간 조회가 되도록).

CREATE TABLE IF NOT EXISTS processed_incident (
    source_key   TEXT NOT NULL,
    external_id  TEXT NOT NULL,
    thread_id    TEXT,
    processed_at TEXT NOT NULL,
    PRIMARY KEY (source_key, external_id)
);

CREATE TABLE IF NOT EXISTS suppression (
    source_key    TEXT NOT NULL,
    external_id   TEXT NOT NULL,
    suppressed_by TEXT,
    created_at    TEXT NOT NULL,
    PRIMARY KEY (source_key, external_id)
);

CREATE TABLE IF NOT EXISTS poll_cursor (
    name       TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS call_log (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    path                  TEXT    NOT NULL,
    model                 TEXT    NOT NULL,
    input_tokens          INTEGER NOT NULL,
    output_tokens         INTEGER NOT NULL,
    cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
    cost_usd              REAL    NOT NULL,
    created_at            TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_call_log_created_at ON call_log (created_at);
