package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.guard.KillSwitch;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 봇이 떴다 죽는 것을 채널에 알린다. systemd가 조용히 재시작해 주는 덕에 사람은 봇이
 * 멀쩡한 줄 아는데, 정작 기동에 실패해 반복 재시작 중이면 장애 때 아무도 오지 않는다.
 *
 * <p>
 * 알림 자체가 기동을 막지는 않는다. Discord가 아직 붙지 않았거나 채널을 찾지 못해도
 * 봇의 나머지(폴링·감시)는 그대로 돌아야 한다.
 */
@Component
class LifecycleNotice {

    private static final Logger log = LoggerFactory.getLogger(LifecycleNotice.class);

    private final OncallProperties properties;
    private final DiscordNotifier notifier;
    private final KillSwitch killSwitch;

    LifecycleNotice(OncallProperties properties, DiscordNotifier notifier, KillSwitch killSwitch) {
        this.properties = properties;
        this.notifier = notifier;
        this.killSwitch = killSwitch;
    }

    /**
     * 컨텍스트가 다 뜬 뒤에 보낸다. Discord 연결도 빈 하나라, 기동 중에 보내면 아직 붙지
     * 않은 게이트웨이를 부르게 된다.
     */
    @EventListener(ApplicationReadyEvent.class)
    void started() {
        // 킬 스위치가 off인 채로 떴다는 사실이 특히 중요하다 — 봇은 살아 있는데 아무것도 하지 않는다.
        notice("온콜 봇이 기동했습니다. 킬 스위치: " + killSwitch.state());
    }

    @PreDestroy
    void stopping() {
        notice("온콜 봇이 종료됩니다. 재시작이면 곧 기동 알림이 이어집니다.");
    }

    private void notice(String line) {
        try {
            notifier.notice(properties.target().discordChannelId(), line);
        } catch (RuntimeException e) {
            log.warn("기동·종료 알림을 보내지 못했다: {}", e.getMessage());
        }
    }
}
