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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDA 연결을 혼자 소유하고 리스너를 등록한다. 전송 쪽에서 첫 전송 때 지연 로그인하던 것을
 * 여기로 옮긴 이유는 질문 트리거다 — 메시지를 받으려면 봇이 보낼 일이 없어도 기동 시점부터
 * 게이트웨이에 붙어 있어야 한다.
 *
 * <p>
 * 대신 끄는 길을 설정으로 남긴다. 이 빈이 없으면 로그인도 재시도도 일어나지 않아, 더미 토큰으로
 * 컨텍스트를 띄우는 테스트나 로컬 실행이 실제 Discord로 나가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "oncall.discord.enabled", matchIfMissing = true)
class DiscordConnection {

    private static final Logger log = LoggerFactory.getLogger(DiscordConnection.class);

    /**
     * 기동을 붙잡아 둘 수 있는 한계. Discord 게이트웨이 핸드셰이크는 정상이면 수 초에 끝나므로,
     * 이보다 오래 걸리면 기다려도 나아질 상황이 아니라고 보고 재시도로 넘긴다.
     */
    private static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final OncallProperties properties;
    private final List<EventListener> listeners;

    private volatile JDA jda;

    DiscordConnection(OncallProperties properties, List<EventListener> listeners) {
        this.properties = properties;
        this.listeners = listeners;
    }

    @PostConstruct
    void connect() {
        tryConnect();
    }

    /**
     * Discord 연결 실패로 기동을 접지 않는다. 앱이 죽어 systemd 재시작 루프에 빠지면 Discord와
     * 무관한 Sentry 폴링·헬스 감시까지 함께 멈추기 때문이다. 대신 여기서 계속 붙어본다.
     */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void reconnect() {
        if (jda == null) {
            tryConnect();
        }
    }

    private synchronized void tryConnect() {
        if (jda != null) {
            return;
        }
        JDA built = null;
        try {
            // MESSAGE_CONTENT는 질문 본문을 읽는 데, GUILD_MEMBERS는 질문자의 역할로 답변 톤을
            // 판정하는 데 각각 필요하다. 둘 다 포털에서 따로 켜야 하는 특권 인텐트다.
            built = JDABuilder.createLight(properties.discord().botToken(),
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(listeners.toArray())
                    .build();
            awaitReady(built);
            jda = built;
            log.info("Discord에 연결했다 — 리스너 {}개", listeners.size());
        } catch (InterruptedException e) {
            shutdownNow(built);
            Thread.currentThread().interrupt();
            log.error("Discord 로그인이 중단됐다");
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            shutdownNow(built);
            log.error("Discord 연결에 실패했다, 1분 뒤 다시 시도한다: {}", e.getMessage());
        }
    }

    /** JDA의 {@code awaitReady()}에는 시간 제한이 없어, 기다리는 쪽에서 건다. */
    private static void awaitReady(JDA built) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture.runAsync(() -> {
            try {
                built.awaitReady();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    /** 연결이 덜 된 인스턴스도 게이트웨이 재접속을 계속 시도하므로, 재시도 전에 확실히 끊는다. */
    private static void shutdownNow(JDA built) {
        if (built != null) {
            built.shutdownNow();
        }
    }
}
