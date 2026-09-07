package com.tmt.oncall.store;

import com.tmt.oncall.core.Audience;

/**
 * 봇이 답변하며 연 스레드와 그때의 원 질문.
 *
 * @param messageId 질문 메시지 ID. 재분석 답변도 같은 건으로 묶으려면 첫 질문의 키를 써야 한다
 * @param audience  첫 답변의 톤. 기록용이고, 재분석 톤은 그때 물어본 사람의 역할로 다시 정한다
 */
public record QuestionThread(String threadId, String messageId, Audience audience, String content) {
}
