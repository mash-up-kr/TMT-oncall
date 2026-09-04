package com.tmt.oncall.trigger;

import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.Audience;

/**
 * 온콜 채널에서 답변할 질문을 받았다. 감지와 답변을 이벤트로 끊어 두면 트리거가
 * 오케스트레이션을 알 필요가 없다.
 *
 * @param audience   질문자의 역할로 정한 답변 톤. 판정은 수신 시점에 끝내 둔다 —
 *                   답변할 때 다시 조회하면 그 사이 역할이 바뀔 수 있다
 * @param threadId   스레드 안에서 받았으면 그 스레드. 채널 본문이면 null
 * @param reanalysis 기존 리포트 스레드에 달린 메시지라 재분석 힌트로 다룬다
 */
public record QuestionAsked(
        Target target,
        Audience audience,
        String channelId,
        String threadId,
        String messageId,
        String authorId,
        String authorName,
        String content,
        boolean reanalysis) {
}
