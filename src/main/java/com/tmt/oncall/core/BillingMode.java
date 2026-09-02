package com.tmt.oncall.core;

/**
 * 봇이 에이전트 CLI를 어떻게 결제하는지. 인증 방식과 비용 집계 여부가 함께 결정되므로
 * 두 값을 따로 두지 않는다 — 따로 두면 "구독제인데 API 키가 환경에 남아 있어
 * 돈은 나가는데 집계는 꺼진" 조합이 만들어진다.
 */
public enum BillingMode {

    /** 종량제. {@code ONCALL_AGENT_API_KEY}로 인증하고 달러를 집계한다. */
    API_KEY,

    /**
     * 구독제. CLI에 저장된 자격 증명으로 인증한다. 달러 개념이 없어 비용 집계와
     * 단계적 차단은 꺼지고 호출 횟수 상한만 남는다.
     *
     * <p>이 모드에서는 하위 프로세스 환경에서 CLI가 읽는 키 변수를 반드시 제거해야 한다
     * ({@code AgentRunner}의 상수). 키가 남아 있으면 구독 자격 증명을 가리고
     * 조용히 종량제로 청구된다.
     */
    SUBSCRIPTION;

    public boolean tracksCost() {
        return this == API_KEY;
    }

    public boolean usesApiKey() {
        return this == API_KEY;
    }
}
