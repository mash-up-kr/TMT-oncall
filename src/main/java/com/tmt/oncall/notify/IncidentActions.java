package com.tmt.oncall.notify;

/**
 * 버튼이 실제로 돌리는 일. 오케스트레이션(TMT-330)·PR 경로(TMT-341)가 채워 넣는다.
 * 버튼 처리 쪽이 그 구현을 기다리지 않도록 인터페이스만 먼저 둔다.
 *
 * <p>구현은 작업이 끝나면 성공·실패와 무관하게 {@link ButtonHandler#finished(IncidentRef)}를
 * 호출해야 한다. 부르지 않으면 그 건의 버튼이 잠긴 채로 남아 사람이 다시 누를 수 없다.
 */
public interface IncidentActions {

    /** @param requestedBy 버튼을 누른 사람. 티켓·PR에 요청자로 기록된다 */
    void createPullRequest(IncidentRef ref, String requestedBy);

    void reanalyze(IncidentRef ref, String requestedBy);
}
