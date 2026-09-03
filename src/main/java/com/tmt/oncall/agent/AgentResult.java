package com.tmt.oncall.agent;

import com.tmt.oncall.core.Usage;

/** 에이전트 호출 결과. 호출부가 세 갈래를 모두 채널 보고로 처리하므로 실패도 값으로 돌려준다. */
public sealed interface AgentResult {

    /** 정상 종료. {@code text}는 CLI가 돌려준 최종 응답이다. */
    record Ok(String text, Usage usage) implements AgentResult {
    }

    /** 가드가 막았다. 호출 자체가 일어나지 않았으므로 비용도 없다. */
    record Blocked(String reason) implements AgentResult {
    }

    /** 실행은 했지만 실패했다 — 비정상 종료·타임아웃·응답 파싱 불가. */
    record Failed(String reason, Usage usage) implements AgentResult {
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    /** 성공했으면 응답 텍스트, 아니면 사유. 채널 보고용. */
    default String message() {
        return switch (this) {
            case Ok ok -> ok.text();
            case Blocked blocked -> blocked.reason();
            case Failed failed -> failed.reason();
        };
    }
}
