package com.tmt.oncall.notify;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** JDA 이벤트를 {@link ButtonHandler}가 아는 값으로 옮기기만 한다. 판단은 여기서 하지 않는다. */
@Component
class DiscordButtonListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordButtonListener.class);

    private final ButtonHandler handler;

    DiscordButtonListener(ButtonHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        ReportButton.parse(event.getComponentId()).ifPresent(press -> {
            String actor = event.getUser().getEffectiveName();
            // 3초 안에 응답하지 않으면 Discord가 상호작용을 실패로 표시하므로, 실제 작업 전에 먼저 받아둔다
            event.deferReply(true).queue();
            try {
                String reply = handler.handle(press.button(), press.ref(), actor);
                event.getHook().sendMessage(reply).queue();
            } catch (RuntimeException e) {
                log.error("버튼 처리에 실패했다: {}", e.getMessage(), e);
                event.getHook().sendMessage("버튼 처리에 실패했다: " + e.getMessage()).queue();
            }
        });
    }
}
