package com.tmt.oncall.notify;

import java.util.List;

/**
 * 실제 전송만 담당한다. JDA를 이 뒤로 숨겨 두면 리포트 구조와 분할은 봇 토큰 없이 테스트할 수 있고,
 * 토큰이 나오기 전까지 전송은 얇게 둔 채로 나머지를 굳힐 수 있다.
 */
public interface DiscordGateway {

    /**
     * @param buttons 마지막 메시지에만 붙인다. 앞선 메시지에 붙으면 같은 버튼이 여러 번 보인다
     * @return 임베드를 실은 메시지의 ID. 이 메시지에 스레드를 연다
     */
    String send(String channelId, ReportEmbed embed, List<ReportButton> buttons, IncidentRef ref);

    String openThread(String channelId, String messageId, String name);

    /**
     * @param embed     스택만 이어 보내는 경우처럼 임베드가 없을 수 있다
     * @param followUps 스택·빌드 로그처럼 임베드에 담기 어려운 본문. 이미 2000자로 잘려 온다
     */
    void sendInThread(String threadId, ReportEmbed embed, List<String> followUps,
                      List<ReportButton> buttons, IncidentRef ref);

    /**
     * 스레드로 바로 가는 주소. 주소를 지으려면 길드 ID가 필요한데 그것을 아는 곳이 여기뿐이라
     * 전송만 담당한다는 원칙에서 한 발 나온다.
     *
     * @return 스레드를 찾지 못하면 빈 문자열. 링크 하나 때문에 티켓 생성을 막지 않는다
     */
    String threadUrl(String threadId);

    /** 기동·종료·예산 경고처럼 리포트가 아닌 한 줄 알림. */
    void sendNotice(String channelId, List<String> chunks);
}
