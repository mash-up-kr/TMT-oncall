package com.tmt.oncall.trigger;

/**
 * 받은 메시지를 어떻게 할지. "무시"와 "안내만 하고 무시"를 갈라 두면 판단은 값으로 남고,
 * 전송은 JDA를 아는 리스너 쪽에만 있다.
 */
public sealed interface IntakeResult {

    /** 질문으로 받았다. 이벤트로 발행한다. */
    record Accepted(QuestionAsked question) implements IntakeResult {
    }

    /** 에이전트를 부르지 않고 채널에 한 줄만 남긴다. */
    record Notice(String channelId, String line) implements IntakeResult {
    }

    record Ignored() implements IntakeResult {
    }

    IntakeResult IGNORED = new Ignored();
}
