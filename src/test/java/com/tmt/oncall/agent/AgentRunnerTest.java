package com.tmt.oncall.agent;

import tools.jackson.databind.ObjectMapper;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.guard.CallBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 CLI 대신 같은 계약(인자·JSON 출력)을 흉내내는 스크립트를 호출한다. 자격 증명 없이
 * CI에서 돌아야 하고, 프로세스 처리·usage 집계·환경 정리는 스크립트로도 그대로 검증된다.
 * 진짜 {@code claude} 호출은 VM 셋업 후 수동으로 한 번 확인한다.
 */
@SpringBootTest(properties = {
        "oncall.store.path=build/test-store/agent/state.db",
        "oncall.agent.billing=subscription",
        "oncall.guard.max-calls-per-hour=1000",
        "oncall.guard.max-calls-per-day=1000"
})
@ActiveProfiles("test")
class AgentRunnerTest {

    @Autowired
    OncallProperties properties;

    @Autowired
    CallBudget budget;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    Path workspace;

    @BeforeEach
    void reset() throws IOException {
        jdbc.sql("DELETE FROM call_log").update();
        workspace = Files.createTempDirectory("agent-runner-test");
    }

    @Test
    void 성공하면_응답과_usage를_돌려준다() throws IOException {
        AgentRunner runner = runner(okScript(), BillingMode.SUBSCRIPTION, name -> null);

        AgentResult result = runner.run(AgentCall.of(CallPath.ANALYZE, "incident-analyze", "스택 봐줘", workspace));

        assertThat(result).isInstanceOf(AgentResult.Ok.class);
        AgentResult.Ok ok = (AgentResult.Ok) result;
        assertThat(ok.text()).isEqualTo("분석 결과");
        assertThat(ok.usage().inputTokens()).isEqualTo(1000);
        assertThat(ok.usage().outputTokens()).isEqualTo(200);
        assertThat(ok.usage().cacheCreationTokens()).isEqualTo(50);
        assertThat(ok.usage().cacheReadTokens()).isEqualTo(10);
    }

    @Test
    void 경로에_맞는_모델과_스킬을_넘긴다() throws IOException {
        AgentRunner runner = runner(okScript(), BillingMode.SUBSCRIPTION, name -> null);

        runner.run(AgentCall.of(CallPath.TRIAGE, "incident-triage", "이 에러 조치 필요한가", workspace));

        assertThat(Files.readString(workspace.resolve("args.txt")))
                .contains("--model")
                .contains(properties.agent().modelFor(CallPath.TRIAGE).id())
                .contains("--output-format json");
        assertThat(Files.readString(workspace.resolve("prompt.txt")))
                .startsWith("/incident-triage")
                .endsWith("이 에러 조치 필요한가");
    }

    @Test
    void usage를_예산에_누적한다() throws IOException {
        AgentRunner runner = runner(okScript(), BillingMode.SUBSCRIPTION, name -> null);

        runner.run(AgentCall.of(CallPath.ANALYZE, null, "분석", workspace));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM call_log").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT input_tokens FROM call_log").query(Long.class).single()).isEqualTo(1000);
    }

    @Test
    void 비정상_종료는_실패로_보고하고_호출로_센다() throws IOException {
        AgentRunner runner = runner(failScript(), BillingMode.SUBSCRIPTION, name -> null);

        AgentResult result = runner.run(AgentCall.of(CallPath.ANALYZE, null, "분석", workspace));

        assertThat(result).isInstanceOf(AgentResult.Failed.class);
        assertThat(result.message()).contains("exit 2").contains("터졌다");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM call_log").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void 타임아웃이면_강제_종료하고_실패로_보고한다() throws IOException {
        AgentRunner runner = runner(hangScript(), BillingMode.SUBSCRIPTION, name -> null, Duration.ofMillis(300));

        AgentResult result = runner.run(AgentCall.of(CallPath.ANALYZE, null, "분석", workspace));

        assertThat(result).isInstanceOf(AgentResult.Failed.class);
        assertThat(result.message()).contains("강제 종료");
    }

    @Test
    void JSON이_아닌_출력은_실패로_본다() throws IOException {
        AgentRunner runner = runner(garbageScript(), BillingMode.SUBSCRIPTION, name -> null);

        AgentResult result = runner.run(AgentCall.of(CallPath.ANALYZE, null, "분석", workspace));

        assertThat(result).isInstanceOf(AgentResult.Failed.class);
        assertThat(result.message()).contains("JSON으로 읽지 못했다");
    }

    @Test
    void 구독제는_상속된_키를_지운다() throws IOException {
        AgentRunner runner = runner(okScript(), BillingMode.SUBSCRIPTION, name -> "상속된-키");

        runner.run(AgentCall.of(CallPath.ANALYZE, null, "분석", workspace));

        // 하위 프로세스에 키가 남아 있으면 구독 자격 증명을 가리고 조용히 종량제로 청구된다.
        assertThat(Files.readString(workspace.resolve("apikey.txt"))).isEqualTo("unset");
    }

    @Test
    void 종량제는_봇의_키를_CLI_이름으로_넘긴다() throws IOException {
        AgentRunner runner = runner(okScript(), BillingMode.API_KEY,
                name -> AgentRunner.ONCALL_API_KEY_ENV.equals(name) ? "봇-키" : null);

        runner.run(AgentCall.of(CallPath.ANALYZE, null, "분석", workspace));

        assertThat(Files.readString(workspace.resolve("apikey.txt"))).isEqualTo("봇-키");
    }

    @Test
    void 종량제인데_키가_없으면_기동에_실패한다() throws IOException {
        assertThatThrownBy(() -> runner(okScript(), BillingMode.API_KEY, name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AgentRunner.ONCALL_API_KEY_ENV);
    }

    // --- 도우미 ---

    private AgentRunner runner(Path binary, BillingMode billing, UnaryOperator<String> environment) {
        return runner(binary, billing, environment, properties.agent().timeout());
    }

    private AgentRunner runner(Path binary, BillingMode billing, UnaryOperator<String> environment,
                               Duration timeout) {
        OncallProperties.Agent agent = new OncallProperties.Agent(
                binary.toAbsolutePath().toString(), timeout, billing, properties.agent().models());
        OncallProperties withBinary = new OncallProperties(
                properties.enabled(), properties.target(), properties.discord(), properties.jira(),
                properties.github(), properties.sentry(), agent, properties.guard(), properties.store());
        return new AgentRunner(withBinary, budget, objectMapper, environment);
    }

    private Path okScript() throws IOException {
        return script("ok", """
                #!/bin/sh
                printf '%s' "$*" > args.txt
                printf '%s' "$2" > prompt.txt
                printf '%s' "${ANTHROPIC_API_KEY-unset}" > apikey.txt
                cat <<'JSON'
                {"type":"result","is_error":false,"result":"분석 결과",
                 "usage":{"input_tokens":1000,"output_tokens":200,
                          "cache_creation_input_tokens":50,"cache_read_input_tokens":10}}
                JSON
                """);
    }

    private Path failScript() throws IOException {
        return script("fail", """
                #!/bin/sh
                echo "빌드가 터졌다" >&2
                exit 2
                """);
    }

    private Path hangScript() throws IOException {
        return script("hang", """
                #!/bin/sh
                sleep 30
                """);
    }

    private Path garbageScript() throws IOException {
        return script("garbage", """
                #!/bin/sh
                echo "Usage: claude [options]"
                """);
    }

    private Path script(String name, String body) throws IOException {
        Path directory = Path.of("build", "test-bin");
        Files.createDirectories(directory);
        Path file = directory.resolve("fake-claude-" + name);
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }
}
