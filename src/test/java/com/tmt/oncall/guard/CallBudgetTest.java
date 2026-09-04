package com.tmt.oncall.guard;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CallBudgetTest {

    OncallProperties properties;
    OncallStore store;
    CallBudget budget;

    @BeforeEach
    void setUp() {
        properties = TestProperties.withGuard(TestProperties.defaults(),
                new OncallProperties.Guard(1000, 1000, 1.0, Duration.ofMinutes(30)));
        store = TestStore.create().store();
        budget = new CallBudget(properties, store);
    }

    @Test
    void 캐시_토큰까지_반영해_비용을_계산한다() {
        OncallProperties.Agent.Model haiku = properties.agent().modelFor(CallPath.TRIAGE);
        Usage usage = new Usage(1_000_000, 1_000_000, 1_000_000, 1_000_000);

        // 입력 $1 + 출력 $5 + 캐시 쓰기 $1.25 + 캐시 읽기 $0.10
        assertThat(budget.costOf(haiku, usage)).isEqualTo(7.35, Offset.offset(1e-9));
    }

    @Test
    void 한도에_다다르면_비싼_경로부터_끊고_알림은_살린다() {
        assertThat(budget.stage()).isEqualTo(CallBudget.Stage.NORMAL);
        assertThat(budget.check(CallPath.FIX).allowed()).isTrue();

        // 70% — 경고 단계지만 아직 모든 경로가 열려 있다
        spend(0.70);
        assertThat(budget.stage()).isEqualTo(CallBudget.Stage.WARN);
        assertThat(budget.check(CallPath.FIX).allowed()).isTrue();

        // 85% — 수정·PR만 막힌다
        spend(0.15);
        assertThat(budget.stage()).isEqualTo(CallBudget.Stage.FIX_BLOCKED);
        assertThat(budget.check(CallPath.FIX).allowed()).isFalse();
        assertThat(budget.check(CallPath.ANALYZE).allowed()).isTrue();

        // 100% — 1차 분류만 남는다
        spend(0.15);
        assertThat(budget.stage()).isEqualTo(CallBudget.Stage.TRIAGE_ONLY);
        assertThat(budget.check(CallPath.ANALYZE).allowed()).isFalse();
        assertThat(budget.check(CallPath.TRIAGE).allowed()).isTrue();
    }

    private void spend(double usd) {
        store.recordCall(CallPath.ANALYZE, "claude-sonnet-5", Usage.NONE, usd);
    }
}
