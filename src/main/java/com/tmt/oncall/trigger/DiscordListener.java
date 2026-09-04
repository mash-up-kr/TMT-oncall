package com.tmt.oncall.trigger;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/** JDA 이벤트를 {@link QuestionIntake}가 아는 값으로 옮기기만 한다. 판단은 여기서 하지 않는다. */
@Component
class DiscordListener extends ListenerAdapter {

    private final QuestionIntake intake;
    private final ApplicationEventPublisher events;

    DiscordListener(QuestionIntake intake, ApplicationEventPublisher events) {
        this.intake = intake;
        this.events = events;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        intake.accept(toMessage(event)).ifPresent(events::publishEvent);
    }

    private static DiscordMessage toMessage(MessageReceivedEvent event) {
        return new DiscordMessage(
                event.getChannel().getId(),
                parentChannelId(event),
                event.getAuthor().isBot(),
                event.getMessageId(),
                event.getAuthor().getId(),
                event.getAuthor().getEffectiveName(),
                roleIds(event.getMember()),
                event.getMessage().getContentDisplay());
    }

    private static String parentChannelId(MessageReceivedEvent event) {
        if (!event.getChannelType().isThread()) {
            return null;
        }
        return event.getChannel().asThreadChannel().getParentChannel().getId();
    }

    /** DM 등 길드 밖 메시지는 멤버가 없다. 채널 필터에서 어차피 걸리므로 빈 목록으로 넘긴다. */
    private static List<String> roleIds(Member member) {
        if (member == null) {
            return List.of();
        }
        return member.getRoles().stream().map(Role::getId).toList();
    }
}
