package com.tmt.oncall.notify;

import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.SentryIssue;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 이벤트를 채널에 올릴 문자열로 만든다. 전송(JDA)과 분리해 둬서 봇 토큰 없이 문구를 검증할 수 있다 —
 * 리포트에서 실제로 틀리는 것은 전송이 아니라 문구다.
 */
public final class IncidentReports {

    private IncidentReports() {
    }

    /** 다운 직전 이슈를 전부 실으면 본문이 스택으로 덮이므로 가장 최근 것만 보여준다. */
    private static final int RECENT_ISSUE_LIMIT = 3;

    public static String down(ServiceDownDetected event) {
        List<String> lines = new ArrayList<>();
        if (event.kind() == ServiceDownDetected.Kind.UNREACHABLE) {
            lines.add("🔴 **서비스 다운 — " + event.target().key() + "**");
            lines.add("헬스 체크에 응답이 없다. 프로세스가 죽었거나 네트워크가 끊긴 것으로 본다.");
        } else {
            lines.add("🟠 **서비스 이상 — " + event.target().key() + "**");
            lines.add("앱은 응답하는데 상태가 DOWN이다. 의존성(DB 등) 쪽 인프라 문제로 본다.");
        }
        lines.add("헬스 URL: " + event.target().healthUrl());
        lines.add("상세: " + event.detail());

        if (!event.recentIssues().isEmpty()) {
            lines.add("");
            lines.add("**다운 직전 Sentry 이슈**");
            event.recentIssues().stream()
                    .limit(RECENT_ISSUE_LIMIT)
                    .map(IncidentReports::issueLine)
                    .forEach(lines::add);
        }

        lines.add("");
        if (event.kind() == ServiceDownDetected.Kind.UNREACHABLE) {
            lines.add("원인 분석을 이어서 이 스레드에 올린다.");
        } else {
            lines.add("코드로 고칠 수 있는 원인이 아니라 자동 수정을 제안하지 않는다. 인프라를 확인해달라.");
        }
        return String.join("\n", lines);
    }

    public static String recovered(ServiceRecovered event) {
        return """
                🟢 **복구 — %s**
                %s 만에 정상 응답으로 돌아왔다.""".formatted(event.target().key(), humanize(event.downFor()));
    }

    public static String incident(IncidentDetected event, Analysis analysis) {
        SentryIssue issue = event.issue();
        List<String> lines = new ArrayList<>();
        lines.add("🚨 **에러 리포트 — " + event.target().key() + "**");
        lines.add("`" + issue.title() + "` — " + issue.culprit()
                + " (" + issue.count() + "회, level=" + issue.level() + ")");
        lines.add(issue.permalink());
        lines.add("");
        lines.add("**스택**");
        lines.add(codeBlock(analysis.stackExcerpt()));
        lines.add("**원인**");
        lines.add(analysis.cause());
        lines.add("");
        lines.add("**수정 계획**");
        if (analysis.fixPlan().isEmpty()) {
            lines.add("- 자동으로 고칠 수 있는 수정 계획을 세우지 못했다.");
        } else {
            for (int i = 0; i < analysis.fixPlan().size(); i++) {
                lines.add((i + 1) + ". " + analysis.fixPlan().get(i));
            }
        }
        lines.add("");
        lines.add("**영향 범위**");
        lines.add(analysis.impact());
        lines.add("");
        lines.add("**관련 배포**");
        lines.add(analysis.relatedDeploy());
        return String.join("\n", lines);
    }

    /** Jira가 죽어도 수정과 PR은 그대로 간다. 사람이 할 일은 티켓을 이어붙이는 것뿐이다. */
    public static String ticketFailed(String reason) {
        return """
                ⚠️ **티켓을 만들지 못했습니다 — Jira 쪽 문제로 보인다**
                사유: %s
                수정과 PR은 그대로 진행한다. 재시도하거나 Jira 토큰을 확인해달라.""".formatted(reason);
    }

    /** 빌드 실패는 우리 수정이 틀렸다는 뜻이라 티켓 실패와 사람이 할 행동이 다르다. */
    public static String buildFailed(String buildCommand, String logTail) {
        return """
                ⚠️ **분석은 됐지만 자동 수정에 실패했습니다**
                `%s`가 실패해 PR을 올리지 않았다. 수정이 틀렸다는 뜻이라 결과물을 내보내지 않는다.
                %s""".formatted(buildCommand, codeBlock(logTail));
    }

    /**
     * 다운 리포트에는 'PR 만들기'를 붙이지 않는다 — 아직 원인을 모르고, 인프라 원인이면
     * 고칠 코드 자체가 없다. 의존성 장애는 억제할 대상도 아니라 버튼을 아예 두지 않는다.
     */
    public static List<ReportButton> buttonsFor(ServiceDownDetected event) {
        return event.kind() == ServiceDownDetected.Kind.UNREACHABLE
                ? List.of(ReportButton.REANALYZE, ReportButton.IGNORE)
                : List.of();
    }

    public static List<ReportButton> buttonsFor(Analysis analysis) {
        return analysis.codeFixPossible()
                ? List.of(ReportButton.CREATE_PR, ReportButton.REANALYZE, ReportButton.IGNORE)
                : List.of(ReportButton.REANALYZE, ReportButton.IGNORE);
    }

    private static String issueLine(SentryIssue issue) {
        return "- [%s] %s — %s (%d회)\n  %s"
                .formatted(issue.shortId(), issue.title(), issue.culprit(), issue.count(), issue.permalink());
    }

    private static String codeBlock(String body) {
        return "```\n" + body.strip() + "\n```";
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
