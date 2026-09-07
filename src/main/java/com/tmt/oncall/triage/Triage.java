package com.tmt.oncall.triage;

/**
 * 1차 분류 결과. 소스를 읽지 않고 Sentry 이벤트만 보고 내린 판정이라, 여기서 정하는 것은
 * 비싼 분석 경로로 넘길지 말지 하나다.
 *
 * @param reason   넘기거나 걸러낸 근거. 채널이 아니라 로그에만 남는다 — 걸러낸 건까지 채널에
 *                 쌓으면 정작 봐야 할 리포트가 묻힌다
 * @param severity 스킬이 매긴 심각도(high·medium·low). 지금은 기록만 한다
 */
public record Triage(boolean actionNeeded, String reason, String severity) {

    /** 형식이 깨졌을 때. 놓치는 쪽이 더 나쁘므로 조치가 필요한 것으로 본다. */
    static Triage conservative(String reason) {
        return new Triage(true, reason, "unknown");
    }
}
