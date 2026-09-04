package com.tmt.oncall.guard;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구독제 모드. 달러 집계와 단계적 차단이 빠지고 호출 횟수 상한만 남는지 확인한다.
 * 과금 모드를 바꿔도 호출 코드는 그대로라는 것이 이 테스트의 요지다.
 */
class CallBudgetSubscriptionModeTest {

    OncallStore store;
    CallBudget budget;

    @BeforeEach
    void setUp() {
        OncallProperties base = TestProperties.defaults();
        OncallProperties properties = TestProperties.withGuard(
                TestProperties.withAgent(base,
                        TestProperties.agent(BillingMode.SUBSCRIPTION, Duration.ofMinutes(10), "claude")),
                new OncallProperties.Guard(3, 1000, 1.0, Duration.ofMinutes(30)));
        store = TestStore.create().store();
        budget = new CallBudget(properties, store);
    }

    @Test
    void 비용이_상한을_넘어도_경로를_막지_않는다() {
        store.recordCall(CallPath.FIX, "claude-opus-5", Usage.NONE, 99.0);

        assertThat(budget.stage()).isEqualTo(CallBudget.Stage.NORMAL);
        assertThat(budget.check(CallPath.FIX).allowed()).isTrue();
    }

    @Test
    void 호출_횟수_상한은_그대로_동작한다() {
        for (int i = 0; i < 3; i++) {
            store.recordCall(CallPath.TRIAGE, "claude-haiku-4-5", Usage.NONE, 0);
        }

        CallBudget.Decision decision = budget.check(CallPath.TRIAGE);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("시간당 호출 상한");
    }
}
