package com.tmt.oncall.notify;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** JDA로 실제 전송한다. 로직을 두지 않는다 — 여기 든 것은 봇 토큰 없이는 검증할 수 없다. */
@Component
class JdaDiscordGateway implements DiscordGateway {

    private static final Logger log = LoggerFactory.getLogger(JdaDiscordGateway.class);

    /** 연결이 꺼져 있으면 이 빈이 없다. 전송을 시도한 시점에야 알 수 있게 지연 조회한다. */
    private final ObjectProvider<DiscordConnection> connection;

    JdaDiscordGateway(ObjectProvider<DiscordConnection> connection) {
        this.connection = connection;
    }

    @Override
    public String send(String channelId, ReportEmbed embed, List<ReportButton> buttons, IncidentRef ref) {
        MessageChannel channel = channel(channelId);
        return channel.sendMessageEmbeds(toEmbed(embed))
                .addComponents(components(buttons, ref))
                .complete()
                .getId();
    }

    @Override
    public String openThread(String channelId, String messageId, String name) {
        Message message = channel(channelId).retrieveMessageById(messageId).complete();
        return message.createThreadChannel(name).complete().getId();
    }

    @Override
    public void sendInThread(String threadId, ReportEmbed embed, List<String> followUps,
                             List<ReportButton> buttons, IncidentRef ref) {
        MessageChannel thread = channel(threadId);
        if (embed != null) {
            thread.sendMessageEmbeds(toEmbed(embed)).complete();
        }
        for (int i = 0; i < followUps.size(); i++) {
            MessageCreateAction action = thread.sendMessage(followUps.get(i));
            if (i == followUps.size() - 1) {
                action = action.addComponents(components(buttons, ref));
            }
            action.complete();
        }
    }

    @Override
    public void sendNotice(String channelId, List<String> chunks) {
        MessageChannel channel = channel(channelId);
        chunks.forEach(chunk -> channel.sendMessage(chunk).complete());
    }

    @Override
    public String threadUrl(String threadId) {
        ThreadChannel thread = jda().getThreadChannelById(threadId);
        if (thread == null) {
            log.warn("스레드를 찾지 못해 주소를 만들지 못했다 — {}", threadId);
            return "";
        }
        return "https://discord.com/channels/%s/%s".formatted(thread.getGuild().getId(), threadId);
    }

    private MessageChannel channel(String channelId) {
        MessageChannel channel = jda().getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            throw new IllegalStateException("채널을 찾을 수 없다: " + channelId);
        }
        return channel;
    }

    private static MessageEmbed toEmbed(ReportEmbed embed) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(embed.title())
                .setDescription(embed.description())
                .setColor(embed.color().rgb())
                .setTimestamp(embed.timestamp());
        embed.fields().forEach(field -> builder.addField(field.name(), field.value(), field.inline()));
        return builder.build();
    }

    private static List<ActionRow> components(List<ReportButton> buttons, IncidentRef ref) {
        if (buttons.isEmpty() || ref == null) {
            return List.of();
        }
        return List.of(ActionRow.of(buttons.stream()
                .map(button -> toButton(button, ref))
                .toList()));
    }

    /** 'PR 만들기'만 눈에 띄게 둔다 — 되돌릴 수 없는 유일한 버튼이다. */
    private static Button toButton(ReportButton button, IncidentRef ref) {
        return switch (button) {
            case CREATE_PR -> Button.success(button.customId(ref), button.label());
            case REANALYZE -> Button.secondary(button.customId(ref), button.label());
            case IGNORE -> Button.danger(button.customId(ref), button.label());
        };
    }

    private JDA jda() {
        DiscordConnection current = connection.getIfAvailable();
        if (current == null) {
            throw new IllegalStateException("Discord 연결이 꺼져 있다 (oncall.discord.enabled=false)");
        }
        return current.jda();
    }
}
