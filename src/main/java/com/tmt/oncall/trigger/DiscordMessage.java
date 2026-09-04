package com.tmt.oncall.trigger;

import java.util.List;

/**
 * 수신한 Discord 메시지에서 판정에 쓰는 값만 뽑아낸 것. JDA 이벤트를 그대로 넘기지 않는 이유는
 * 봇 토큰 없이 검증할 수 없는 타입에 판단 로직이 묶이지 않게 하기 위해서다.
 *
 * @param channelId       메시지가 올라온 채널. 스레드면 스레드 자신의 ID다
 * @param parentChannelId 스레드일 때 그 스레드가 달린 채널. 스레드가 아니면 null
 * @param fromBot         봇 자신을 포함한 모든 봇
 * @param roleIds         질문자가 가진 역할 ID. 길드 밖 메시지 등 조회할 수 없으면 비어 있다
 */
public record DiscordMessage(
        String channelId,
        String parentChannelId,
        boolean fromBot,
        String messageId,
        String authorId,
        String authorName,
        List<String> roleIds,
        String content) {

    public boolean inThread() {
        return parentChannelId != null;
    }
}
