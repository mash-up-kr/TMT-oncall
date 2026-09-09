package com.tmt.oncall.notify;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** JDA 이벤트를 {@link ButtonHandler}가 아는 값으로 옮기기만 한다. 판단은 여기서 하지 않는다. */
@Component
class DiscordButtonListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordButtonListener.class);

    private final ButtonHandler handler;
    private final Executor executor;

    @Autowired
    DiscordButtonListener(ButtonHandler handler) {
        this(handler, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("button-", 1).factory()));
    }

    /** 테스트가 같은 스레드에서 돌리려고 실행기를 갈아 끼우는 자리. */
    DiscordButtonListener(ButtonHandler handler, Executor executor) {
        this.handler = handler;
        this.executor = executor;
    }

    /**
     * 실제 작업은 수신 스레드에서 떼어 낸다. 수정·PR은 분 단위로 걸리는데 JDA의 수신 스레드를
     * 붙잡고 있으면 그동안 하트비트가 나가지 못해 재연결이 걸리고, 그 재연결이 이 스레드를
     * 인터럽트해 하던 작업까지 끊는다.
     */
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        ReportButton.parse(event.getComponentId()).ifPresent(press -> {
            String actor = event.getUser().getEffectiveName();
            // 3초 안에 응답하지 않으면 Discord가 상호작용을 실패로 표시하므로, 실제 작업 전에 먼저 받아둔다
            event.deferReply(true).queue();
            executor.execute(() -> respond(event, press, actor));
        });
    }

    private void respond(ButtonInteractionEvent event, ReportButton.Press press, String actor) {
        String reply;
        try {
            reply = handler.handle(press.button(), press.ref(), actor);
        } catch (RuntimeException e) {
            log.error("버튼 처리에 실패했다: {}", e.getMessage(), e);
            reply = "버튼 처리에 실패했습니다: " + e.getMessage();
        }
        // 상호작용 토큰은 15분이면 만료된다. 그 뒤에는 누른 사람에게 전할 길이 없으므로 로그에 남긴다.
        event.getHook().sendMessage(reply)
                .queue(null, error -> log.error("버튼 결과를 전하지 못했다 — {}/{}: {}",
                        press.ref().sourceKey(), press.ref().externalId(), error.getMessage()));
    }
}
