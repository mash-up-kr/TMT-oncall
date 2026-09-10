package com.tmt.oncall.action;

import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.agent.TestAgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.guard.CallBudget;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임시 디렉터리에 진짜 git 저장소와 로컬 베어 원격을 세워 돌린다. 네트워크로 나가는 것은
 * {@code gh}와 수정 에이전트뿐이라 둘만 같은 계약을 흉내내는 스크립트로 대체한다 —
 * 브랜치·커밋·push 규칙은 진짜 git으로 돌려야 확인하는 의미가 있다.
 */
class PullRequestAgentTest {

    Path root;
    Path origin;
    Path workspace;
    Path fakeBin;
    Path fakeGh;
    String baseCommit;

    OncallProperties properties;
    CallBudget budget;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("pr-agent-test");
        origin = root.resolve("origin.git");
        workspace = root.resolve("workspace");
        fakeBin = root.resolve("bin");
        Files.createDirectories(fakeBin);

        seedRepository();

        properties = TestProperties.withAgent(TestProperties.defaults(),
                TestProperties.agent(BillingMode.SUBSCRIPTION, Duration.ofMinutes(1),
                        fakeAgent().toAbsolutePath().toString()));
        budget = new CallBudget(properties, TestStore.create().store());
        fakeGh = fakeGh();
    }

    /** 규칙은 TMT-BE docs/BRANCHING.md — {@code <type>/<Jira키>}, 설명 suffix 금지. */
    @Test
    void 티켓이_있으면_브랜치와_PR_제목에_그_키를_쓴다() throws IOException {
        PullRequestResult result = agent("./gradlew").open(request("TMT-401", "./gradlew"));

        assertThat(result).isInstanceOf(PullRequestResult.Created.class);
        PullRequestResult.Created created = (PullRequestResult.Created) result;
        assertThat(created.branch()).isEqualTo("fix/TMT-401");
        assertThat(created.url()).isEqualTo("https://github.com/mash-up-kr/TMT-BE/pull/7");
        assertThat(created.ticketLinked()).isTrue();
        assertThat(remoteBranches()).contains(created.branch());
        assertThat(Files.readString(ghArgs()))
                .contains("[TMT-401] fix: NullPointerException 방어")
                .contains("--base")
                .contains("main");
    }

    /** 키를 지어내면 나중에 생길 진짜 티켓과 부딪히므로, 없으면 없는 채로 간다. */
    @Test
    void 티켓이_없으면_oncall_접두사를_쓰고_키를_지어내지_않는다() throws IOException {
        PullRequestResult result = agent("./gradlew").open(request(null, "./gradlew"));

        PullRequestResult.Created created = (PullRequestResult.Created) result;
        assertThat(created.branch()).startsWith("oncall/");
        assertThat(created.ticketLinked()).isFalse();
        assertThat(Files.readString(ghArgs()))
                .contains("(티켓 미생성)")
                .doesNotContain("TMT-4");
    }

    /**
     * 스킬은 고칠 수 없으면 무엇이 막았는지 적고 끝내라고 지시한다. 그 설명을 버리면 호출료를
     * 다 치르고도 왜 안 고쳤는지 아무도 알 수 없다.
     */
    @Test
    void 아무것도_고치지_않았으면_에이전트_보고를_함께_전한다() throws IOException {
        PullRequestResult result = agent("./gradlew", idleAgent()).open(request("TMT-401", "./gradlew"));

        assertThat(result).isInstanceOf(PullRequestResult.Failed.class);
        assertThat(result.message())
                .contains("아무것도 고치지 않았습니다")
                .contains("측정 지표가 없어 어디가 느린지 특정할 수 없다");
    }

    /**
     * 클론이 하나뿐이라 두 건이 겹치면 한쪽의 수정이 남의 브랜치로 커밋된다. 기다리게 하지
     * 않고 돌려보내는 것은 상호작용 토큰이 만료되기 전에 사람에게 답을 주기 위해서다.
     */
    @Test
    void 다른_건의_수정이_도는_동안에는_받지_않는다() throws Exception {
        PullRequestAgent agent = agent("./gradlew", slowAgent());
        Path started = workspace.resolve("agent-started.txt");

        Thread first = Thread.ofVirtual().start(() -> agent.open(request("TMT-401", "./gradlew")));
        while (!Files.exists(started)) {
            Thread.onSpinWait();
        }

        PullRequestResult second = agent.open(request("TMT-402", "./gradlew"));

        assertThat(second).isInstanceOf(PullRequestResult.Failed.class);
        assertThat(second.message()).contains("다른 건의 수정이 진행 중");
        first.join();
        assertThat(remoteBranches()).contains("fix/TMT-401").doesNotContain("fix/TMT-402");
    }

    @Test
    void 빌드가_실패하면_PR을_올리지_않는다() throws IOException {
        PullRequestResult result = agent("./build-fail.sh").open(request("TMT-401", "./build-fail.sh"));

        assertThat(result).isInstanceOf(PullRequestResult.BuildFailed.class);
        assertThat(result.message()).contains("자동 수정에 실패");
        assertThat(ghArgs()).doesNotExist();
        assertThat(remoteBranches()).containsExactly("main");
    }

    @Test
    void 베이스_브랜치에는_직접_push하지_않는다() throws IOException {
        PullRequestResult.Created created = (PullRequestResult.Created) agent("./gradlew").open(request("TMT-401", "./gradlew"));

        assertThat(created.branch()).isNotEqualTo("main");
        assertThat(remoteCommit("main")).isEqualTo(baseCommit);
        assertThat(remoteCommit(created.branch())).isNotEqualTo(baseCommit);
    }

    /** Conventional Commits — 티켓 키는 squash 뒤 main에 남을 PR 제목이 들고 간다. */
    @Test
    void 커밋_메시지는_제목만_남긴다() throws IOException {
        agent("./gradlew").open(request("TMT-401", "./gradlew"));

        String message = git(workspace, "log", "-1", "--format=%B").strip();
        assertThat(message.lines()).hasSize(1);
        assertThat(message)
                .isEqualTo("fix: NullPointerException 방어")
                .doesNotContain("Co-Authored-By")
                .doesNotContain("Claude");
    }

    // --- 도우미 ---

    private PullRequestAgent agent(String buildCommand) {
        return agent(buildCommand, properties.agent().binary());
    }

    private PullRequestAgent agent(String buildCommand, Path agentBinary) {
        return agent(buildCommand, agentBinary.toAbsolutePath().toString());
    }

    private PullRequestAgent agent(String buildCommand, String agentBinary) {
        OncallProperties withAgent = TestProperties.withAgent(properties,
                TestProperties.agent(BillingMode.SUBSCRIPTION, Duration.ofMinutes(1), agentBinary));
        OncallProperties withWorkspace = new OncallProperties(withAgent.enabled(), target(buildCommand),
                withAgent.discord(), withAgent.jira(), withAgent.github(), withAgent.sentry(),
                withAgent.agent(), withAgent.guard(), withAgent.store());
        AgentRunner runner = TestAgentRunner.withFakeCli(withWorkspace, budget);
        return new PullRequestAgent(withWorkspace, runner, fakeGh.toAbsolutePath().toString());
    }

    private Path ghArgs() {
        return workspace.resolve("gh-args.txt");
    }

    private Target target(String buildCommand) {
        Target base = properties.target();
        return new Target(base.key(), base.repo(), workspace.toString(), buildCommand,
                base.jiraProjectKey(), base.sentryProjectSlug(), base.healthUrl(),
                base.healthPollInterval(), base.healthFailureThreshold(), base.discordChannelId());
    }

    private PullRequestRequest request(String ticketKey, String buildCommand) {
        return new PullRequestRequest(target(buildCommand), ticketKey, "NullPointerException 방어",
                "StoreService.find에서 null 검사를 추가한다", "minseo");
    }

    private void seedRepository() throws IOException {
        Path seed = root.resolve("seed");
        Files.createDirectories(seed);
        git(origin.getParent(), "init", "--bare", "--initial-branch=main", origin.toString());

        Files.writeString(seed.resolve("src.txt"), "원본\n");
        executable(seed.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
        executable(seed.resolve("build-fail.sh"), "#!/bin/sh\necho '컴파일 에러' >&2\nexit 1\n");

        git(seed, "init", "--initial-branch=main");
        git(seed, "config", "user.email", "seed@tmt.invalid");
        git(seed, "config", "user.name", "seed");
        git(seed, "add", "--all");
        git(seed, "commit", "-m", "초기 커밋");
        git(seed, "remote", "add", "origin", origin.toString());
        git(seed, "push", "origin", "main");

        git(root, "clone", origin.toString(), workspace.toString());
        baseCommit = remoteCommit("main");
    }

    /** 작업 트리만 고치고 끝나는 수정 에이전트 대역. */
    /** 들어온 것을 알리고 잠시 머무는 대역. 두 번째 호출이 겹치는 순간을 만들려면 필요하다. */
    private Path slowAgent() throws IOException {
        return executable(fakeBin.resolve("slow-claude"), """
                #!/bin/sh
                echo "수정됨" >> src.txt
                : > agent-started.txt
                sleep 2
                cat <<'JSON'
                {"is_error":false,"result":"고쳤다","usage":{"input_tokens":1,"output_tokens":1}}
                JSON
                """);
    }

    /** 파일을 고치지 않고 이유만 적고 끝내는 대역. */
    private Path idleAgent() throws IOException {
        return executable(fakeBin.resolve("idle-claude"), """
                #!/bin/sh
                cat <<'JSON'
                {"is_error":false,"result":"측정 지표가 없어 어디가 느린지 특정할 수 없다",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                JSON
                """);
    }

    private Path fakeAgent() throws IOException {
        return executable(fakeBin.resolve("fake-claude"), """
                #!/bin/sh
                echo "수정됨" >> src.txt
                cat <<'JSON'
                {"is_error":false,"result":"고쳤다","usage":{"input_tokens":1,"output_tokens":1}}
                JSON
                """);
    }

    /** 인자를 남기고 PR 주소를 찍는 gh 대역. 인자 파일은 커밋이 끝난 뒤 생기므로 diff에 섞이지 않는다. */
    private Path fakeGh() throws IOException {
        return executable(fakeBin.resolve("gh"), """
                #!/bin/sh
                : > gh-args.txt
                for arg in "$@"; do printf '%s\\n' "$arg" >> gh-args.txt; done
                echo https://github.com/mash-up-kr/TMT-BE/pull/7
                """);
    }

    private Path executable(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private List<String> remoteBranches() {
        return git(origin, "for-each-ref", "--format=%(refname:short)", "refs/heads").lines().toList();
    }

    private String remoteCommit(String branch) {
        return git(origin, "rev-parse", branch).strip();
    }

    private String git(Path directory, String... arguments) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command().add("git");
            builder.command().addAll(List.of(arguments));
            builder.directory(directory.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git %s 실패: %s".formatted(List.of(arguments), output));
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
