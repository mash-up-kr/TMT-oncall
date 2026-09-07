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

-- 스레드에 달린 힌트로 다시 답하려면 원 질문이 있어야 한다. 스레드를 다시 읽지 않고
-- 여기서 꺼내는 이유는, 사람이 오간 대화까지 되먹이면 톤과 맥락이 함께 흔들리기 때문이다.
CREATE TABLE IF NOT EXISTS question_thread (
    thread_id  TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    audience   TEXT NOT NULL,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- 버튼은 봇이 재시작한 뒤에도 눌린다. 그때 티켓·PR의 재료(요약·수정 계획·스택)를 메모리에서
-- 꺼낼 수 없으므로 리포트를 낼 때 함께 남긴다. 사람이 읽을 분석 전문은 스레드에 있으므로
-- 여기에는 다시 쓸 재료만 둔다.
CREATE TABLE IF NOT EXISTS incident_analysis (
    source_key    TEXT NOT NULL,
    external_id   TEXT NOT NULL,
    summary       TEXT NOT NULL,
    plan          TEXT NOT NULL,
    stack_excerpt TEXT,
    sentry_url    TEXT,
    occurred_at   TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    PRIMARY KEY (source_key, external_id)
);

-- 에러 리포트 스레드에 달린 사람의 메시지. '다시 분석' 버튼은 상호작용만 전달하고 본문을
-- 싣고 오지 않으므로, 힌트는 받은 시점에 여기 모아 두었다가 재분석 때 꺼내 쓴다.
CREATE TABLE IF NOT EXISTS incident_hint (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    thread_id  TEXT NOT NULL,
    author     TEXT,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_incident_hint_thread ON incident_hint (thread_id, id);
