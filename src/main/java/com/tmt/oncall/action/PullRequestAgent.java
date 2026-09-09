package com.tmt.oncall.action;

import com.tmt.oncall.agent.AgentCall;
import com.tmt.oncall.agent.AgentResult;
import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.CallPath;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 승인된 수정 계획을 전용 클론에서 실행해 PR까지 올린다. 머지는 하지 않는다.
 *
 * <p>
 * 운영 배포 소스를 건드리지 않으려고 {@code Target.workspacePath()} 클론에서만 돌고,
 * 베이스 브랜치로는 절대 push하지 않는다.
 */
@Component
public class PullRequestAgent {

    private static final Logger log = LoggerFactory.getLogger(PullRequestAgent.class);

    /** 코드를 고치는 규칙은 봇이 아니라 이 스킬이 갖는다. 브랜치·커밋·PR 이름은 여기서 짓는다. */
    private static final String FIX_SKILL = "tmt-fix-pr";

    private static final String BASE_BRANCH = "main";
    private static final String REMOTE = "origin";

    /**
     * 브랜치·커밋·PR 이름의 규칙은 TMT-BE의 {@code docs/BRANCHING.md}가 원본이다. 봇은 그
     * 문서를 읽지 않으므로 여기 옮겨 둔다 — 문서가 바뀌면 이 클래스도 같이 고쳐야 한다.
     *
     * <p>
     * 온콜이 만드는 것은 장애·문의 대응이라 타입은 늘 {@code fix}다. 다른 타입이 필요해지면
     * 분석 스킬이 타입을 내주게 하는 것이 맞지, 여기서 요약을 보고 짐작할 일이 아니다.
     */
    private static final String COMMIT_TYPE = "fix";

    /** 티켓을 못 만든 건의 브랜치 접두사. 실제 Jira 키와 부딪히지 않는다. */
    private static final String NO_TICKET_PREFIX = "oncall/";

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 헤드리스 VM에는 전역 git 신원이 없어 커밋 자체가 실패한다. 봇 커밋임이 로그에 드러나도록
     * 설정 파일에 남기지 않고 호출마다 붙인다. 주소는 GitHub noreply 형식을 쓰되 특정 사람
     * 계정에는 묶지 않는다.
     */
    private static final List<String> COMMIT_IDENTITY =
            List.of("-c", "user.name=tmt-oncall",
                    "-c", "user.email=tmt-oncall@users.noreply.github.com");

    private final OncallProperties properties;
    private final AgentRunner agentRunner;
    private final String ghBinary;

    @Autowired
    PullRequestAgent(OncallProperties properties, AgentRunner agentRunner) {
        this(properties, agentRunner, "gh");
    }

    /** 테스트가 {@code gh} 대역을 끼워 넣는 자리. git과 빌드는 진짜를 그대로 쓴다. */
    PullRequestAgent(OncallProperties properties, AgentRunner agentRunner, String ghBinary) {
        this.properties = properties;
        this.agentRunner = agentRunner;
        this.ghBinary = ghBinary;
    }

    public PullRequestResult open(PullRequestRequest request) {
        Path workspace = request.target().workspacePath();
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return new PullRequestResult.Failed("전용 클론이 없다: " + workspace);
        }

        String branch = branchName(request);
        if (BASE_BRANCH.equals(branch)) {
            return new PullRequestResult.Failed("브랜치 이름이 베이스와 같아 진행하지 않는다");
        }

        PullRequestResult prepared = prepareBranch(workspace, branch);
        if (prepared != null) {
            return prepared;
        }

        AgentResult fixed = agentRunner.run(AgentCall.of(CallPath.FIX, FIX_SKILL, prompt(request), workspace));
        if (!fixed.isOk()) {
            return new PullRequestResult.Failed("수정 에이전트가 끝내지 못했다: " + fixed.message());
        }

        Command status = git(workspace, List.of("status", "--porcelain"), GIT_TIMEOUT);
        if (!status.succeeded()) {
            return new PullRequestResult.Failed("변경 사항을 확인하지 못했다: " + status.describe());
        }
        if (status.stdout().isBlank()) {
            // 스킬은 고칠 수 없으면 무엇이 막았는지 적고 끝내라고 지시한다. 그 설명을 버리면
            // 호출료를 다 치르고도 왜 안 고쳤는지 아무도 알 수 없다.
            log.warn("에이전트가 파일을 고치지 않았다 — 브랜치={}, 보고={}", branch, fixed.message());
            return new PullRequestResult.Failed(
                    "에이전트가 아무것도 고치지 않았습니다. 에이전트 보고:%n%n%s".formatted(fixed.message()));
        }

