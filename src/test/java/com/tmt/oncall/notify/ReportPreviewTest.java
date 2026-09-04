package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.SentryIssue;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 리포트가 실제 Discord에서 어떻게 보이는지 눈으로 확인하는 수동 경로. 임베드는 Discord가
 * 그리는 것이라 문자열 검증만으로는 색·필드 배치를 알 수 없다.
 *
 * <p>
 * 실행:
 * <pre>
 * set -a; source .env; set +a
 * ./gradlew test --tests '*ReportPreviewTest*' --rerun
 * </pre>
 * 봇 토큰이 없으면 건너뛰므로 평소 빌드에는 영향이 없다.
 */
@EnabledIfEnvironmentVariable(named = "DISCORD_BOT_TOKEN", matches = ".+")
class ReportPreviewTest {

    @Test
    void 샘플_리포트를_채널로_보낸다() {
        OncallProperties properties = previewProperties();
        OncallStore store = TestStore.create().store();
        DiscordConnection connection = new DiscordConnection(properties,
                List.of(new DiscordButtonListener(new ButtonHandler(store, noActions()))));
        connection.connect();
        DiscordNotifier notifier = new DiscordNotifier(new JdaDiscordGateway(provider(connection)), store);

        notifier.reportDown(down(ServiceDownDetected.Kind.UNREACHABLE));
        notifier.reportDown(down(ServiceDownDetected.Kind.DEGRADED));
        notifier.reportIncident(incident(), analysis());
        notifier.reportRecovered(new ServiceRecovered(properties.target(),
                Duration.ofMinutes(4).plusSeconds(12), Instant.now()));

        holdForButtonClicks();
    }

    /**
     * 버튼은 봇이 살아 있는 동안만 먹는다. 보내고 바로 끝내면 눌렀을 때 Discord가
     * "응답하지 않았어요"를 띄우므로, 눌러볼 시간만큼 프로세스를 붙잡아 둔다.
     */
    private void holdForButtonClicks() {
        long seconds = Long.parseLong(System.getenv().getOrDefault("PREVIEW_HOLD_SECONDS", "120"));
        if (seconds <= 0) {
            return;
        }
        System.out.println("버튼을 눌러볼 수 있게 " + seconds + "초 동안 연결을 유지합니다.");
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OncallProperties previewProperties() {
        OncallProperties base = TestProperties.defaults();
        Target target = base.target();
        Target preview = new Target(target.key(), target.repo(), target.workspace(), target.buildCommand(),
                target.jiraProjectKey(), target.sentryProjectSlug(),
                System.getenv().getOrDefault("TMT_HEALTH_URL", target.healthUrl()),
                target.healthPollInterval(), target.healthFailureThreshold(),
                System.getenv("DISCORD_ONCALL_CHANNEL_ID"));
        OncallProperties.Discord discord = new OncallProperties.Discord(
                System.getenv("DISCORD_BOT_TOKEN"), base.discord().roles());
        return new OncallProperties(base.enabled(), preview, discord, base.jira(), base.github(),
                base.sentry(), base.agent(), base.guard(), base.store());
    }

    private ServiceDownDetected down(ServiceDownDetected.Kind kind) {
        return new ServiceDownDetected(
                previewProperties().target(),
                kind,
                kind == ServiceDownDetected.Kind.UNREACHABLE
                        ? "I/O error on GET request: Connection refused"
                        : "503 Service Unavailable",
                Duration.ofMinutes(3),
                Instant.now(),
                """
                        {"status":"DOWN","components":{"db":{"status":"DOWN"},"ping":{"status":"UP"}}}
                        """,
                kind == ServiceDownDetected.Kind.UNREACHABLE ? List.of(sentryIssue()) : List.of());
    }

    private IncidentDetected incident() {
        return new IncidentDetected(previewProperties().target(), sentryIssue(), "{}");
    }

    private SentryIssue sentryIssue() {
        return new SentryIssue("4501", "TMT-BE-7", "NullPointerException",
                "com.tmt.menu.MenuService.findById", "error", "new",
                "https://sentry.io/organizations/tmt/issues/4501/", 34,
                Instant.now().minus(Duration.ofHours(2)), Instant.now());
    }

    private Analysis analysis() {
        return new Analysis(
                "메뉴 조회 시 삭제된 가게를 참조하면 `store`가 null로 돌아오는데 그대로 역참조합니다.",
                List.of("`MenuService.findById`에서 가게 조회 결과를 `Optional`로 받습니다.",
                        "가게가 없으면 `MenuNotFoundException`으로 404를 돌려줍니다."),
                "응답 계약 변경 없음. 스키마 변경 없음.",
                "`a1b2c3d` — 2026-09-04 18:20 배포",
                """
                        java.lang.NullPointerException: Cannot invoke "Store.getName()" because "store" is null
                            at com.tmt.menu.MenuService.findById(MenuService.java:42)
                            at com.tmt.menu.MenuController.get(MenuController.java:28)""",
                true);
    }

    /** 실행부(TMT-330)가 아직 없다. 미리보기는 버튼 모양만 보면 된다. */
    private static ObjectProvider<DiscordConnection> provider(DiscordConnection connection) {
        return new ObjectProvider<>() {
            @Override
            public DiscordConnection getObject() {
                return connection;
            }

            @Override
            public DiscordConnection getObject(Object... args) {
                return connection;
            }
        };
    }

    private static ObjectProvider<IncidentActions> noActions() {
        return new ObjectProvider<>() {
            @Override
            public IncidentActions getObject() {
                return null;
            }

            @Override
            public IncidentActions getObject(Object... args) {
                return null;
            }

            @Override
            public IncidentActions getIfAvailable() {
                return null;
            }

            @Override
            public IncidentActions getIfUnique() {
                return null;
            }
        };
    }
}
