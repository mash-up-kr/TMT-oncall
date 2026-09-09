package com.tmt.oncall.store;

import com.tmt.oncall.core.Audience;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /**
     * 분석에 넘기기 전에 시도를 센다. 스레드 ID는 건드리지 않는다 — 재시도로 들어와도
     * 첫 리포트가 연 스레드를 잃으면 후속 보고가 채널로 흩어진다.
     *
     * @return 이번이 몇 번째 시도인지
     */
    public int beginAttempt(String sourceKey, String externalId) {
        jdbc.sql("""
                        INSERT INTO processed_incident (source_key, external_id, processed_at, attempts)
                        VALUES (?, ?, ?, 1)
                        ON CONFLICT (source_key, external_id)
                        DO UPDATE SET processed_at = excluded.processed_at, attempts = attempts + 1
                        """)
                .params(sourceKey, externalId, now())
                .update();
        return attemptsOf(sourceKey, externalId);
    }

    public int attemptsOf(String sourceKey, String externalId) {
        return jdbc.sql("SELECT attempts FROM processed_incident WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** 1차 분류의 답. 남겨 두지 않으면 중복 창이 지날 때마다 같은 건을 다시 분류한다. */
    public void saveTriageDecision(String sourceKey, String externalId, boolean actionNeeded) {
        jdbc.sql("""
                        INSERT INTO processed_incident (source_key, external_id, processed_at, attempts, triage_passed)
                        VALUES (?, ?, ?, 1, ?)
                        ON CONFLICT (source_key, external_id)
                        DO UPDATE SET triage_passed = excluded.triage_passed
                        """)
                .params(sourceKey, externalId, now(), actionNeeded ? 1 : 0)
                .update();
    }

    /** @return 아직 분류하지 않았으면 비어 있다 */
    public Optional<Boolean> triageDecision(String sourceKey, String externalId) {
        return jdbc
                .sql("SELECT triage_passed FROM processed_incident WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .query(Integer.class)
                .optional()
                .map(passed -> passed != 0);
    }

    /**
     * 넘겼는데 리포트로 끝나지 않은 건. 폴링 목록(미해결 최근 N건)만 훑으면 밀려난 이슈를
     * 놓치는데, 하필 재시도가 가장 필요한 폭주 상황에서 밀려나기 쉬워 이력에서 직접 꺼낸다.
     *
     * <p>
     * 1차 분류에서 걸러낸 건(triage_passed = 0)은 실패가 아니므로 제외한다.
     *
     * @param idleSince 마지막 시도가 이보다 오래된 것만. 분석이 아직 도는 중인 건을 다시
     *                  넘기면 같은 건을 두 번 분석하고 리포트도 두 번 나간다
     */
    public List<String> retryableIncidents(String sourceKey, int maxAttempts, Instant idleSince) {
        return jdbc.sql("""
                        SELECT external_id FROM processed_incident
                        WHERE source_key = ? AND thread_id IS NULL
                          AND (triage_passed IS NULL OR triage_passed = 1)
                          AND attempts BETWEEN 1 AND ?
                          AND processed_at < ?
                        ORDER BY processed_at
                        """)
                .params(sourceKey, maxAttempts - 1, TIMESTAMP.format(idleSince))
                .query(String.class)
                .list();
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

    // --- 질문 스레드 ---

    public void saveQuestionThread(QuestionThread question) {
        jdbc.sql("""
                        INSERT INTO question_thread (thread_id, message_id, audience, content, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (thread_id)
                        DO UPDATE SET message_id = excluded.message_id, audience = excluded.audience,
                                      content = excluded.content, created_at = excluded.created_at
                        """)
                .params(question.threadId(), question.messageId(), question.audience().name(),
                        question.content(), now())
                .update();
    }

    /** @return 봇이 질문에 답하며 연 스레드가 아니면 비어 있다 — 에러 리포트 스레드가 여기 걸린다 */
    public Optional<QuestionThread> questionThread(String threadId) {
        return jdbc.sql("SELECT message_id, audience, content FROM question_thread WHERE thread_id = ?")
                .param(threadId)
                .query((rs, rowNum) -> new QuestionThread(threadId, rs.getString("message_id"),
                        Audience.valueOf(rs.getString("audience")), rs.getString("content")))
                .optional();
    }

    // --- 분석 결과 ---

    public void saveAnalysis(IncidentAnalysis analysis) {
        jdbc.sql("""
                        INSERT INTO incident_analysis (
                            source_key, external_id, summary, plan,
                            stack_excerpt, sentry_url, occurred_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (source_key, external_id)
                        DO UPDATE SET summary = excluded.summary, plan = excluded.plan,
                                      stack_excerpt = excluded.stack_excerpt,
                                      sentry_url = excluded.sentry_url,
                                      occurred_at = excluded.occurred_at,
                                      created_at = excluded.created_at
                        """)
                .params(analysis.sourceKey(), analysis.externalId(), analysis.summary(), analysis.plan(),
                        analysis.stackExcerpt(), analysis.sentryIssueUrl(),
                        TIMESTAMP.format(analysis.occurredAt()), now())
                .update();
    }

    /** @return 리포트를 낸 적이 없거나 이 버전 이전에 낸 건이면 비어 있다 */
    public Optional<IncidentAnalysis> analysisOf(String sourceKey, String externalId) {
        return jdbc.sql("""
                        SELECT summary, plan, stack_excerpt, sentry_url, occurred_at
                        FROM incident_analysis WHERE source_key = ? AND external_id = ?
                        """)
                .params(sourceKey, externalId)
                .query((rs, rowNum) -> new IncidentAnalysis(sourceKey, externalId,
                        rs.getString("summary"), rs.getString("plan"), rs.getString("stack_excerpt"),
                        rs.getString("sentry_url"), Instant.parse(rs.getString("occurred_at"))))
                .optional();
    }

    // --- 티켓 ---

    public void saveTicketKey(String sourceKey, String externalId, String ticketKey) {
        jdbc.sql("""
                        INSERT INTO incident_ticket (source_key, external_id, ticket_key, created_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (source_key, external_id)
                        DO UPDATE SET ticket_key = excluded.ticket_key
                        """)
                .params(sourceKey, externalId, ticketKey, now())
                .update();
    }

    /** @return 아직 티켓을 만들지 않았으면 비어 있다 */
    public Optional<String> ticketKeyOf(String sourceKey, String externalId) {
        return jdbc.sql("SELECT ticket_key FROM incident_ticket WHERE source_key = ? AND external_id = ?")
                .params(sourceKey, externalId)
                .query(String.class)
                .optional();
    }

    // --- 재분석 힌트 ---

    public void saveThreadHint(String threadId, String author, String content) {
        jdbc.sql("INSERT INTO incident_hint (thread_id, author, content, created_at) VALUES (?, ?, ?, ?)")
                .params(threadId, author, content, now())
                .update();
    }

    /** @return 최근 것부터 {@code limit}개. 스레드가 길어져도 재분석 입력이 무한정 늘지 않게 한다 */
    public List<String> threadHints(String threadId, int limit) {
        List<String> newestFirst = jdbc.sql("""
                        SELECT author, content FROM incident_hint
                        WHERE thread_id = ? ORDER BY id DESC LIMIT ?
                        """)
                .params(threadId, limit)
                .query((rs, rowNum) -> rs.getString("author") + ": " + rs.getString("content"))
                .list();
        return newestFirst.reversed();
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
