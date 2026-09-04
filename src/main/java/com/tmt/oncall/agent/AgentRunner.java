package com.tmt.oncall.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.Usage;
import com.tmt.oncall.guard.CallBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * 에이전트 CLI를 헤드리스로 실행한다. 경로별 모델을 지정하고, 응답의 {@code usage}를
 * {@link CallBudget}에 넘겨 비용을 누적한다.
 *
 * <p>
 * 자격 증명은 설정 객체에 바인딩하지 않고 여기서 환경변수를 직접 읽어 하위 프로세스로 옮긴다 —
 * 우리가 쓰지 않고 넘기기만 하는 값이라 설정에 담으면 로그·덤프로 흘릴 위험만 는다.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** CLI가 읽는 이름. */
    static final String CLI_API_KEY_ENV = "ANTHROPIC_API_KEY";

    /** 봇이 쓸 키. 종량제일 때만 설정한다. */
    static final String ONCALL_API_KEY_ENV = "ONCALL_AGENT_API_KEY";

    private final OncallProperties properties;
    private final CallBudget budget;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    @Autowired
    AgentRunner(OncallProperties properties, CallBudget budget, ObjectMapper objectMapper) {
        this(properties, budget, objectMapper, System::getenv);
    }

    AgentRunner(OncallProperties properties, CallBudget budget, ObjectMapper objectMapper,
                UnaryOperator<String> environment) {
        this.properties = properties;
        this.budget = budget;
        this.objectMapper = objectMapper;
        this.apiKey = environment.apply(ONCALL_API_KEY_ENV);

        if (properties.agent().billing().usesApiKey() && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "종량제(api-key) 모드인데 %s가 없다. 키 없이 반쯤 뜬 채로 두지 않는다".formatted(ONCALL_API_KEY_ENV));
        }
    }

    public AgentResult run(AgentCall call) {
        CallBudget.Decision decision = budget.check(call.path());
        if (!decision.allowed()) {
            log.warn("{} 호출을 가드가 막았다 — {}", call.path(), decision.reason());
            return new AgentResult.Blocked(decision.reason());
        }

        OncallProperties.Agent.Model model = properties.agent().modelFor(call.path());
        Duration timeout = properties.agent().timeout();

        Process process;
        try {
            process = start(call, model);
        } catch (IOException e) {
            // 프로세스가 뜨지도 못했으므로 호출로 세지 않는다.
            return new AgentResult.Failed("에이전트 CLI를 실행하지 못했다: " + e.getMessage(), Usage.NONE);
        }

        Output output = drain(process, timeout);
        Usage usage = parseUsage(output.stdout());

        // 실패해도 토큰은 이미 나갔을 수 있고, 실패를 세지 않으면 무한 재시도가 상한을 그대로 통과한다.
        budget.record(call.path(), usage);

        if (output.timedOut()) {
            return new AgentResult.Failed(
                    "에이전트 호출이 %s를 넘겨 강제 종료했다".formatted(timeout), usage);
        }
        if (output.exitCode() != 0) {
            return new AgentResult.Failed(
                    "에이전트가 비정상 종료했다 (exit %d): %s".formatted(output.exitCode(), summarize(output.stderr())),
                    usage);
        }
        return parseResult(output.stdout(), usage);
    }

    private Process start(AgentCall call, OncallProperties.Agent.Model model) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                properties.agent().binary(),
                "-p", call.fullPrompt(),
                "--output-format", "json",
                "--model", model.id()));

        Path workingDirectory = call.workingDirectory();
        Files.createDirectories(workingDirectory);

        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        applyCredentials(builder.environment());

        log.info("{} 호출 — 모델={}, 스킬={}, 디렉터리={}",
                call.path(), model.id(), call.skill(), workingDirectory);
        return builder.start();
    }

    /**
     * 상속된 키를 두 모드 모두에서 먼저 지운다. 구독제로 설정해두어도 VM 환경에 키가 남아 있으면
     * 하위 프로세스가 그대로 물려받아 조용히 종량제로 청구된다.
     */
    private void applyCredentials(Map<String, String> environment) {
        environment.remove(CLI_API_KEY_ENV);
        if (properties.agent().billing().usesApiKey()) {
            environment.put(CLI_API_KEY_ENV, apiKey);
        }
    }

    private record Output(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    /** stdout·stderr를 동시에 읽는다. 한쪽만 읽으면 반대쪽 파이프 버퍼가 차서 프로세스가 멈춘다. */
    private Output drain(Process process, Duration timeout) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread out = readInBackground(process.getInputStream(), stdout);
        Thread err = readInBackground(process.getErrorStream(), stderr);

        boolean exited;
        try {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor();
            }
            out.join();
            err.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Output(-1, stdout.toString(), "호출이 인터럽트됐다", false);
        }
        return new Output(exited ? process.exitValue() : -1, stdout.toString(), stderr.toString(), !exited);
    }

    private Thread readInBackground(InputStream stream, StringBuilder sink) {
        return Thread.ofVirtual().start(() -> {
            try (stream) {
                sink.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.debug("에이전트 출력 스트림을 읽다 끊겼다", e);
            }
        });
    }

    private AgentResult parseResult(String stdout, Usage usage) {
        JsonNode root = readTree(stdout);
        if (root == null) {
            return new AgentResult.Failed("에이전트 응답을 JSON으로 읽지 못했다: " + summarize(stdout), usage);
        }
        if (root.path("is_error").asBoolean(false)) {
            return new AgentResult.Failed(
                    "에이전트가 오류를 반환했다: " + summarize(root.path("result").asString("")), usage);
        }
        String text = root.path("result").asString("");
        if (text.isBlank()) {
            return new AgentResult.Failed("에이전트 응답이 비어 있다", usage);
        }
        return new AgentResult.Ok(text, usage);
    }

    /**
     * 구독제 인증에서는 {@code usage} 필드가 다르게 나올 수 있다. 집계가 0이 되는 편이
     * 호출이 죽는 것보다 나으므로 없거나 모양이 달라도 깨지지 않게 읽는다.
     */
    private Usage parseUsage(String stdout) {
        JsonNode root = readTree(stdout);
        if (root == null) {
            return Usage.NONE;
        }
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || !usage.isObject()) {
            return Usage.NONE;
        }
        return new Usage(
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0));
    }

    private JsonNode readTree(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(stdout);
            return root.isObject() ? root : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "(출력 없음)";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }
}
