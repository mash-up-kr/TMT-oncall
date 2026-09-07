package com.tmt.oncall.support;

import com.tmt.oncall.notify.DiscordGateway;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.notify.ReportButton;
import com.tmt.oncall.notify.ReportEmbed;

import java.util.ArrayList;
import java.util.List;

/** 전송만 받아 적는 게이트웨이. 봇 토큰 없이 리포트가 어디로 갔는지를 본다. */
public final class FakeGateway implements DiscordGateway {

    public final List<ReportEmbed> channelEmbeds = new ArrayList<>();
    public final List<ReportEmbed> threadEmbeds = new ArrayList<>();
    public final List<String> threadFollowUps = new ArrayList<>();
    public final List<String> openedThreadNames = new ArrayList<>();
    public final List<String> notices = new ArrayList<>();
    /** 한 줄 알림이 간 곳. 버튼 결과가 채널이 아니라 스레드로 갔는지를 여기서 본다. */
    public final List<String> noticeChannelIds = new ArrayList<>();
    /** 리포트가 들어간 스레드. 재분석이 원 스레드로 갔는지를 여기서 본다. */
    public final List<String> threadIds = new ArrayList<>();
    public List<ReportButton> lastButtons = List.of();

    @Override
    public String send(String channelId, ReportEmbed embed, List<ReportButton> buttons, IncidentRef ref) {
        channelEmbeds.add(embed);
        lastButtons = buttons;
        return "message-" + channelEmbeds.size();
    }

    @Override
    public String openThread(String channelId, String messageId, String name) {
        openedThreadNames.add(name);
        return "thread-" + openedThreadNames.size();
    }

    @Override
    public void sendInThread(String threadId, ReportEmbed embed, List<String> followUps,
                             List<ReportButton> buttons, IncidentRef ref) {
        threadIds.add(threadId);
        lastButtons = buttons;
        if (embed != null) {
            threadEmbeds.add(embed);
        }
        threadFollowUps.addAll(followUps);
    }

    @Override
    public void sendNotice(String channelId, List<String> chunks) {
        noticeChannelIds.add(channelId);
        notices.addAll(chunks);
    }
}
