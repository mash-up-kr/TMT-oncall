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

    private static final Instant DETECTED_AT = Instant.parse("2026-09-04T15:30:00Z");

    private final OncallProperties properties = TestProperties.defaults();

    private static SentryIssue issue() {
        return new SentryIssue("4501", "TMT-BE-7", "NullPointerException",
                "com.tmt.menu.MenuService.findById", "error", "new",
                "https://sentry.io/organizations/test-org/issues/4501/", 34,
                Instant.parse("2026-09-04T10:00:00Z"), Instant.parse("2026-09-04T15:20:00Z"));
    }

    private ServiceDownDetected down(ServiceDownDetected.Kind kind, String detail,
                                     String body, List<SentryIssue> issues) {
        return new ServiceDownDetected(properties.target(), kind, detail,
                Duration.ofMinutes(3), DETECTED_AT, body, issues);
    }

    @Test
    void 응답이_없으면_지속_시간과_오류를_그대로_알린다() {
        ReportEmbed embed = IncidentReports.down(down(ServiceDownDetected.Kind.UNREACHABLE,
                "Connection refused", "", List.of(issue())));

        assertThat(embed.title()).isEqualTo("🚨 서비스 다운 — tmt-be");
        assertThat(embed.description()).isEqualTo("3분간 응답이 없습니다.");
        assertThat(embed.color()).isEqualTo(ReportColor.RED);
        assertThat(embed.fields()).extracting(ReportEmbed.Field::name)
                .containsExactly("대상", "오류", "확인 시각", "최근 Sentry 이슈");
        assertThat(fieldValue(embed, "대상")).isEqualTo("`http://localhost:9/actuator/health`");
        assertThat(fieldValue(embed, "오류")).isEqualTo("`Connection refused`");
        assertThat(fieldValue(embed, "확인 시각")).isEqualTo("`2026-09-05 00:30:00`");
        assertThat(fieldValue(embed, "최근 Sentry 이슈"))
                .isEqualTo("`TMT-BE-7` NullPointerException (34회)\n"
                        + "https://sentry.io/organizations/test-org/issues/4501/");
    }

    @Test
    void 의존성_장애는_DOWN인_컴포넌트를_뽑아_알린다() {
        String body = """
                {"status":"DOWN","components":{"db":{"status":"DOWN"},"diskSpace":{"status":"UP"}}}""";

        ReportEmbed embed = IncidentReports.down(
                down(ServiceDownDetected.Kind.DEGRADED, "503", body, List.of()));

        assertThat(embed.title()).isEqualTo("⚠️ 서비스 이상 — tmt-be");
        assertThat(embed.description())
                .isEqualTo("앱은 응답하지만 상태가 DOWN입니다. 인프라 원인이므로 자동 수정을 제안하지 않습니다.");
        assertThat(embed.color()).isEqualTo(ReportColor.ORANGE);
        assertThat(fieldValue(embed, "DOWN 컴포넌트")).isEqualTo("`db`");
    }

    /** 헬스 응답 형식이 달라도 다운 자체는 알려야 한다. */
    @Test
    void 헬스_본문을_읽지_못해도_리포트는_나간다() {
        ReportEmbed embed = IncidentReports.down(
                down(ServiceDownDetected.Kind.DEGRADED, "503", "<html>502 Bad Gateway</html>", List.of()));

        assertThat(embed.fields()).extracting(ReportEmbed.Field::name).containsExactly("대상", "확인 시각");
    }

    /** 인프라 원인에 'PR 만들기'를 붙이면 고칠 코드가 없는 수정을 사람이 승인하게 된다. */
    @Test
    void 의존성_장애에는_버튼을_붙이지_않는다() {
        assertThat(IncidentReports.buttonsFor(
                down(ServiceDownDetected.Kind.DEGRADED, "503", "", List.of()))).isEmpty();
        assertThat(IncidentReports.buttonsFor(
                down(ServiceDownDetected.Kind.UNREACHABLE, "refused", "", List.of())))
                .containsExactly(ReportButton.REANALYZE, ReportButton.IGNORE);
    }

    @Test
    void 복구는_다운_지속_시간과_함께_알린다() {
        ReportEmbed embed = IncidentReports.recovered(
                new ServiceRecovered(properties.target(), Duration.ofSeconds(3723), DETECTED_AT));

        assertThat(embed.title()).isEqualTo("✅ 복구 — tmt-be");
        assertThat(embed.description()).isEqualTo("1시간 2분 3초 만에 정상 응답으로 돌아왔습니다.");
        assertThat(embed.color()).isEqualTo(ReportColor.GREEN);
        assertThat(fieldValue(embed, "확인 시각")).isEqualTo("`2026-09-05 00:30:00`");
    }

    @Test
    void 지속_시간은_0에_가까워도_초로_읽힌다() {
        assertThat(IncidentReports.humanize(Duration.ZERO)).isEqualTo("0초");
        assertThat(IncidentReports.humanize(Duration.ofMinutes(5))).isEqualTo("5분");
        assertThat(IncidentReports.humanize(Duration.ofHours(2))).isEqualTo("2시간");
    }

    /** 스택이 위에 오면 원인·수정 계획이 밀려 읽히지 않는다. */
    @Test
    void 에러_리포트는_원인부터_놓고_스택을_임베드에_넣지_않는다() {
        ReportEmbed embed = IncidentReports.incident(
                new IncidentDetected(properties.target(), issue(), "{}"), analysis());

        assertThat(embed.title()).isEqualTo("🚨 에러 리포트 — tmt-be");
        assertThat(embed.description()).isEqualTo(
                "`NullPointerException`이(가) 34회 발생했습니다.\n`com.tmt.menu.MenuService.findById`");
        assertThat(embed.fields()).extracting(ReportEmbed.Field::name)
                .containsExactly("원인", "수정 계획", "영향 범위", "관련 배포", "Sentry", "최근 발생");
        assertThat(fieldValue(embed, "수정 계획")).isEqualTo("""
                1. MenuService.findById에서 orElseThrow(MenuNotFoundException::new)로 바꿉니다
                2. MenuNotFoundException을 404로 매핑하는 핸들러를 추가합니다""");
        assertThat(embed.fields()).noneMatch(field -> field.value().contains("MenuService.kt:42"));
    }

    @Test
    void 스택은_코드_블록_메시지로_따로_나간다() {
        String message = IncidentReports.codeBlock("스택", analysis().stackExcerpt());

        assertThat(message).isEqualTo("""
                **스택**
                ```
                java.lang.NullPointerException: null
                    at com.tmt.menu.MenuService.findById(MenuService.kt:42)
                ```""");
    }

    @Test
    void 코드로_고칠_수_없으면_PR_버튼을_빼고_보낸다() {
        Analysis analysis = new Analysis("외부 결제 API가 502를 반환합니다", List.of(),
                "결제 경로 전체", "관련 배포 없음", "HttpServerErrorException: 502", false);

        assertThat(IncidentReports.buttonsFor(analysis))
                .containsExactly(ReportButton.REANALYZE, ReportButton.IGNORE);
    }

    /** 실패 원인이 다르면 사람이 할 일도 다르다 — 문구를 섞지 않는다. */
    @Test
    void 티켓_실패와_빌드_실패는_다른_문구로_알린다() {
        ReportEmbed ticket = IncidentReports.ticketFailed("티켓을 만들지 못했다: 504 Gateway Timeout");
        assertThat(ticket.title()).isEqualTo("⚠️ 티켓 생성 실패");
        // 사유는 이미 온전한 문장이라 접두사를 덧붙이지 않는다
        assertThat(ticket.description()).isEqualTo("티켓을 만들지 못했다: 504 Gateway Timeout");
        assertThat(fieldValue(ticket, "진행"))
                .isEqualTo("수정과 PR은 그대로 진행합니다. 재시도하거나 Jira 토큰 확인이 필요합니다.");

        ReportEmbed build = IncidentReports.buildFailed("./gradlew build");
        assertThat(build.title()).isEqualTo("⚠️ 자동 수정 실패");
        assertThat(build.description())
                .isEqualTo("분석은 마쳤지만 `./gradlew build`가 실패해 PR을 올리지 않았습니다.");
    }

    /** 한도를 넘기면 전송이 통째로 거절된다 — 장애 알림이 길이 때문에 사라지면 안 된다. */
    @Test
    void 임베드_한도를_넘으면_자르고_말줄임표를_붙인다() {
        ReportEmbed embed = new ReportEmbed("제".repeat(300), "설".repeat(5000),
                List.of(new ReportEmbed.Field("이름", "값".repeat(2000), false)),
                ReportColor.RED, DETECTED_AT);

        assertThat(embed.title()).hasSize(ReportEmbed.TITLE_LIMIT).endsWith("…");
        assertThat(embed.description()).hasSize(ReportEmbed.DESCRIPTION_LIMIT).endsWith("…");
        assertThat(embed.fields().getFirst().value()).hasSize(ReportEmbed.FIELD_VALUE_LIMIT).endsWith("…");
    }

    @Test
    void 전체_한도를_넘는_필드는_뒤에서부터_버린다() {
        ReportEmbed embed = new ReportEmbed("제목", "설명",
                List.of(new ReportEmbed.Field("첫째", "값".repeat(1024), false),
                        new ReportEmbed.Field("둘째", "값".repeat(1024), false),
                        new ReportEmbed.Field("셋째", "값".repeat(1024), false),
                        new ReportEmbed.Field("넷째", "값".repeat(1024), false),
                        new ReportEmbed.Field("다섯째", "값".repeat(1024), false),
                        new ReportEmbed.Field("여섯째", "값".repeat(1024), false)),
                ReportColor.RED, DETECTED_AT);

        assertThat(embed.fields()).extracting(ReportEmbed.Field::name)
                .containsExactly("첫째", "둘째", "셋째", "넷째", "다섯째");
    }

    private static Analysis analysis() {
        return new Analysis(
                "MenuRepository.findById가 빈 Optional을 반환하는데 get()으로 바로 꺼냅니다.",
                List.of("MenuService.findById에서 orElseThrow(MenuNotFoundException::new)로 바꿉니다",
                        "MenuNotFoundException을 404로 매핑하는 핸들러를 추가합니다"),
                "API 응답 계약 변경 없음. 404 응답이 새로 나갈 수 있습니다.",
                "3시간 전 머지된 a1b2c3d (메뉴 조회 캐시 도입)",
                """
                        java.lang.NullPointerException: null
                            at com.tmt.menu.MenuService.findById(MenuService.kt:42)""",
                true);
    }

    private static String fieldValue(ReportEmbed embed, String name) {
        return embed.fields().stream()
                .filter(field -> field.name().equals(name))
                .map(ReportEmbed.Field::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("필드가 없다: " + name));
    }
}
