package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JDA 연결을 혼자 소유하고 리스너를 등록한다. 전송 쪽에서 첫 전송 때 지연 로그인하던 것을
 * 여기로 옮긴 이유는 질문 트리거다 — 메시지를 받으려면 봇이 보낼 일이 없어도 기동 시점부터
 * 게이트웨이에 붙어 있어야 한다.
 *
 * <p>
 * 대신 끄는 길을 설정으로 남긴다. 이 빈이 없으면 로그인 자체가 일어나지 않아, 더미 토큰으로
 * 컨텍스트를 띄우는 테스트나 로컬 실행이 실제 Discord로 나가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "oncall.discord.enabled", matchIfMissing = true)
class DiscordConnection {

    private static final Logger log = LoggerFactory.getLogger(DiscordConnection.class);

    private final OncallProperties properties;
    private final List<EventListener> listeners;

    private volatile JDA jda;

    DiscordConnection(OncallProperties properties, List<EventListener> listeners) {
        this.properties = properties;
        this.listeners = listeners;
    }

    @PostConstruct
    void connect() {
        try {
            // MESSAGE_CONTENT는 질문 본문을 읽는 데, GUILD_MEMBERS는 질문자의 역할로 답변 톤을
            // 판정하는 데 각각 필요하다. 둘 다 포털에서 따로 켜야 하는 특권 인텐트다.
            jda = JDABuilder.createLight(properties.discord().botToken(),
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(listeners.toArray())
                    .build()
                    .awaitReady();
            log.info("Discord에 연결했다 — 리스너 {}개", listeners.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord 로그인이 중단됐다", e);
        }
    }

    JDA jda() {
        JDA current = jda;
        if (current == null) {
            throw new IllegalStateException("Discord에 연결되지 않았다");
        }
        return current;
    }

    @PreDestroy
    void disconnect() {
        JDA current = jda;
        if (current != null) {
            current.shutdown();
        }
    }
}
