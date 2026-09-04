package com.tmt.oncall.support;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.core.CallPath;

import java.time.Duration;
import java.util.Map;

/**
 * 테스트용 설정. yml을 읽지 않고 값을 직접 세워, 필드가 늘면 컴파일이 깨져 갱신을 강제한다.
 * yml과 record가 실제로 맞물리는지는 컨텍스트를 띄우는 {@code OncallApplicationTests}가 본다.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static OncallProperties defaults() {
        return new OncallProperties(
                true,
                new Target("tmt-be", "mash-up-kr/TMT-BE", "build/test-workspace", "./gradlew build",
                        "TMT", "tmt-be", "http://localhost:0/actuator/health", "1"),
                new OncallProperties.Discord("test-bot-token",
                        new OncallProperties.Discord.Roles("100", "200", "300")),
                new OncallProperties.Jira("https://ttalkkak.atlassian.net", "test@example.com", "test-jira-token"),
                new OncallProperties.Github("test-github-token"),
                new OncallProperties.Sentry("https://sentry.io", "test-sentry-token", "test-org",
                        Duration.ofMinutes(1)),
                agent(BillingMode.API_KEY, Duration.ofMinutes(10), "claude"),
                new OncallProperties.Guard(1000, 1000, 7.0, Duration.ofMinutes(30)),
                new OncallProperties.Store("build/test-store/unused.db"));
    }

    public static OncallProperties.Agent agent(BillingMode billing, Duration timeout, String binary) {
        return new OncallProperties.Agent(binary, timeout, billing, Map.of(
                CallPath.TRIAGE, new OncallProperties.Agent.Model("claude-haiku-4-5", 1.00, 5.00),
                CallPath.ANALYZE, new OncallProperties.Agent.Model("claude-sonnet-5", 2.00, 10.00),
                CallPath.FIX, new OncallProperties.Agent.Model("claude-opus-5", 5.00, 25.00)));
    }

    public static OncallProperties withAgent(OncallProperties base, OncallProperties.Agent agent) {
        return new OncallProperties(base.enabled(), base.target(), base.discord(), base.jira(),
                base.github(), base.sentry(), agent, base.guard(), base.store());
    }

    public static OncallProperties withGuard(OncallProperties base, OncallProperties.Guard guard) {
        return new OncallProperties(base.enabled(), base.target(), base.discord(), base.jira(),
                base.github(), base.sentry(), base.agent(), guard, base.store());
    }
}
