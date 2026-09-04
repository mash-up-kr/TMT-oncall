package com.tmt.oncall.notify;

/**
 * 버튼이 실제로 돌리는 일. 오케스트레이션(TMT-330)·PR 경로(TMT-341)가 채워 넣는다.
 * 버튼 처리 쪽이 그 구현을 기다리지 않도록 인터페이스만 먼저 둔다.
 */
public interface IncidentActions {

    /** @param requestedBy 버튼을 누른 사람. 티켓·PR에 요청자로 기록된다 */
    void createPullRequest(IncidentRef ref, String requestedBy);

    void reanalyze(IncidentRef ref, String requestedBy);
}
