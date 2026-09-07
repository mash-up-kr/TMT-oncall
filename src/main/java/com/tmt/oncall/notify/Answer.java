package com.tmt.oncall.notify;

import java.util.List;

/**
 * 질문에 대한 답변. 톤은 이미 스킬이 입혀 왔으므로 봇은 여기서 문구를 손대지 않는다.
 *
 * @param fixPlan       코드 수정이 필요할 때만 채워진다
 * @param codeFixNeeded 수정이 필요한 질문인지. 버튼을 붙일지가 여기서 갈린다
 */
public record Answer(String text, List<String> fixPlan, boolean codeFixNeeded) {

    public Answer {
        fixPlan = List.copyOf(fixPlan);
    }

    /** 스킬이 약속한 형식을 지키지 않았을 때. 답변 본문은 살리고 수정 경로만 닫는다. */
    public static Answer plain(String text) {
        return new Answer(text, List.of(), false);
    }
}
