package com.tmt.oncall.trigger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 폴링 주기만 담당한다. 감지 로직과 분리해 둬서, 끄면 스케줄 자체가 등록되지 않는다 —
 * 테스트나 로컬 실행이 실수로 외부 API를 때리는 길을 코드로 막는다.
 */
@Component
@ConditionalOnProperty(name = "oncall.trigger.enabled", matchIfMissing = true)
class TriggerSchedule {

    private final SentryWatcher sentryWatcher;
    private final HealthWatcher healthWatcher;

    TriggerSchedule(SentryWatcher sentryWatcher, HealthWatcher healthWatcher) {
        this.sentryWatcher = sentryWatcher;
        this.healthWatcher = healthWatcher;
    }

    @Scheduled(fixedDelayString = "${oncall.sentry.poll-interval}",
            initialDelayString = "${oncall.sentry.poll-interval}")
    void pollSentry() {
        sentryWatcher.poll();
    }

    @Scheduled(fixedDelayString = "${oncall.target.health-poll-interval}",
            initialDelayString = "${oncall.target.health-poll-interval}")
    void pollHealth() {
        healthWatcher.poll();
    }
}
