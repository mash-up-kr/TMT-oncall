package com.tmt.oncall.triage;

import com.tmt.oncall.action.PullRequestResult;
import com.tmt.oncall.action.TestActionAgents;
import com.tmt.oncall.action.TicketResult;
import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.agent.TestAgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.guard.CallBudget;
import com.tmt.oncall.notify.ButtonHandler;
import com.tmt.oncall.notify.DiscordNotifier;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.notify.ReportButton;
import com.tmt.oncall.notify.TestButtonHandler;
import com.tmt.oncall.store.IncidentAnalysis;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.FakeAgentCli;
import com.tmt.oncall.support.FakeGateway;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import com.tmt.oncall.trigger.QuestionAsked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 버튼을 누른 뒤에 실제로 무엇이 돌고, 무엇이 스레드에 남는지를 본다. */
class ButtonActionsTest {

    private static final IncidentRef REF = IncidentRef.sentry("4501");
    private static final String ACTOR = "민서";

    private static final String REANALYZED = """
            {"cause": "메뉴 캐시가 비어 있다",
             "fix_plan": ["캐시 워밍업을 넣는다"],
             "impact": "메뉴 상세 조회 실패",
             "related_deploy": "abc123",
             "stack_excerpt": "java.lang.NullPointerException",
             "code_fix_possible": true}
            """;

    Path workspace;
    OncallProperties properties;

    FakeAgentCli cli;
    OncallStore store;
    FakeGateway gateway;
    TestActionAgents.Jira jira;
    TestActionAgents.PullRequests pullRequests;
    AgentRunner runner;
    DiscordNotifier notifier;
    ButtonHandler handler;
    ButtonActions actions;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Files.createTempDirectory("button-actions-test");
        workspace = Files.createDirectories(root.resolve("workspace"));

        cli = FakeAgentCli.create();
        cli.answers(REANALYZED);

        OncallProperties base = TestProperties.defaults();
        properties = withTarget(TestProperties.withAgent(base,
                TestProperties.agent(BillingMode.SUBSCRIPTION, Duration.ofMinutes(1),
                        cli.binary(), root.resolve("scratch").toString())),
                target(base.target()));

        store = TestStore.create().store();
        gateway = new FakeGateway();
        jira = new TestActionAgents.Jira(properties);
        runner = TestAgentRunner.withFakeCli(properties, new CallBudget(properties, store));
        notifier = new DiscordNotifier(gateway, store);
        pullRequests = new TestActionAgents.PullRequests(properties, runner);
        handler = TestButtonHandler.create(store, () -> actions);
        actions = new ButtonActions(properties, store, jira, pullRequests, runner, notifier, handler);

