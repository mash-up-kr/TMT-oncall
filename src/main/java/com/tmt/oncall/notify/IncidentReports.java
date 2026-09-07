package com.tmt.oncall.notify;

import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.QuestionAsked;
import com.tmt.oncall.trigger.SentryIssue;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 이벤트를 채널에 올릴 임베드로 만든다. 전송(JDA)과 분리해 둬서 봇 토큰 없이 문구를 검증할 수 있다 —
 * 리포트에서 실제로 틀리는 것은 전송이 아니라 문구다.
 *
 * <p>
 * 추정은 여기서 하지 않는다. 확인된 사실만 쓰고 원인 판단은 분석 단계가 채운다.
 */
public final class IncidentReports {

    private IncidentReports() {
    }

    /** 다운 직전 이슈를 전부 실으면 필드 하나가 스택으로 덮이므로 가장 최근 것만 보여준다. */
    private static final int RECENT_ISSUE_LIMIT = 3;

    private static final DateTimeFormatter CHECKED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Actuator 기본 헬스 인디케이터 중 문장으로 옮길 수 있는 것들. */
    private static final Map<String, String> KNOWN_COMPONENTS = Map.of(
            "db", "데이터베이스 응답이 없습니다.",
            "diskSpace", "디스크 공간이 부족합니다.");

    public static ReportEmbed down(ServiceDownDetected event) {
        List<ReportEmbed.Field> fields = new ArrayList<>();
        fields.add(new ReportEmbed.Field("대상", code(event.target().healthUrl()), false));

        String title;
        String description;
        if (event.kind() == ServiceDownDetected.Kind.UNREACHABLE) {
            title = "🚨 서비스 다운";
            description = humanize(event.unresponsiveFor()) + "간 응답이 없습니다.";
            fields.add(new ReportEmbed.Field("오류", code(event.detail()), false));
        } else {
            title = "⚠️ 서비스 이상";
            List<String> components = downComponents(event.responseBody());
            description = degradedDescription(components);
            if (!components.isEmpty()) {
                fields.add(new ReportEmbed.Field("DOWN 컴포넌트",
                        code(String.join(", ", components)), true));
            }
        }
        fields.add(new ReportEmbed.Field("확인 시각", checkedAt(event.detectedAt()), true));

        if (!event.recentIssues().isEmpty()) {
            fields.add(new ReportEmbed.Field("최근 Sentry 이슈", recentIssues(event.recentIssues()), false));
        }

        ReportColor color = event.kind() == ServiceDownDetected.Kind.UNREACHABLE
                ? ReportColor.RED
                : ReportColor.ORANGE;
        return new ReportEmbed(title, description, fields, color, event.detectedAt());
    }

    public static ReportEmbed recovered(ServiceRecovered event) {
        return new ReportEmbed(
                "✅ 복구",
                humanize(event.downFor()) + " 만에 정상 응답으로 돌아왔습니다.",
                List.of(new ReportEmbed.Field("확인 시각", checkedAt(event.recoveredAt()), true)),
                ReportColor.GREEN,
                event.recoveredAt());
    }

    /** 스택은 임베드에 넣지 않는다. 위에 놓이면 원인·수정 계획이 밀려 읽히지 않는다. */
    public static ReportEmbed incident(IncidentDetected event, Analysis analysis) {
        SentryIssue issue = event.issue();
        List<ReportEmbed.Field> fields = List.of(
                new ReportEmbed.Field("원인", analysis.cause(), false),
                new ReportEmbed.Field("수정 계획", fixPlan(analysis), false),
                new ReportEmbed.Field("영향 범위", analysis.impact(), false),
                new ReportEmbed.Field("관련 배포", analysis.relatedDeploy(), false),
                new ReportEmbed.Field("Sentry", code(issue.permalink()), false),
                new ReportEmbed.Field("최근 발생", checkedAt(issue.lastSeen()), true));

        return new ReportEmbed(
                "🚨 에러 리포트",
                code(issue.title()) + "이(가) " + issue.count() + "회 발생했습니다.\n"
                        + code(issue.culprit()),
                fields,
                ReportColor.RED,
                issue.lastSeen());
    }

    /**
     * 질문 답변. 톤은 스킬이 이미 입혀 왔으므로 여기서는 문구를 손대지 않고 자리만 잡아준다.
     * 원 질문을 함께 실어 두면 스레드만 봐도 무엇에 대한 답인지 읽힌다.
     */
    public static ReportEmbed answer(QuestionAsked question, Answer answer) {
        List<ReportEmbed.Field> fields = new ArrayList<>();
        fields.add(new ReportEmbed.Field("질문", question.content(), false));
        if (!answer.fixPlan().isEmpty()) {
            fields.add(new ReportEmbed.Field("수정 계획", numbered(answer.fixPlan()), false));
        }
        return new ReportEmbed(
                "💬 " + question.authorName() + "님의 질문",
                answer.text(),
                fields,
                ReportColor.BLUE,
                Instant.now());
    }

