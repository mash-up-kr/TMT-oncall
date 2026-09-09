package com.tmt.oncall.triage;

import com.tmt.oncall.agent.TestAgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.guard.CallBudget;
import com.tmt.oncall.notify.DiscordNotifier;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.FakeAgentCli;
import com.tmt.oncall.support.FakeGateway;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.SentryIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스킬은 아직 마켓플레이스에 없으므로 약속된 JSON만 돌려주는 CLI 대역으로 세운다.
 * 여기서 봐야 하는 것은 리포트 문구가 아니라 어떤 건을 걸러내고 무엇을 넘겼는가다.
 */
class IncidentTriageTest {

    private static final String ISSUE_ID = "4501";

    private static final String NEEDS_ACTION = """
            {"action_needed": true, "reason": "신규 NPE", "severity": "high"}
            """;

    private static final String ANALYZED = """
            {"cause": "MenuService에서 null 메뉴를 참조한다",
             "fix_plan": ["findById에 존재 검증을 넣는다", "테스트를 추가한다"],
             "impact": "메뉴 상세 조회 실패",
             "related_deploy": "abc123",
             "stack_excerpt": "java.lang.NullPointerException",
             "code_fix_possible": true}
            """;

    Path root;
    Path workspace;
    Path scratch;
    Target target;

    FakeAgentCli cli;
    OncallStore store;
    FakeGateway gateway;
    IncidentTriage triage;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("incident-triage-test");
        workspace = Files.createDirectories(root.resolve("workspace"));
        scratch = root.resolve("scratch");

        cli = FakeAgentCli.create();
        cli.answers(1, NEEDS_ACTION);
        cli.answers(2, ANALYZED);

        OncallProperties base = TestProperties.defaults();
        target = target(base.target());
        OncallProperties properties = withTarget(TestProperties.withAgent(base,
                TestProperties.agent(BillingMode.SUBSCRIPTION, Duration.ofMinutes(1),
                        cli.binary(), scratch.toAbsolutePath().toString())));