        store.markProcessed(REF.sourceKey(), REF.externalId(), "thread-1");
        store.saveAnalysis(analysis());
    }

    @Test
    void 티켓을_만들고_그_키로_PR을_올린다() {
        actions.createPullRequest(REF, ACTOR);

        assertThat(jira.requests).singleElement().satisfies(request -> {
            assertThat(request.summary()).isEqualTo("NullPointerException");
            assertThat(request.requestedBy()).isEqualTo(ACTOR);
            assertThat(request.threadUrl()).contains("thread-1");
        });
        assertThat(pullRequests.requests).singleElement().satisfies(request -> {
            assertThat(request.ticketKey()).isEqualTo("TMT-401");
            assertThat(request.plan()).contains("findById에 존재 검증을 넣는다");
        });
        // 결과는 채널이 아니라 그 건의 스레드로 간다.
        assertThat(gateway.noticeChannelIds).containsOnly("thread-1");
        assertThat(gateway.notices).anySatisfy(line -> assertThat(line).contains("https://github/pr/1"));
    }

    /** 이 자동화의 산출물은 수정과 PR이고 티켓은 기록이다. */
    @Test
    void 티켓_생성에_실패해도_PR은_낸다() {
        jira.returns(new TicketResult.Failed("Jira가 500을 냈다"));

        actions.createPullRequest(REF, ACTOR);

        assertThat(pullRequests.requests).singleElement()
                .satisfies(request -> assertThat(request.ticketKey()).isNull());
        assertThat(gateway.threadEmbeds)
                .anySatisfy(embed -> assertThat(embed.title()).contains("티켓 생성 실패"));
    }

    /** 빌드 실패는 수정이 틀렸다는 뜻이라 티켓 실패와 사람이 할 일이 다르다. */
    @Test
    void 빌드가_깨지면_자동_수정_실패로_보고한다() {
        pullRequests.returns(new PullRequestResult.BuildFailed("oncall/fix", "테스트가 깨졌다"));

        actions.createPullRequest(REF, ACTOR);

        assertThat(gateway.threadEmbeds)
                .anySatisfy(embed -> assertThat(embed.title()).contains("자동 수정 실패"));
    }

    /** 부르지 않으면 그 건의 버튼이 잠긴 채로 남아 사람이 다시 누를 수 없다. */
    @Test
    void 실패해도_잠금을_푼다() {
        pullRequests.throwsFailure(new IllegalStateException("gh가 없다"));

        assertThat(handler.handle(ReportButton.CREATE_PR, REF, ACTOR)).contains("시작합니다");
        assertThat(handler.handle(ReportButton.CREATE_PR, REF, ACTOR)).contains("시작합니다");

        assertThat(pullRequests.requests).hasSize(2);
        assertThat(gateway.notices).anySatisfy(line -> assertThat(line).contains("오류로 중단됐습니다"));
    }

    @Test
    void 재분석은_스레드_힌트를_반영한다() {
        store.saveThreadHint("thread-1", "지영", "주문 상세에서만 그래요");

        actions.reanalyze(REF, ACTOR);

        assertThat(cli.prompt(1))
                .contains("/incident-analyze")
                .contains("주문 상세에서만 그래요")
                .contains("NullPointerException");
        assertThat(gateway.threadEmbeds).singleElement()
                .satisfies(embed -> assertThat(embed.title()).contains("재분석"));
        assertThat(gateway.threadIds).containsExactly("thread-1");
    }

    /** 이후에 눌리는 'PR 만들기'는 방금 나온 계획으로 돌아야 한다. */
    @Test
    void 재분석_결과가_다음_PR의_재료가_된다() {
        actions.reanalyze(REF, ACTOR);
        actions.createPullRequest(REF, ACTOR);

        assertThat(pullRequests.requests).singleElement()
                .satisfies(request -> assertThat(request.plan()).isEqualTo("캐시 워밍업을 넣는다"));
    }

    /** 눌러도 아무 일이 없는 것처럼 보이면 사람이 원인을 알 수 없다. */
    @Test
    void 분석_결과가_없는_건은_사유를_알린다() {
        IncidentRef unknown = IncidentRef.sentry("9999");

        actions.createPullRequest(unknown, ACTOR);

        assertThat(jira.requests).isEmpty();
        assertThat(pullRequests.requests).isEmpty();
        assertThat(gateway.notices).singleElement().asString().contains("남아 있지 않아");
    }

    @Test
    void 재분석에_실패하면_스레드에_알린다() {
        cli.fails(1, "타임아웃");

        actions.reanalyze(REF, ACTOR);

        assertThat(gateway.threadEmbeds).isEmpty();
        assertThat(gateway.notices).singleElement().asString().contains("다시 분석하지 못했습니다");
    }

    /**
     * 질문 답변에 'PR 만들기'를 붙였으면 그 버튼도 PR까지 가야 한다. 버튼은 보이는데 눌러도
     * 아무 일이 없으면 사람 눈에는 봇이 고장 난 것으로 보인다.
     */
    @Test
    void 질문_답변의_PR_버튼도_PR까지_간다() {
        cli.answers(1, """
                {"answer": "응답 계약이 어긋났습니다.", "code_fix_needed": true,
                 "fix_plan": ["OrderResponse에 status 필드를 추가한다"]}
                """);
        new QuestionTriage(runner, notifier, store).answer(question("status가 없어요"));

        IncidentRef question = IncidentRef.question("m1");
        assertThat(handler.handle(ReportButton.CREATE_PR, question, ACTOR)).contains("시작합니다");

        assertThat(pullRequests.requests).singleElement().satisfies(request -> {
            assertThat(request.summary()).isEqualTo("status가 없어요");
            assertThat(request.plan()).isEqualTo("OrderResponse에 status 필드를 추가한다");
            assertThat(request.ticketKey()).isEqualTo("TMT-401");
        });
        // 질문에는 Sentry 이슈도 스택도 없다. 티켓 본문은 그 줄을 통째로 뺀다.
        assertThat(jira.requests).singleElement().satisfies(request -> {
            assertThat(request.sentryIssueUrl()).isEmpty();
            assertThat(request.stackSummary()).isEmpty();
        });
    }

    // --- 도우미 ---

    private QuestionAsked question(String content) {
        return new QuestionAsked(properties.target(), Audience.WEB,
                properties.target().discordChannelId(), null, "m1", "u1", "지영", content, false);
    }

    private static IncidentAnalysis analysis() {
        return new IncidentAnalysis(REF.sourceKey(), REF.externalId(), "NullPointerException",
                "findById에 존재 검증을 넣는다", "java.lang.NullPointerException",
                "https://sentry.io/issues/4501/", Instant.parse("2026-09-05T03:00:00Z"));
    }

    private Target target(Target base) {
        return new Target(base.key(), base.repo(), workspace.toString(), base.buildCommand(),
                base.jiraProjectKey(), base.sentryProjectSlug(), base.healthUrl(),
                base.healthPollInterval(), base.healthFailureThreshold(), base.discordChannelId());
    }

    private static OncallProperties withTarget(OncallProperties base, Target target) {
        return new OncallProperties(base.enabled(), target, base.discord(), base.jira(),
                base.github(), base.sentry(), base.agent(), base.guard(), base.store());
    }
}
