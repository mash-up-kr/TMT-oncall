package com.tmt.oncall.agent;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.guard.CallBudget;
import tools.jackson.databind.json.JsonMapper;

/** 다른 패키지의 테스트가 가짜 CLI를 물린 {@link AgentRunner}를 세울 수 있게 열어주는 통로. */
public final class TestAgentRunner {

    private TestAgentRunner() {
    }

    public static AgentRunner withFakeCli(OncallProperties properties, CallBudget budget) {
        return new AgentRunner(properties, budget, JsonMapper.builder().build(), name -> null);
    }
}
