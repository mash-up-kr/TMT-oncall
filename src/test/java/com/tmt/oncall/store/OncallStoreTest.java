package com.tmt.oncall.store;

import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import com.tmt.oncall.support.TestStore;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OncallStoreTest {

    private static final String SOURCE = "sentry:tmt-be";

    OncallStore store;

    @BeforeEach
    void setUp() {
        store = TestStore.create().store();
    }

    @Test
    void 같은_건을_다시_처리하면_덮어쓴다() {
        String issueId = newId();
        store.markProcessed(SOURCE, issueId, "thread-1");
        Instant first = store.lastProcessedAt(SOURCE, issueId).orElseThrow();

        store.markProcessed(SOURCE, issueId, "thread-2");

        assertThat(store.threadIdOf(SOURCE, issueId)).contains("thread-2");
        assertThat(store.lastProcessedAt(SOURCE, issueId).orElseThrow()).isAfterOrEqualTo(first);
    }

    @Test
    void 억제한_건을_해제하면_다시_알림_대상이_된다() {
        String issueId = newId();
        store.suppress(SOURCE, issueId, "minseo");
        assertThat(store.isSuppressed(SOURCE, issueId)).isTrue();

        store.unsuppress(SOURCE, issueId);

        assertThat(store.isSuppressed(SOURCE, issueId)).isFalse();
    }

    /** {@code /oncall unmute}가 "해제했습니다"와 "그건 억제 중이 아닙니다"를 구분해 답하는 근거. */
    @Test
    void 억제_중이_아닌_건을_해제하면_해제할_게_없었다고_답한다() {
        String issueId = newId();

        assertThat(store.unsuppress(SOURCE, issueId)).isFalse();

        store.suppress(SOURCE, issueId, "minseo");
        assertThat(store.unsuppress(SOURCE, issueId)).isTrue();
    }

    /** 시각을 ISO-8601 문자열로 저장해 문자열 비교로 구간을 조회하므로, 경계가 맞는지 확인한다. */
    @Test
    void 호출_기록을_시간_구간으로_집계한다() {
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        int baseline = store.countCallsSince(before);
        double baselineCost = store.costSince(before);

        store.recordCall(CallPath.TRIAGE, "claude-haiku-4-5", new Usage(1000, 200, 0, 0), 0.002);
        store.recordCall(CallPath.ANALYZE, "claude-sonnet-5", new Usage(5000, 900, 0, 0), 0.019);

        assertThat(store.countCallsSince(before)).isEqualTo(baseline + 2);
        assertThat(store.costSince(before)).isEqualTo(baselineCost + 0.021, Offset.offset(1e-9));
        assertThat(store.countCallsSince(Instant.now().plus(1, ChronoUnit.MINUTES))).isZero();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
