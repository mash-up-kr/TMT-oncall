package com.tmt.oncall.trigger;

import com.tmt.oncall.config.Target;

import java.util.List;

/**
 * 대상 서비스가 다운으로 판정됐다. 앱이 죽으면 Sentry로 이벤트가 가지 않으므로
 * 이 경로가 유일한 감지 수단이다.
 *
 * @param recentIssues 다운 직전의 Sentry 이슈. 원인 추정의 출발점이 된다
 */
public record ServiceDownDetected(Target target, Kind kind, String detail, List<SentryIssue> recentIssues) {

    /**
     * 응답이 오는지로 나눈다. 앱이 죽은 것과 의존성만 죽은 것은 조치가 달라서,
     * 리포트에서 "코드 문제"와 "인프라 문제"를 가르는 근거가 된다.
     */
    public enum Kind {
        /** 응답 자체가 없다 — 프로세스가 죽었거나 네트워크가 끊겼다. */
        UNREACHABLE,
        /** 응답은 오는데 상태가 DOWN이다 — 앱은 떠 있고 의존성(DB 등)이 문제다. */
        DEGRADED
    }
}
