package com.tmt.oncall.notify;

import java.util.List;

/**
 * 실제 전송만 담당한다. JDA를 이 뒤로 숨겨 두면 리포트 문구와 분할은 봇 토큰 없이 테스트할 수 있고,
 * 토큰이 나오기 전까지 전송은 얇게 둔 채로 나머지를 굳힐 수 있다.
 */
public interface DiscordGateway {

    /**
     * @param buttons 마지막 조각에만 붙인다. 중간 조각에 붙으면 같은 버튼이 여러 번 보인다
     * @return 보낸 첫 메시지의 ID. 이 메시지에 스레드를 연다
     */
    String send(String channelId, List<String> chunks, List<ReportButton> buttons, IncidentRef ref);

    String openThread(String channelId, String messageId, String name);

    void sendInThread(String threadId, List<String> chunks, List<ReportButton> buttons, IncidentRef ref);
}
