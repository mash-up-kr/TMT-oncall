package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JDA로 실제 전송한다. 로직을 두지 않는다 — 여기 든 것은 봇 토큰 없이는 검증할 수 없다.
 */
@Component
class JdaDiscordGateway implements DiscordGateway {

    private final OncallProperties properties;
    private final DiscordButtonListener buttonListener;

    /**
     * 로그인은 첫 전송 때 한다. 빈 생성 시점에 접속하면 토큰 없이 뜨는 테스트·로컬 실행이
     * 곧바로 Discord로 나간다.
     */
    private volatile JDA jda;

    JdaDiscordGateway(OncallProperties properties, DiscordButtonListener buttonListener) {
        this.properties = properties;
        this.buttonListener = buttonListener;
    }

    @Override
    public String send(String channelId, List<String> chunks, List<ReportButton> buttons, IncidentRef ref) {
        MessageChannel channel = jda().getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            throw new IllegalStateException("채널을 찾을 수 없다: " + channelId);
        }
        return sendChunks(channel, chunks, buttons, ref).getId();
    }

    @Override
    public String openThread(String channelId, String messageId, String name) {
        MessageChannel channel = jda().getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            throw new IllegalStateException("채널을 찾을 수 없다: " + channelId);
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        return message.createThreadChannel(name).complete().getId();
    }

    @Override
    public void sendInThread(String threadId, List<String> chunks, List<ReportButton> buttons, IncidentRef ref) {
        MessageChannel thread = jda().getChannelById(MessageChannel.class, threadId);
        if (thread == null) {
            throw new IllegalStateException("스레드를 찾을 수 없다: " + threadId);
        }
        sendChunks(thread, chunks, buttons, ref);
    }

    /** @return 첫 조각의 메시지. 스레드는 여기에 열어야 채널에서 리포트 머리와 붙어 보인다 */
    private Message sendChunks(MessageChannel channel, List<String> chunks,
                               List<ReportButton> buttons, IncidentRef ref) {
        Message first = null;
        for (int i = 0; i < chunks.size(); i++) {
            MessageCreateAction action = channel.sendMessage(chunks.get(i));
            boolean last = i == chunks.size() - 1;
            if (last && !buttons.isEmpty() && ref != null) {
                action = action.addComponents(ActionRow.of(toComponents(buttons, ref)));
            }
            Message sent = action.complete();
            if (first == null) {
                first = sent;
            }
        }
        return first;
    }

    /** 'PR 만들기'만 눈에 띄게 둔다 — 되돌릴 수 없는 유일한 버튼이다. */
    private static List<Button> toComponents(List<ReportButton> buttons, IncidentRef ref) {
        return buttons.stream()
                .map(button -> switch (button) {
                    case CREATE_PR -> Button.success(button.customId(ref), button.label());
                    case REANALYZE -> Button.secondary(button.customId(ref), button.label());
                    case IGNORE -> Button.danger(button.customId(ref), button.label());
                })
                .toList();
    }

    private JDA jda() {
        JDA current = jda;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (jda == null) {
                jda = login();
            }
            return jda;
        }
    }

    private JDA login() {
        try {
            // 질문 트리거가 메시지 본문을 읽어야 해 MESSAGE_CONTENT는 여기서부터 켜 둔다
            return JDABuilder.createLight(properties.discord().botToken(),
                            GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(buttonListener)
                    .build()
                    .awaitReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord 로그인이 중단됐다", e);
        }
    }
}
