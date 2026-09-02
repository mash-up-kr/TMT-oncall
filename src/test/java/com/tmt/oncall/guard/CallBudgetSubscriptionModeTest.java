package com.tmt.oncall.guard;

import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import com.tmt.oncall.store.OncallStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구독제 모드. 달러 집계와 단계적 차단이 빠지고 호출 횟수 상한만 남는지 확인한다.
 * 과금 모드를 바꿔도 호출 코드는 그대로라는 것이 이 테스트의 요지다.
 */
@SpringBootTest(properties = {
        "oncall.store.path=build/test-store/billing-subscription/state.db",
        "oncall.agent.billing=subscription",
        "oncall.guard.max-cost-per-month=1.0",
        "oncall.guard.max-calls-per-hour=3",
        "oncall.guard.max-calls-per-day=1000"
})
@ActiveProfiles("test")
class CallBudgetSubscriptionModeTest {

    @Autowired
    CallBudget budget;

    @Autowired
    OncallStore store;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clearCallLog() {
        jdbc.sql("DELETE FROM call_log").update();
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
