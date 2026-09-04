package com.tmt.oncall.action;

/**
 * 티켓 생성 결과. 실패해도 수정·PR은 계속 진행하고 채널에만 사유를 알리므로,
 * 흐름을 끊는 예외가 아니라 값으로 돌려준다.
 */
public sealed interface TicketResult {

    record Created(String key, String url) implements TicketResult {
    }

    record Failed(String reason) implements TicketResult {
    }
}
