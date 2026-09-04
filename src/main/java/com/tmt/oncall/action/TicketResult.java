package com.tmt.oncall.action;

/** 티켓 생성 결과. 실패하면 PR 경로를 멈추고 채널에 사유를 알려야 하므로 값으로 돌려준다. */
public sealed interface TicketResult {

    record Created(String key, String url) implements TicketResult {
    }

    record Failed(String reason) implements TicketResult {
    }
}
