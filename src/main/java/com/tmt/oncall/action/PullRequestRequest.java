package com.tmt.oncall.action;

import com.tmt.oncall.config.Target;

/**
 * 승인된 수정 한 건의 재료.
 *
 * @param ticketKey   티켓을 못 만들었으면 {@code null}. 키를 지어내지 않는다 — Jira 키는 순번이라
 *                    지어낸 키가 나중에 생길 진짜 티켓과 부딪힌다
 * @param summary     커밋 제목이자 PR 제목의 몸통
 * @param plan        승인된 수정 계획. 그대로 수정 에이전트의 입력이 된다
 * @param requestedBy 버튼을 누른 사람
 */
public record PullRequestRequest(
        Target target,
        String ticketKey,
        String summary,
        String plan,
        String requestedBy) {

    public boolean hasTicket() {
        return ticketKey != null && !ticketKey.isBlank();
    }
}
