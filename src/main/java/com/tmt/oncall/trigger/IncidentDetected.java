package com.tmt.oncall.trigger;

import com.tmt.oncall.config.Target;

/**
 * 조치가 필요할 수 있는 건을 감지했다. 감지와 분석을 이벤트로 끊어 두면 트리거가
 * 오케스트레이션을 알 필요가 없다.
 *
 * @param latestEventJson 대표 이벤트 원본. 스택트레이스·요청 컨텍스트가 여기 들어 있다
 */
public record IncidentDetected(Target target, SentryIssue issue, String latestEventJson) {
}
