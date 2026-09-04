package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.guard.KillSwitch;
import com.tmt.oncall.store.OncallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Sentry API를 주기 폴링해 신규·재발 이슈를 감지한다. 봇이 앱 서버와 분리돼 있어
 * 앱이 죽어도 알릴 수 있고, 인바운드 엔드포인트를 열지 않아도 된다.
 */
@Component
public class SentryWatcher {

    private static final Logger log = LoggerFactory.getLogger(SentryWatcher.class);

    /** 처리 이력·억제 목록에서 Sentry 이슈를 가리키는 키. */
    public static final String SOURCE_KEY = "sentry";

    /** 한 번에 훑는 이슈 수. 폴링 간격이 1분이라 이보다 많이 밀릴 일이 없다. */
    private static final int PAGE_SIZE = 25;

    private final OncallProperties properties;
    private final SentryClient client;
    private final OncallStore store;
    private final KillSwitch killSwitch;
    private final ApplicationEventPublisher events;

    SentryWatcher(OncallProperties properties, SentryClient client, OncallStore store,
                  KillSwitch killSwitch, ApplicationEventPublisher events) {
        this.properties = properties;
        this.client = client;
        this.store = store;
        this.killSwitch = killSwitch;
        this.events = events;
    }

    public void poll() {
        Target target = properties.target();
        if (!killSwitch.isEnabled() || !target.hasErrorTrigger()) {
            return;
        }

        List<SentryIssue> issues;
        try {
            issues = client.unresolvedIssues(
                    properties.sentry().orgSlug(), target.sentryProjectSlug(), PAGE_SIZE);
        } catch (RestClientException e) {
            log.error("Sentry 폴링에 실패했다: {}", e.getMessage());
            return;
        }

        Optional<Instant> cursor = cursor(target);
        if (cursor.isEmpty()) {
            // 첫 기동에서 밀린 이슈를 한꺼번에 알리면 채널이 잠긴다. 지금부터 새로 나는 것만 본다.
            advanceCursor(target, issues, Instant.now());
            log.info("Sentry 커서를 현재 시각으로 초기화했다 — 이전 이슈는 알리지 않는다");
            return;
        }

        issues.stream()
                .filter(issue -> issue.lastSeen().isAfter(cursor.get()))
                .sorted(Comparator.comparing(SentryIssue::lastSeen))
                .forEach(issue -> handle(target, issue));

        advanceCursor(target, issues, cursor.get());
    }

    private void handle(Target target, SentryIssue issue) {
        if (!issue.isNewOrRegressed()) {
            return;
        }
        if (store.isSuppressed(SOURCE_KEY, issue.id())) {
            return;
        }
        if (withinDuplicateWindow(issue)) {
            return;
        }

        // 분석 전에 먼저 기록한다. 뒤에서 실패하더라도 폴링마다 같은 건을 다시 띄우지 않는다.
        store.markProcessed(SOURCE_KEY, issue.id(), null);

        log.info("Sentry 이슈 감지 — {} [{}] {}", issue.shortId(), issue.substatus(), issue.title());
        events.publishEvent(new IncidentDetected(target, issue, client.latestEventJson(issue.id())));
    }

    private boolean withinDuplicateWindow(SentryIssue issue) {
        Duration window = properties.guard().duplicateWindow();
        return store.lastProcessedAt(SOURCE_KEY, issue.id())
                .map(processedAt -> processedAt.isAfter(Instant.now().minus(window)))
                .orElse(false);
    }

    private Optional<Instant> cursor(Target target) {
        return store.cursor(cursorName(target)).map(Instant::parse);
    }

    /** 커서는 훑은 이슈 전체의 최신 발생 시각으로 민다 — 걸러낸 이슈도 다시 볼 필요가 없다. */
    private void advanceCursor(Target target, List<SentryIssue> issues, Instant fallback) {
        Instant next = issues.stream()
                .map(SentryIssue::lastSeen)
                .max(Comparator.naturalOrder())
                .filter(fallback::isBefore)
                .orElse(fallback);
        store.saveCursor(cursorName(target), next.toString());
    }

    private static String cursorName(Target target) {
        return SOURCE_KEY + ":" + target.key();
    }
}
