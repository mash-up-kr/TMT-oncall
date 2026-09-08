package com.tmt.oncall.notify;

import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ButtonHandlerTest {

    private static final IncidentRef REF = IncidentRef.sentry("4501");

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    OncallStore store;
    RecordingActions actions;
    MutableClock clock;
    ButtonHandler handler;

    @BeforeEach
    void setUp() {
        store = TestStore.create().store();
        actions = new RecordingActions();
        clock = new MutableClock(NOW);
        handler = new ButtonHandler(store, new FixedProvider(actions), clock);
    }

    @Test
    void 무시는_영구_억제한다() {
        String reply = handler.handle(ReportButton.IGNORE, REF, "민서");

        assertThat(store.isSuppressed(REF.sourceKey(), REF.externalId())).isTrue();
        assertThat(reply).contains("/oncall unmute 4501");
    }

    /** 같은 건에 두 번 눌리면 티켓·PR이 두 벌 생긴다. */
    @Test
    void PR_만들기를_두_번_눌러도_한_번만_실행한다() {
        handler.handle(ReportButton.CREATE_PR, REF, "민서");
        String second = handler.handle(ReportButton.CREATE_PR, REF, "민서");

        assertThat(actions.pullRequests).containsExactly("4501:민서");
        assertThat(second).contains("이미 실행 중입니다");
    }

    @Test
    void 실행이_끝나면_다시_누를_수_있다() {
        handler.handle(ReportButton.CREATE_PR, REF, "민서");
        handler.finished(REF);
        handler.handle(ReportButton.CREATE_PR, REF, "민서");

        assertThat(actions.pullRequests).hasSize(2);
    }

    /** finished()가 불리지 않아 잠긴 건이 재시작 전까지 막히면 사람이 손쓸 길이 없다. */
    @Test
    void 오래된_잠금은_만료되어_다시_누를_수_있다() {
        handler.handle(ReportButton.CREATE_PR, REF, "민서");

        clock.advance(Duration.ofMinutes(29));
        assertThat(handler.handle(ReportButton.CREATE_PR, REF, "민서")).contains("이미 실행 중입니다");

        clock.advance(Duration.ofMinutes(2));
        assertThat(handler.handle(ReportButton.CREATE_PR, REF, "민서")).contains("PR 작업을 시작합니다");
        assertThat(actions.pullRequests).hasSize(2);
    }

    @Test
    void 무시된_건은_PR을_만들지_않는다() {
        handler.handle(ReportButton.IGNORE, REF, "민서");

        String reply = handler.handle(ReportButton.CREATE_PR, REF, "민서");

        assertThat(actions.pullRequests).isEmpty();
        assertThat(reply).contains("unmute");
    }

    @Test
    void 다시_분석도_한_건에_하나씩만_실행한다() {
        handler.handle(ReportButton.REANALYZE, REF, "민서");
        String second = handler.handle(ReportButton.REANALYZE, REF, "민서");

        assertThat(actions.reanalyses).hasSize(1);
        assertThat(second).contains("이미 실행 중입니다");
    }

    /**
     * 잠금은 건 단위라, 잡지 않은 쪽이 풀면 남의 것을 푼다 — 재분석이 끝나며 PR 작업의 잠금을
     * 풀면 같은 건에 티켓·PR이 두 벌 생긴다.
     */
    @Test
    void 재분석은_실행_중인_PR_작업의_잠금을_풀지_않는다() {
        handler.handle(ReportButton.CREATE_PR, REF, "민서");

        String reply = handler.handle(ReportButton.REANALYZE, REF, "지영");

        assertThat(actions.reanalyses).isEmpty();
        assertThat(reply).contains("이미 실행 중입니다");
        assertThat(handler.handle(ReportButton.CREATE_PR, REF, "민서")).contains("이미 실행 중입니다");
    }

    /** 실행 경로가 아직 없어도 버튼은 눌린다. 그때 잠가 두면 붙은 뒤에도 못 누른다. */
    @Test
    void 실행_경로가_없으면_알리고_잠그지_않는다() {
        ButtonHandler unwired = new ButtonHandler(store, new FixedProvider(null), clock);

        assertThat(unwired.handle(ReportButton.CREATE_PR, REF, "민서")).contains("연결되지 않았습니다");
        assertThat(unwired.handle(ReportButton.CREATE_PR, REF, "민서")).contains("연결되지 않았습니다");
    }

    @Test
    void 우리가_만들지_않은_custom_ID는_무시한다() {
        assertThat(ReportButton.parse("other-bot|create-pr|sentry|4501")).isEmpty();
        assertThat(ReportButton.parse("oncall|unknown|sentry|4501")).isEmpty();
        assertThat(ReportButton.parse(null)).isEmpty();

        assertThat(ReportButton.parse(ReportButton.CREATE_PR.customId(REF)))
                .contains(new ReportButton.Press(ReportButton.CREATE_PR, REF));
    }

    /** 만료를 대기 없이 검증하려고 시각을 직접 옮긴다. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static final class RecordingActions implements IncidentActions {

        final List<String> pullRequests = new ArrayList<>();
        final List<String> reanalyses = new ArrayList<>();

        @Override
        public void createPullRequest(IncidentRef ref, String requestedBy) {
            pullRequests.add(ref.externalId() + ":" + requestedBy);
        }

        @Override
        public void reanalyze(IncidentRef ref, String requestedBy) {
            reanalyses.add(ref.externalId() + ":" + requestedBy);
        }
    }

    /** 실행 경로가 아직 붙지 않은 상태(null)도 재현해야 해서 직접 만든다. */
    record FixedProvider(IncidentActions value) implements ObjectProvider<IncidentActions> {

        @Override
        public IncidentActions getObject(Object... args) {
            return getObject();
        }

        @Override
        public IncidentActions getObject() {
            if (value == null) {
                throw new IllegalStateException("등록된 실행 경로가 없다");
            }
            return value;
        }

        @Override
        public IncidentActions getIfAvailable() {
            return value;
        }

        @Override
        public IncidentActions getIfUnique() {
            return value;
        }
    }
}
