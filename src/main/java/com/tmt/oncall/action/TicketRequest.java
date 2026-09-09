package com.tmt.oncall.action;

import com.tmt.oncall.config.Target;

import java.time.Instant;

/**
 * 자동 생성할 티켓의 재료.
 *
 * @param requestedBy  버튼을 누른 사람. 봇이 스스로 판단해 만든 티켓이 아니라는 기록이다
 * @param plan         승인된 수정 계획. 티켓만 보고도 무엇을 할 일인지 알 수 있어야 한다
 * @param threadUrl    분석 내용이 있는 Discord 스레드. 배경과 대화는 여기로 보낸다
 */
public record TicketRequest(
        Target target,
        String summary,
        String sentryIssueUrl,
        Instant occurredAt,
        String stackSummary,
        String plan,
        String threadUrl,
        String requestedBy) {
}
