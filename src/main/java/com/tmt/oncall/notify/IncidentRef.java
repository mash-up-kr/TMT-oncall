package com.tmt.oncall.notify;

/**
 * 리포트가 가리키는 건 하나. 스레드 묶기·억제·버튼 멱등성이 같은 키를 써야 해서
 * 저장소 키(source_key + external_id)를 그대로 값으로 들고 다닌다.
 */
public record IncidentRef(String sourceKey, String externalId) {

    public static final String SENTRY = "sentry";
    public static final String HEALTH = "health";

    public static IncidentRef sentry(String issueId) {
        return new IncidentRef(SENTRY, issueId);
    }

    /** 다운은 Sentry 이슈가 아니라 대상 서비스 자체가 키다. */
    public static IncidentRef health(String targetKey) {
        return new IncidentRef(HEALTH, targetKey);
    }
}