    /** 질문 경로에서 실패는 사람이 다시 물으면 되는 일이라, 리포트가 아니라 한 줄로 알린다. */
    public static String answerFailed(String reason) {
        return "답변을 만들지 못했습니다 — " + reason;
    }

    /** Jira가 실패해도 수정과 PR은 그대로 간다. 남은 일은 사람이 티켓을 이어붙이는 것뿐이다. */
    public static ReportEmbed ticketFailed(String reason) {
        return new ReportEmbed(
                "⚠️ 티켓 생성 실패",
                reason,
                List.of(new ReportEmbed.Field("진행",
                        "수정과 PR은 그대로 진행합니다. 재시도하거나 Jira 토큰 확인이 필요합니다.", false)),
                ReportColor.ORANGE,
                Instant.now());
    }

    /** 빌드 실패는 수정이 틀렸다는 뜻이라 티켓 실패와 사람이 할 일이 다르다. */
    public static ReportEmbed buildFailed(String buildCommand) {
        return new ReportEmbed(
                "⚠️ 자동 수정 실패",
                "분석은 마쳤지만 " + code(buildCommand) + "가 실패해 PR을 올리지 않았습니다.",
                List.of(new ReportEmbed.Field("진행",
                        "수정이 올바르지 않다는 뜻이므로 결과물을 내보내지 않습니다. 확인이 필요합니다.", false)),
                ReportColor.ORANGE,
                Instant.now());
    }

    /** 스택·빌드 로그처럼 긴 것은 임베드가 아니라 스레드에 코드 블록 메시지로 따로 보낸다. */
    public static String codeBlock(String heading, String body) {
        return "**" + heading + "**\n```\n" + body.strip() + "\n```";
    }

    /**
     * 답변만 하고 끝나는 질문에는 버튼을 붙이지 않는다 — 티켓을 만들지 않는 경로라 누를 것이 없고,
     * 재분석은 이 스레드에 힌트를 남기면 그대로 다시 돈다.
     */
    public static List<ReportButton> buttonsFor(Answer answer) {
        return answer.codeFixNeeded() ? List.of(ReportButton.CREATE_PR) : List.of();
    }

    public static List<ReportButton> buttonsFor(Analysis analysis) {
        return analysis.codeFixPossible()
                ? List.of(ReportButton.CREATE_PR, ReportButton.REANALYZE, ReportButton.IGNORE)
                : List.of(ReportButton.REANALYZE, ReportButton.IGNORE);
    }

    private static String fixPlan(Analysis analysis) {
        return analysis.fixPlan().isEmpty()
                ? "수정 계획을 세우지 못했습니다."
                : numbered(analysis.fixPlan());
    }

    private static String numbered(List<String> steps) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            lines.add((i + 1) + ". " + steps.get(i));
        }
        return String.join("\n", lines);
    }

    private static String recentIssues(List<SentryIssue> issues) {
        return issues.stream()
                .limit(RECENT_ISSUE_LIMIT)
                .map(issue -> "%s %s (%d회)\n%s"
                        .formatted(code(issue.shortId()), issue.title(), issue.count(), issue.permalink()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * 헬스 응답에서 UP이 아닌 컴포넌트 이름을 뽑는다. 형식이 예상과 달라도 리포트 자체는
     * 나가야 하므로 실패는 값이 없는 것으로 다룬다.
     */
    private static List<String> downComponents(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readTree(responseBody).path("components").properties().stream()
                    .filter(entry -> !"UP".equals(entry.getValue().path("status").asString("")))
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (JacksonException e) {
            return List.of();
        }
    }

    /**
     * 아는 컴포넌트만 사람이 읽는 말로 옮기고, 모르는 이름은 그대로 쓴다 —
     * 이름을 보고 무엇이 죽었는지 지어내면 사람이 엉뚱한 곳을 본다.
     */
    private static String degradedDescription(List<String> components) {
        if (components.isEmpty()) {
            return "앱은 응답하지만 상태가 DOWN입니다.";
        }
        return components.stream()
                .map(name -> KNOWN_COMPONENTS.getOrDefault(name, name + " 컴포넌트가 DOWN입니다."))
                .collect(Collectors.joining(" "));
    }

    private static String checkedAt(Instant instant) {
        return code(CHECKED_AT.format(instant));
    }

    /** 기계가 읽는 값(URL·명령어·오류 코드)은 인라인 코드로 감싸 눈으로 구분되게 한다. */
    private static String code(String value) {
        return "`" + value + "`";
    }

    static String humanize(Duration duration) {
        long seconds = Math.max(duration.getSeconds(), 0);
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remaining = seconds % 60;

        List<String> parts = new ArrayList<>();
        if (hours > 0) {
            parts.add(hours + "시간");
        }
        if (minutes > 0) {
            parts.add(minutes + "분");
        }
        if (remaining > 0 || parts.isEmpty()) {
            parts.add(remaining + "초");
        }
        return String.join(" ", parts);
    }
}
