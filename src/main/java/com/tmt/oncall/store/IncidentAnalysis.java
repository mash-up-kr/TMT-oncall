package com.tmt.oncall.store;

import java.time.Instant;

/**
 * 리포트를 낸 시점의 분석 결과 중, 버튼을 눌렀을 때 다시 필요한 것들.
 *
 * <p>
 * 버튼은 봇이 재시작한 뒤에도 눌린다. 그때 티켓·PR의 재료를 메모리에서 꺼낼 수 없으므로
 * 리포트와 함께 남겨 둔다. 분석 전문이 아니라 재료만 담는 이유는 사람이 읽을 결과는
 * 이미 Discord 스레드에 있기 때문이다.
 *
 * <p>
 * 질문에서 시작한 건도 같은 자리에 담는다 — 버튼이 하나라 꺼내는 길도 하나여야 한다. 대신
 * {@code stackExcerpt}·{@code sentryIssueUrl}은 비어 있다. 질문에는 그런 것이 없고,
 * 없는 값을 지어내면 티켓과 PR이 엉뚱한 곳을 가리킨다.
 *
 * @param summary    티켓 제목이자 PR 제목의 몸통. 에러는 이슈 제목, 질문은 물어본 말을 줄여 쓴다
 * @param plan       승인된 수정 계획. 그대로 수정 에이전트의 입력이 된다
 * @param occurredAt 에러는 최근 발생 시각, 질문은 답한 시각
 */
public record IncidentAnalysis(
        String sourceKey,
        String externalId,
        String summary,
        String plan,
        String stackExcerpt,
        String sentryIssueUrl,
        Instant occurredAt) {
}