        PullRequestResult committed = commit(workspace, request);
        if (committed != null) {
            return committed;
        }

        Command build = build(workspace, request);
        if (!build.succeeded()) {
            log.warn("빌드가 실패해 PR을 올리지 않는다 — 브랜치={}", branch);
            return new PullRequestResult.BuildFailed(branch, build.describe());
        }

        Command push = git(workspace, List.of("push", "--set-upstream", REMOTE, branch), GIT_TIMEOUT);
        if (!push.succeeded()) {
            return new PullRequestResult.Failed("브랜치를 올리지 못했다: " + push.describe());
        }

        return createPullRequest(workspace, request, branch);
    }

    /**
     * 티켓이 있으면 {@code fix/TMT-401} — 규칙이 {@code <type>/<Jira키>}이고 설명 suffix를
     * 금지한다. 작업 내용은 티켓 제목이 말한다.
     *
     * <p>
     * 티켓을 못 만든 건은 {@code oncall/...}로 간다. 규칙은 키 없는 브랜치를 금지하지만,
     * 여기서 멈추면 승인된 수정이 통째로 사라진다 — 사람이 사후에 티켓을 붙이는 편이 낫다.
     * 뒤의 해시는 같은 증상으로 다시 승인했을 때 남아 있는 브랜치와 부딪히지 않게 하는 값이다.
     */
    String branchName(PullRequestRequest request) {
        return request.hasTicket()
                ? COMMIT_TYPE + "/" + request.ticketKey()
                : NO_TICKET_PREFIX + slug(request.summary()) + "-" + fingerprint(request.summary());
    }

    private String slug(String summary) {
        String slug = summary.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        // 한글만으로 된 요약은 슬러그가 통째로 비므로 브랜치 이름이 깨진다.
        return slug.isBlank() ? "fix" : slug;
    }

    private String fingerprint(String summary) {
        return Integer.toHexString(summary.hashCode() & 0xFFFFFF);
    }

    /** 남아 있는 이전 작업물이 섞이지 않도록 원격 베이스에서 브랜치를 새로 딴다. */
    private PullRequestResult prepareBranch(Path workspace, String branch) {
        Command fetch = git(workspace, List.of("fetch", REMOTE, BASE_BRANCH), GIT_TIMEOUT);
        if (!fetch.succeeded()) {
            return new PullRequestResult.Failed("원격을 가져오지 못했다: " + fetch.describe());
        }
        Command checkout = git(workspace,
                List.of("checkout", "-B", branch, REMOTE + "/" + BASE_BRANCH), GIT_TIMEOUT);
        if (!checkout.succeeded()) {
            return new PullRequestResult.Failed("브랜치를 만들지 못했다: " + checkout.describe());
        }
        return null;
    }

    /**
     * 커밋 메시지는 제목만 남긴다 — 트레일러·서명 금지가 TMT-BE 규칙이라 본문을 아예 만들지 않는다.
     */
    private PullRequestResult commit(Path workspace, PullRequestRequest request) {
        Command add = git(workspace, List.of("add", "--all"), GIT_TIMEOUT);
        if (!add.succeeded()) {
            return new PullRequestResult.Failed("변경 사항을 담지 못했다: " + add.describe());
        }
        List<String> commit = new ArrayList<>(COMMIT_IDENTITY);
        commit.addAll(List.of("commit", "-m", commitTitle(request)));
        Command committed = git(workspace, commit, GIT_TIMEOUT);
        if (!committed.succeeded()) {
            return new PullRequestResult.Failed("커밋하지 못했다: " + committed.describe());
        }
        return null;
    }

    /** Conventional Commits — {@code type: 제목}. 티켓 키는 PR 제목이 들고 간다. */
    String commitTitle(PullRequestRequest request) {
        return "%s: %s".formatted(COMMIT_TYPE, request.summary());
    }

    private Command build(Path workspace, PullRequestRequest request) {
        List<String> command = new ArrayList<>(List.of(request.target().buildCommand().trim().split("\\s+")));
        // ProcessBuilder는 상대 실행 파일을 작업 디렉터리가 아니라 JVM의 cwd에서 찾는다.
        // `./gradlew`는 그대로 넘기면 클론이 아닌 봇 실행 위치를 뒤진다.
        if (command.getFirst().startsWith("./")) {
            command.set(0, workspace.resolve(command.getFirst().substring(2)).toAbsolutePath().toString());
        }
        return run(command, workspace, BUILD_TIMEOUT, Map.of());
    }

    private PullRequestResult createPullRequest(Path workspace, PullRequestRequest request, String branch) {
        Command created = run(List.of(ghBinary, "pr", "create",
                        "--base", BASE_BRANCH,
                        "--head", branch,
                        "--title", pullRequestTitle(request),
                        "--body", body(request)),
                workspace, GIT_TIMEOUT,
                // gh는 이 이름으로만 토큰을 읽는다. VM의 로그인 상태에 기대지 않는다.
                Map.of("GH_TOKEN", properties.github().token()));
        if (!created.succeeded()) {
            return new PullRequestResult.Failed("PR을 올리지 못했다: " + created.describe());
        }
        String url = created.stdout().strip().lines()
                .filter(line -> line.startsWith("http"))
                .reduce((first, last) -> last)
                .orElse("");
        if (url.isBlank()) {
            return new PullRequestResult.Failed("gh 출력에서 PR 주소를 찾지 못했다: " + created.describe());
        }
        log.info("PR 생성 — {} ({} 요청)", url, request.requestedBy());
        return new PullRequestResult.Created(branch, url, request.hasTicket());
    }

    /**
     * squash 머지라 PR 제목이 그대로 main의 커밋 메시지가 된다 — 규칙을 지켜야 하는 것은
     * 브랜치 안의 커밋이 아니라 이 줄이다.
     */
    String pullRequestTitle(PullRequestRequest request) {
        return request.hasTicket()
                ? "[%s] %s: %s".formatted(request.ticketKey(), COMMIT_TYPE, request.summary())
                : "[온콜] %s: %s (티켓 미생성)".formatted(COMMIT_TYPE, request.summary());
    }

    private String body(PullRequestRequest request) {
        String ticketLine = request.hasTicket()
                ? "티켓: " + request.ticketKey()
                : "티켓을 만들지 못했습니다 — 확인 후 링크해주세요.";
        return """
                ## What

                %s

                ## Why

                %s

                ## Notes for Reviewer

                온콜 봇이 자동으로 만든 PR입니다. 요청자: %s
                %s
                """.formatted(request.summary(), request.plan(), request.requestedBy(), ticketLine);
    }

    private String prompt(PullRequestRequest request) {
        return """
                아래 수정 계획대로 이 저장소를 고쳐라. 커밋·PR은 만들지 말고 작업 트리만 고친다.

                제목: %s
                요청자: %s

                수정 계획
                %s
                """.formatted(request.summary(), request.requestedBy(), request.plan());
    }

    private Command git(Path workspace, List<String> arguments, Duration timeout) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(arguments);
        return run(command, workspace, timeout, Map.of());
    }

    private record Command(int exitCode, String stdout, String stderr, boolean timedOut) {

        boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }

        String describe() {
            if (timedOut) {
                return "제한 시간을 넘겨 강제 종료했다";
            }
            String output = stderr.isBlank() ? stdout : stderr;
            String trimmed = output.strip();
            if (trimmed.length() > 500) {
                trimmed = trimmed.substring(0, 500) + "…";
            }
            return "exit %d: %s".formatted(exitCode, trimmed.isBlank() ? "(출력 없음)" : trimmed);
        }
    }

    private Command run(List<String> command, Path workspace, Duration timeout, Map<String, String> environment) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile());
        builder.environment().putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Command(-1, "", "실행하지 못했다: " + e.getMessage(), false);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        // 한쪽만 읽으면 반대쪽 파이프 버퍼가 차서 프로세스가 멈춘다. 빌드는 출력이 특히 많다.
        Thread out = read(process.getInputStream(), stdout);
        Thread err = read(process.getErrorStream(), stderr);
        try {
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor();
                out.join();
                err.join();
                return new Command(-1, stdout.toString(), stderr.toString(), true);
            }
            out.join();
            err.join();
            return new Command(process.exitValue(), stdout.toString(), stderr.toString(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Command(-1, stdout.toString(), "인터럽트됐다", false);
        }
    }

    private Thread read(InputStream stream, StringBuilder sink) {
        return Thread.ofVirtual().start(() -> {
            try (stream) {
                sink.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.debug("프로세스 출력 스트림을 읽다 끊겼다", e);
            }
        });
    }
}
