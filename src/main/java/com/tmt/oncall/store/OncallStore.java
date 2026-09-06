package com.tmt.oncall.store;

import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 재시작 후에도 같은 건을 다시 분석하거나 중복 PR을 만들지 않도록
 * 처리 이력·억제 목록·폴링 커서·호출 기록을 SQLite에 남긴다.
 */
@Component
public class OncallStore {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private final JdbcClient jdbc;

    public OncallStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- 처리 이력 ---

    public Optional<Instant> lastProcessedAt(String sourceKey, String externalId) {
        return jdbc.sql("SELECT processed_at FROM processed_incident WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .query(String.class)
                .optional()
                .map(Instant::parse);
    }

    public void markProcessed(String sourceKey, String externalId, String threadId) {
        jdbc.sql("""
                        INSERT INTO processed_incident (source_key, external_id, thread_id, processed_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (source_key, external_id)
                        DO UPDATE SET thread_id = excluded.thread_id, processed_at = excluded.processed_at
                        """)
                .params(sourceKey, externalId, threadId, now())
                .update();
    }

    public Optional<String> threadIdOf(String sourceKey, String externalId) {
        return jdbc.sql("SELECT thread_id FROM processed_incident WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .query(String.class)
                .optional();
    }

    /** 봇이 리포트에 연 스레드인지 본다 — 사람이 잡담하려고 연 스레드와 갈라야 한다. */
    public boolean hasThread(String threadId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM processed_incident WHERE thread_id = ?")
                .param(threadId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    // --- 억제 목록 ---

    public boolean isSuppressed(String sourceKey, String externalId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM suppression WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public void suppress(String sourceKey, String externalId, String suppressedBy) {
        jdbc.sql("""
                        INSERT INTO suppression (source_key, external_id, suppressed_by, created_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (source_key, external_id)
                        DO UPDATE SET suppressed_by = excluded.suppressed_by, created_at = excluded.created_at
                        """)
                .params(sourceKey, externalId, suppressedBy, now())
                .update();
    }

    /** @return 실제로 억제 목록에 있어서 해제된 경우에만 true */
    public boolean unsuppress(String sourceKey, String externalId) {
        int affected = jdbc.sql("DELETE FROM suppression WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .update();
        return affected > 0;
    }

    // --- 폴링 커서 ---

    public Optional<String> cursor(String name) {
        return jdbc.sql("SELECT value FROM poll_cursor WHERE name = ?")
                .param(name)
                .query(String.class)
                .optional();
    }

    public void saveCursor(String name, String value) {
        jdbc.sql("""
                        INSERT INTO poll_cursor (name, value, updated_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (name)
                        DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                        """)
                .params(name, value, now())
                .update();
    }

    // --- 호출 기록 ---

    public void recordCall(CallPath path, String model, Usage usage, double costUsd) {
        jdbc.sql("""
                        INSERT INTO call_log (
                            path, model, input_tokens, output_tokens,
                            cache_creation_tokens, cache_read_tokens, cost_usd, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(path.name(), model, usage.inputTokens(), usage.outputTokens(),
                        usage.cacheCreationTokens(), usage.cacheReadTokens(), costUsd, now())
                .update();
    }

    public int countCallsSince(Instant since) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM call_log WHERE created_at >= ?")
                .param(TIMESTAMP.format(since))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public double costSince(Instant since) {
        Double cost = jdbc.sql("SELECT COALESCE(SUM(cost_usd), 0) FROM call_log WHERE created_at >= ?")
                .param(TIMESTAMP.format(since))
                .query(Double.class)
                .single();
        return cost == null ? 0d : cost;
    }

    private static String now() {
        return TIMESTAMP.format(Instant.now());
    }
}
