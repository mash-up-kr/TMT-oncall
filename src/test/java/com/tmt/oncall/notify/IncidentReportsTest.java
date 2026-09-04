package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.SentryIssue;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReportsTest {

    private final OncallProperties properties = TestProperties.defaults();

    private static SentryIssue issue() {
        return new SentryIssue("4501", "TMT-BE-7", "NullPointerException",
                "com.tmt.menu.MenuService.findById", "error", "new",
                "https://sentry.io/organizations/test-org/issues/4501/", 34,
                Instant.parse("2026-09-04T10:00:00Z"), Instant.parse("2026-09-04T10:30:00Z"));
    }

    @Test
    void 응답이_없는_다운은_프로세스_문제로_쓴다() {
        String report = IncidentReports.down(new ServiceDownDetected(
                properties.target(), ServiceDownDetected.Kind.UNREACHABLE,
                "연결할 수 없다: Connection refused", List.of(issue())));

        assertThat(report).isEqualTo("""
                🔴 **서비스 다운 — tmt-be**
                헬스 체크에 응답이 없다. 프로세스가 죽었거나 네트워크가 끊긴 것으로 본다.
                헬스 URL: http://localhost:9/actuator/health
                상세: 연결할 수 없다: Connection refused

                **다운 직전 Sentry 이슈**
                - [TMT-BE-7] NullPointerException — com.tmt.menu.MenuService.findById (34회)
                  https://sentry.io/organizations/test-org/issues/4501/

                원인 분석을 이어서 이 스레드에 올린다.""");
    }

    @Test
    void 의존성_장애는_인프라_문제로_쓰고_수정을_제안하지_않는다() {
        String report = IncidentReports.down(new ServiceDownDetected(
                properties.target(), ServiceDownDetected.Kind.DEGRADED,
                "status=DOWN, db=DOWN", List.of()));

        assertThat(report).isEqualTo("""
                🟠 **서비스 이상 — tmt-be**
                앱은 응답하는데 상태가 DOWN이다. 의존성(DB 등) 쪽 인프라 문제로 본다.
                헬스 URL: http://localhost:9/actuator/health
                상세: status=DOWN, db=DOWN

                코드로 고칠 수 있는 원인이 아니라 자동 수정을 제안하지 않는다. 인프라를 확인해달라.""");
    }

    /** 인프라 원인에 'PR 만들기'를 붙이면 고칠 코드가 없는 수정을 사람이 승인하게 된다. */
    @Test
    void 의존성_장애에는_버튼을_붙이지_않는다() {
        ServiceDownDetected degraded = new ServiceDownDetected(
                properties.target(), ServiceDownDetected.Kind.DEGRADED, "status=DOWN", List.of());
        ServiceDownDetected unreachable = new ServiceDownDetected(
                properties.target(), ServiceDownDetected.Kind.UNREACHABLE, "연결할 수 없다", List.of());

        assertThat(IncidentReports.buttonsFor(degraded)).isEmpty();
        assertThat(IncidentReports.buttonsFor(unreachable))
                .containsExactly(ReportButton.REANALYZE, ReportButton.IGNORE)
                .doesNotContain(ReportButton.CREATE_PR);
    }

    @Test
    void 복구는_다운_지속_시간과_함께_알린다() {
        String report = IncidentReports.recovered(
                new ServiceRecovered(properties.target(), Duration.ofSeconds(3723)));

        assertThat(report).isEqualTo("""
                🟢 **복구 — tmt-be**
                1시간 2분 3초 만에 정상 응답으로 돌아왔다.""");
    }

    @Test
    void 지속_시간은_0에_가까워도_초로_읽힌다() {
        assertThat(IncidentReports.humanize(Duration.ZERO)).isEqualTo("0초");
        assertThat(IncidentReports.humanize(Duration.ofMinutes(5))).isEqualTo("5분");
        assertThat(IncidentReports.humanize(Duration.ofHours(2))).isEqualTo("2시간");
    }

    @Test
    void 에러_리포트는_스택과_수정_계획을_그대로_싣는다() {
        Analysis analysis = new Analysis(
                "MenuRepository.findById가 빈 Optional을 반환하는데 get()으로 바로 꺼낸다.",
                List.of("MenuService.findById에서 orElseThrow(MenuNotFoundException::new)로 바꾼다",
                        "MenuNotFoundException을 404로 매핑하는 핸들러를 추가한다"),
                "API 응답 계약 변경 없음. 404 응답이 새로 나갈 수 있다.",
                "3시간 전 머지된 a1b2c3d (메뉴 조회 캐시 도입)",
                """
                        java.lang.NullPointerException: null
                            at com.tmt.menu.MenuService.findById(MenuService.kt:42)
                            at com.tmt.menu.MenuController.get(MenuController.kt:21)""",
                true);

        String report = IncidentReports.incident(
                new IncidentDetected(properties.target(), issue(), "{}"), analysis);

        assertThat(report).isEqualTo("""
                🚨 **에러 리포트 — tmt-be**
                `NullPointerException` — com.tmt.menu.MenuService.findById (34회, level=error)
                https://sentry.io/organizations/test-org/issues/4501/

                **스택**
                ```
                java.lang.NullPointerException: null
                    at com.tmt.menu.MenuService.findById(MenuService.kt:42)
                    at com.tmt.menu.MenuController.get(MenuController.kt:21)
                ```
                **원인**
                MenuRepository.findById가 빈 Optional을 반환하는데 get()으로 바로 꺼낸다.

                **수정 계획**
                1. MenuService.findById에서 orElseThrow(MenuNotFoundException::new)로 바꾼다
                2. MenuNotFoundException을 404로 매핑하는 핸들러를 추가한다

                **영향 범위**
                API 응답 계약 변경 없음. 404 응답이 새로 나갈 수 있다.

                **관련 배포**
                3시간 전 머지된 a1b2c3d (메뉴 조회 캐시 도입)""");
    }

    @Test
    void 코드로_고칠_수_없으면_PR_버튼을_빼고_보낸다() {
        Analysis analysis = new Analysis("외부 결제 API가 502를 낸다", List.of(),
                "결제 경로 전체", "관련 배포 없음", "HttpServerErrorException: 502", false);

        assertThat(IncidentReports.buttonsFor(analysis))
                .containsExactly(ReportButton.REANALYZE, ReportButton.IGNORE);
    }

    /** 실패 원인이 다르면 사람이 할 행동도 다르다 — 문구를 섞지 않는다. */
    @Test
    void 티켓_실패와_빌드_실패는_다른_문구로_알린다() {
        assertThat(IncidentReports.ticketFailed("504 Gateway Timeout")).isEqualTo("""
                ⚠️ **티켓을 만들지 못했습니다 — Jira 쪽 문제로 보인다**
                사유: 504 Gateway Timeout
                수정과 PR은 그대로 진행한다. 재시도하거나 Jira 토큰을 확인해달라.""");

        assertThat(IncidentReports.buildFailed("./gradlew build", "MenuService.kt:42 unresolved reference"))
                .isEqualTo("""
                        ⚠️ **분석은 됐지만 자동 수정에 실패했습니다**
                        `./gradlew build`가 실패해 PR을 올리지 않았다. 수정이 틀렸다는 뜻이라 결과물을 내보내지 않는다.
                        ```
                        MenuService.kt:42 unresolved reference
                        ```""");
    }
}
