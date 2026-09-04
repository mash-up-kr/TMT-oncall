package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.guard.KillSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@code /actuator/health}를 주기 폴링해 다운을 감지한다. 상태가 바뀌는 시점에만 알리고,
 * 다운이 이어지는 동안은 침묵한다 — 같은 장애로 채널을 도배하지 않는다.
 */
@Component
public class HealthWatcher {

    private static final Logger log = LoggerFactory.getLogger(HealthWatcher.class);

    /** 응답을 기다리는 한계. 폴링 주기보다 짧아야 다음 폴링과 겹치지 않는다. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** 다운 직전 상황으로 함께 보낼 Sentry 이슈 수. */
    private static final int RECENT_ISSUE_LIMIT = 5;

    private final OncallProperties properties;
    private final RestClient restClient;
    private final SentryClient sentryClient;
    private final KillSwitch killSwitch;
    private final ApplicationEventPublisher events;

    private int consecutiveFailures;
    private Instant downSince;

    @Autowired
    HealthWatcher(OncallProperties properties, SentryClient sentryClient, KillSwitch killSwitch,
                  ApplicationEventPublisher events) {
        this(properties, RestClient.builder().requestFactory(probeRequestFactory()),
                sentryClient, killSwitch, events);
    }

    HealthWatcher(OncallProperties properties, RestClient.Builder builder, SentryClient sentryClient,
                  KillSwitch killSwitch, ApplicationEventPublisher events) {
        this.properties = properties;
        this.restClient = builder.build();
        this.sentryClient = sentryClient;
        this.killSwitch = killSwitch;
        this.events = events;
    }

    public void poll() {
        Target target = properties.target();
        if (!killSwitch.isEnabled() || !target.hasDownTrigger()) {
            return;
        }

        Probe probe = probe(target.healthUrl());
        if (probe.healthy()) {
            recover(target);
            return;
        }

        consecutiveFailures++;
        log.warn("헬스체크 실패 {}회 — {}", consecutiveFailures, probe.detail());
        if (consecutiveFailures >= target.healthFailureThreshold() && downSince == null) {
            declareDown(target, probe);
        }
    }

    private void declareDown(Target target, Probe probe) {
        downSince = Instant.now();

        // 응답이 아예 없을 때만 liveness를 찔러 본다. 앱이 떠 있는데 의존성만 죽은 경우와
        // 프로세스가 죽은 경우는 조치가 달라서 리포트에서 갈라야 한다.
        ServiceDownDetected.Kind kind = probe.responded()
                ? ServiceDownDetected.Kind.DEGRADED
                : probe(livenessUrl(target.healthUrl())).healthy()
                        ? ServiceDownDetected.Kind.DEGRADED
                        : ServiceDownDetected.Kind.UNREACHABLE;

        log.error("서비스 다운 판정 — {} / {}", kind, probe.detail());
        // 실제 무응답 구간은 첫 실패부터다. 폴링 주기 × 실패 횟수로 환산해 리포트가
        // 내부 사정(실패 횟수) 대신 지속 시간으로 말하게 한다.
        Duration unresponsiveFor = target.healthPollInterval().multipliedBy(consecutiveFailures);
        events.publishEvent(new ServiceDownDetected(target, kind, probe.detail(), unresponsiveFor,
                downSince, probe.body(), recentIssues(target)));
    }

    private void recover(Target target) {
        int failures = consecutiveFailures;
        consecutiveFailures = 0;
        if (downSince == null) {
            if (failures > 0) {
                log.info("헬스체크가 임계 전에 회복했다 — 알리지 않는다 (실패 {}회)", failures);
            }
            return;
        }

        Instant recoveredAt = Instant.now();
        Duration downFor = Duration.between(downSince, recoveredAt);
        downSince = null;
        log.info("서비스 복구 — 다운 지속 {}", downFor);
        events.publishEvent(new ServiceRecovered(target, downFor, recoveredAt));
    }

    /** 이슈 조회가 실패해도 다운 알림 자체는 나가야 한다. */
    private List<SentryIssue> recentIssues(Target target) {
        if (!target.hasErrorTrigger()) {
            return List.of();
        }
        try {
            return sentryClient.unresolvedIssues(
                    properties.sentry().orgSlug(), target.sentryProjectSlug(), RECENT_ISSUE_LIMIT);
        } catch (RestClientException e) {
            log.warn("다운 직전 Sentry 이슈를 가져오지 못했다: {}", e.getMessage());
            return List.of();
        }
    }

    /** @param body 앱이 돌려준 헬스 응답 본문. 응답이 없으면 빈 문자열이다 */
    private record Probe(boolean healthy, boolean responded, String detail, String body) {
    }

    private Probe probe(String url) {
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return new Probe(true, true, body, body == null ? "" : body);
        } catch (RestClientResponseException e) {
            // 상태 코드가 실려 오면 앱이 응답한 것이다. 어느 의존성이 DOWN인지는 본문에 있다.
            return new Probe(false, true, e.getMessage(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            return new Probe(false, false, e.getMessage(), "");
        }
    }

    private static SimpleClientHttpRequestFactory probeRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PROBE_TIMEOUT);
        factory.setReadTimeout(PROBE_TIMEOUT);
        return factory;
    }

    private static String livenessUrl(String healthUrl) {
        return healthUrl.endsWith("/") ? healthUrl + "liveness" : healthUrl + "/liveness";
    }
}
