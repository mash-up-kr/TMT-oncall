package com.tmt.oncall.trigger;

import java.time.Instant;

/**
 * Sentry가 그루핑한 이슈 하나. 봇의 중복 억제 키는 {@code id}다 — 같은 예외의 반복 집계는
 * Sentry가 이미 했으므로 봇이 다시 세지 않는다.
 *
 * @param substatus 신규·재발 판정 근거. Sentry가 계산해 내려준다
 */
public record SentryIssue(
        String id,
        String shortId,
        String title,
        String culprit,
        String level,
        String substatus,
        String permalink,
        long count,
        Instant firstSeen,
        Instant lastSeen) {

    /**
     * 조치 대상은 새로 생겼거나 해결된 줄 알았는데 다시 터진 건이다. 계속 나던 에러(ongoing)는
     * 이미 한 번 알렸으므로 다시 알리지 않는다.
     */
    public boolean isNewOrRegressed() {
        return "new".equals(substatus) || "regressed".equals(substatus);
    }
}