        store = TestStore.create().store();
        gateway = new FakeGateway();
        triage = new IncidentTriage(properties,
                TestAgentRunner.withFakeCli(properties, new CallBudget(properties, store)),
                new DiscordNotifier(gateway, store),
                store);
    }

    /** 대표 이벤트에는 상한이 없어, 자르지 않으면 컨텍스트를 넘겨 호출료만 쓰고 실패한다. */
    @Test
    void 너무_긴_대표_이벤트는_잘라서_넘긴다() {
        String huge = "{\"entries\": [\"" + "x".repeat(80_000) + "\"]}";

        triage.handle(incident(huge));

        assertThat(cli.prompt(1))
                .hasSizeLessThan(huge.length())
                .contains("길이 상한 60000자에서 잘림");
    }

    @Test
    void 조치가_필요하면_분석해_리포트를_낸다() {
        triage.handle(incident());

        assertThat(cli.prompt(1)).contains("/incident-triage").contains("NullPointerException");
        assertThat(cli.prompt(2)).contains("/incident-analyze").contains("MenuService.findById");
        assertThat(gateway.channelEmbeds).hasSize(1);
        assertThat(gateway.channelEmbeds.getFirst().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("수정 계획");
            assertThat(field.value()).isEqualTo("1. findById에 존재 검증을 넣는다\n2. 테스트를 추가한다");
        });
        assertThat(store.threadIdOf(IncidentRef.SENTRY, ISSUE_ID)).contains("thread-1");
    }

    /** 노이즈까지 리포트로 만들면 정작 봐야 할 건이 묻힌다. */
    @Test
    void 조치가_필요_없으면_채널에_올리지_않는다() {
        cli.answers(1, """
                {"action_needed": false, "reason": "일시적 타임아웃", "severity": "low"}
                """);

        triage.handle(incident());

        assertThat(cli.calls()).isEqualTo(1);
        assertThat(gateway.channelEmbeds).isEmpty();
        assertThat(gateway.notices).isEmpty();
        // 판정을 남긴다 — 없으면 중복 창이 지날 때마다 같은 건을 다시 분류한다.
        assertThat(store.triageDecision(IncidentRef.SENTRY, ISSUE_ID)).contains(false);
    }

    /** 같은 이슈에는 같은 답이 나온다. 다시 사면 걸러내서 아끼려던 호출을 도로 쓴다. */
    @Test
    void 걸러낸_건은_다시_분류하지_않는다() {
        cli.answers(1, """
                {"action_needed": false, "reason": "일시적 타임아웃", "severity": "low"}
                """);

        triage.handle(incident());
        triage.handle(incident());

        assertThat(cli.calls()).isEqualTo(1);
        assertThat(gateway.channelEmbeds).isEmpty();
    }

    /** 재시도는 분석이 실패해서 도는 것이다. 1차 분류까지 다시 살 이유가 없다. */
    @Test
    void 재시도는_분류를_건너뛰고_분석부터_한다() {
        cli.fails(2, "타임아웃");

        triage.handle(incident());
        cli.answers(3, ANALYZED);
        triage.handle(incident());

        assertThat(cli.calls()).isEqualTo(3);
        assertThat(cli.prompt(3)).contains("/incident-analyze");
        assertThat(gateway.channelEmbeds).hasSize(1);
    }

    /** 싼 모델로 거르려던 이유가 없어지지 않게, 1차 분류에는 대상 클론을 주지 않는다. */
    @Test
    void 일차_분류는_소스를_보지_않는다() throws IOException {
        triage.handle(incident());

        assertThat(Path.of(cli.workingDirectory(1)).toRealPath())
                .isEqualTo(scratch.toRealPath());
        assertThat(Path.of(cli.workingDirectory(2)).toRealPath())
                .isEqualTo(workspace.toRealPath());
    }

    /** 놓치는 쪽이 더 나쁘다 — 질문 경로와 폴백 방향이 반대다. */
    @Test
    void 일차_분류가_형식을_어기면_분석으로_넘긴다() {
        cli.answers(1, "조치가 필요해 보이지 않습니다");

        triage.handle(incident());

        assertThat(cli.prompt(2)).contains("/incident-analyze");
        assertThat(gateway.channelEmbeds).hasSize(1);
    }

    @Test
    void 일차_분류가_실패해도_분석으로_넘긴다() {
        cli.fails(1, "타임아웃");

        triage.handle(incident());

        assertThat(cli.prompt(2)).contains("/incident-analyze");
        assertThat(gateway.channelEmbeds).hasSize(1);
    }

    @Test
    void 무시된_건은_분석하지_않는다() {
        store.suppress(IncidentRef.SENTRY, ISSUE_ID, "민서");

        triage.handle(incident());

        assertThat(cli.calls()).isZero();
        assertThat(gateway.channelEmbeds).isEmpty();
    }

    /** 완료 표시는 리포트를 낸 스레드다. 재시작 후 같은 건을 다시 분석하지 않는다. */
    @Test
    void 이미_리포트를_낸_건은_다시_분석하지_않는다() {
        store.markProcessed(IncidentRef.SENTRY, ISSUE_ID, "thread-9");

        triage.handle(incident());

        assertThat(cli.calls()).isZero();
        assertThat(gateway.channelEmbeds).isEmpty();
    }

    /** 트리거는 분석에 넘기기 전에 이력을 남긴다 — 그것만으로 건너뛰면 아무 건도 분석되지 않는다. */
    @Test
    void 트리거가_먼저_남긴_이력은_완료로_보지_않는다() {
        store.markProcessed(IncidentRef.SENTRY, ISSUE_ID, null);

        triage.handle(incident());

        assertThat(gateway.channelEmbeds).hasSize(1);
    }

    /** 감지는 됐는데 아무도 모르는 상태가 되지 않게, 분석 실패도 채널에 알린다. */
    @Test
    void 분석에_실패하면_한_줄로_알린다() {
        cli.fails(2, "타임아웃");

        triage.handle(incident());

        assertThat(gateway.channelEmbeds).isEmpty();
        assertThat(gateway.notices).singleElement().asString().contains("분석하지 못했습니다");
    }

    /** 버튼은 재시작 뒤에도 눌린다. 그때 쓸 재료가 없으면 티켓도 PR도 만들 수 없다. */
    @Test
    void 버튼이_쓸_재료를_남긴다() {
        triage.handle(incident());

        assertThat(store.analysisOf(IncidentRef.SENTRY, ISSUE_ID)).hasValueSatisfying(saved -> {
            assertThat(saved.summary()).isEqualTo("NullPointerException");
            assertThat(saved.plan()).contains("findById에 존재 검증을 넣는다");
            assertThat(saved.stackExcerpt()).isEqualTo("java.lang.NullPointerException");
            assertThat(saved.sentryIssueUrl()).isEqualTo("https://sentry.io/issues/4501/");
        });
    }

    // --- 도우미 ---

    private IncidentDetected incident(String eventJson) {
        return new IncidentDetected(target, issue(), eventJson);
    }

    private IncidentDetected incident() {
        return new IncidentDetected(target, issue(), "{\"entries\": []}");
    }

    private static SentryIssue issue() {
        Instant seen = Instant.parse("2026-09-05T03:00:00Z");
        return new SentryIssue(ISSUE_ID, "TMT-BE-7", "NullPointerException", "MenuService.findById",
                "error", "new", "https://sentry.io/issues/4501/", 3, seen, seen);
    }

    private Target target(Target base) {
        return new Target(base.key(), base.repo(), workspace.toString(), base.buildCommand(),
                base.jiraProjectKey(), base.sentryProjectSlug(), base.healthUrl(),
                base.healthPollInterval(), base.healthFailureThreshold(), base.discordChannelId());
    }

    private OncallProperties withTarget(OncallProperties base) {
        return new OncallProperties(base.enabled(), target, base.discord(), base.jira(),
                base.github(), base.sentry(), base.agent(), base.guard(), base.store());
    }
}
