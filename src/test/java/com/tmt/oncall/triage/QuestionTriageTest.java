package com.tmt.oncall.triage;

import com.tmt.oncall.agent.TestAgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.guard.CallBudget;
import com.tmt.oncall.notify.DiscordNotifier;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.notify.ReportButton;
import com.tmt.oncall.notify.ReportEmbed;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.FakeGateway;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import com.tmt.oncall.trigger.QuestionAsked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스킬은 아직 마켓플레이스에 없으므로 약속된 JSON만 돌려주는 CLI 대역으로 세운다.
 * 여기서 봐야 하는 것은 답변 문구가 아니라 어떤 스킬을 무엇을 담아 불렀는가다.
 */
class QuestionTriageTest {

    /** {@code TestProperties.defaults()}의 온콜 채널. */
    private static final String ONCALL = "1";

    Path root;
    Path workspace;
    Path response;
    Target target;

    OncallStore store;
    FakeGateway gateway;
    QuestionTriage triage;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("question-triage-test");
        workspace = Files.createDirectories(root.resolve("workspace"));
        response = root.resolve("response.json");
        answers("""
                {"answer": "결제 서버 점검 중이라 잠시 뒤 다시 시도해 주세요."}
                """);

        OncallProperties base = TestProperties.defaults();
        target = target(base.target());
        OncallProperties properties = withTarget(TestProperties.withAgent(base,
                TestProperties.agent(BillingMode.SUBSCRIPTION, Duration.ofMinutes(1),
                        fakeAgent().toAbsolutePath().toString())));

        store = TestStore.create().store();
        gateway = new FakeGateway();
        triage = new QuestionTriage(
                TestAgentRunner.withFakeCli(properties, new CallBudget(properties, store)),
                new DiscordNotifier(gateway, store),
                store);
    }

    @Test
    void 역할로_정해진_톤의_스킬을_부른다() {
        triage.answer(question(Audience.SPRING, ONCALL, null, "결제가 500 떨어져요"));

        assertThat(agentPrompt())
                .contains("/answer-spring")
                .contains("결제가 500 떨어져요");
    }

    @Test
    void 답변을_채널에_올리고_스레드를_연다() {
        triage.answer(question(Audience.DESIGN, ONCALL, null, "결제가 안 돼요"));

        assertThat(gateway.channelEmbeds).hasSize(1);
        ReportEmbed embed = gateway.channelEmbeds.getFirst();
        assertThat(embed.description()).isEqualTo("결제 서버 점검 중이라 잠시 뒤 다시 시도해 주세요.");
        assertThat(embed.fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("질문");
            assertThat(field.value()).isEqualTo("결제가 안 돼요");
        });
        assertThat(gateway.openedThreadNames).containsExactly("질문 — 지영");
    }

    /** 답변만 하고 끝나는 경로는 티켓을 만들지 않는다 — 스레드가 기록이라 누를 것이 없다. */
    @Test
    void 수정이_필요_없는_답변에는_버튼을_붙이지_않는다() {
        triage.answer(question(Audience.DESIGN, ONCALL, null, "결제가 안 돼요"));

        assertThat(gateway.lastButtons).isEmpty();
    }

    @Test
    void 코드_수정이_필요하면_수정_계획과_버튼을_붙인다() {
        answers("""
                {"answer": "응답 계약이 어긋났습니다.",
                 "code_fix_needed": true,
                 "fix_plan": ["OrderResponse에 status 필드를 추가한다", "매퍼를 함께 고친다"]}
                """);

        triage.answer(question(Audience.WEB, ONCALL, null, "status가 없어요"));

        assertThat(gateway.lastButtons).containsExactly(ReportButton.CREATE_PR);
        assertThat(gateway.channelEmbeds.getFirst().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("수정 계획");
            assertThat(field.value())
                    .isEqualTo("1. OrderResponse에 status 필드를 추가한다\n2. 매퍼를 함께 고친다");
        });
    }

    /** 버튼은 재시작 뒤에도 눌린다. 재료가 없으면 눌러도 아무 일이 없다. */
    @Test
    void 버튼을_붙인_답변은_PR_재료를_남긴다() {
        answers("""
                {"answer": "응답 계약이 어긋났습니다.",
                 "code_fix_needed": true,
                 "fix_plan": ["OrderResponse에 status 필드를 추가한다", "매퍼를 함께 고친다"]}
                """);

        triage.answer(question(Audience.WEB, ONCALL, null, "status가 없어요"));

        assertThat(store.analysisOf(IncidentRef.QUESTION, "m1")).hasValueSatisfying(saved -> {
            assertThat(saved.summary()).isEqualTo("status가 없어요");
            assertThat(saved.plan())
                    .isEqualTo("OrderResponse에 status 필드를 추가한다\n매퍼를 함께 고친다");
            // 질문에는 그런 것이 없다. 지어내면 티켓·PR이 엉뚱한 곳을 가리킨다.
            assertThat(saved.stackExcerpt()).isEmpty();
            assertThat(saved.sentryIssueUrl()).isEmpty();
        });
    }

    @Test
    void 버튼이_없는_답변은_PR_재료를_남기지_않는다() {
        triage.answer(question(Audience.DESIGN, ONCALL, null, "결제가 안 돼요"));

        assertThat(store.analysisOf(IncidentRef.QUESTION, "m1")).isEmpty();
    }

    /** 재분석으로 계획이 바뀌면 그다음에 눌리는 버튼은 새 계획으로 돌아야 한다. */
    @Test
    void 재분석한_계획으로_PR_재료를_갱신한다() {
        answers("""
                {"answer": "계약 문제입니다.", "code_fix_needed": true,
                 "fix_plan": ["OrderResponse에 status 필드를 추가한다"]}
                """);
        triage.answer(question(Audience.WEB, ONCALL, null, "status가 없어요"));

        answers("""
                {"answer": "주문 상세만 고치면 됩니다.", "code_fix_needed": true,
                 "fix_plan": ["OrderDetailResponse만 고친다"]}
                """);
        triage.answer(question(Audience.WEB, "thread-1", "thread-1", "주문 상세에서만 그래요"));

        assertThat(store.analysisOf(IncidentRef.QUESTION, "m1")).hasValueSatisfying(saved -> {
            assertThat(saved.plan()).isEqualTo("OrderDetailResponse만 고친다");
            // 요약은 원 질문 그대로다 — 재분석 힌트가 PR 제목이 되면 무엇을 고치는지 흐려진다.
            assertThat(saved.summary()).isEqualTo("status가 없어요");
        });
    }

    @Test
    void 스레드에_남긴_힌트를_원_질문과_함께_다시_묻는다() {
        triage.answer(question(Audience.WEB, ONCALL, null, "status가 없어요"));

        triage.answer(question(Audience.WEB, "thread-1", "thread-1", "주문 상세에서만 그래요"));

        assertThat(agentPrompt())
                .contains("status가 없어요")
                .contains("주문 상세에서만 그래요");
        assertThat(gateway.channelEmbeds).hasSize(1);
        assertThat(gateway.threadIds).containsExactly("thread-1");
    }

    /** 에러 리포트 스레드의 힌트는 '다시 분석' 버튼이 맡는다. 여기서 답하면 두 번 답한다. */
    @Test
    void 질문_스레드가_아니면_답하지_않는다() {
        store.markProcessed("sentry", "4501", "thread-9");

        triage.answer(question(Audience.SPRING, "thread-9", "thread-9", "배포 직후부터예요"));

        assertThat(gateway.channelEmbeds).isEmpty();
        assertThat(gateway.threadEmbeds).isEmpty();
    }

    /** 형식 하나 때문에 질문한 사람이 아무 답도 못 받는 것이 버튼 없는 답변보다 나쁘다. */
    @Test
    void 약속된_형식이_아니어도_답변은_전한다() {
        answers("그냥 평문으로 답했습니다");

        triage.answer(question(Audience.DESIGN, ONCALL, null, "결제가 안 돼요"));

        // 본문은 살리되 왜 이렇게 보이는지 앞에 적는다 — 형식이 깨진 것과 원래 그런 답인 것은 다르다.
        assertThat(gateway.channelEmbeds.getFirst().description())
                .contains("그냥 평문으로 답했습니다")
                .contains("약속된 형식으로 답하지 않아");
        assertThat(gateway.lastButtons).isEmpty();
    }

    @Test
    void 분석에_실패하면_한_줄로_알린다() {
        agentFails("타임아웃");

        triage.answer(question(Audience.DESIGN, ONCALL, null, "결제가 안 돼요"));

        assertThat(gateway.channelEmbeds).isEmpty();
        assertThat(gateway.notices).singleElement().asString()
                .startsWith("답변을 만들지 못했습니다");
    }

    // --- 도우미 ---

    private QuestionAsked question(Audience audience, String channelId, String threadId, String content) {
        return new QuestionAsked(target, audience, channelId, threadId, "m1", "u1", "지영",
                content, threadId != null);
    }

    /** 스킬이 다음 호출에 돌려줄 출력. CLI 봉투(result)에 담아 둔다. */
    private void answers(String skillOutput) {
        write(response, "{\"is_error\": false, \"result\": %s, \"usage\": {}}"
                .formatted(quoted(skillOutput.strip().replace("\n", " "))));
    }

    /** CLI가 오류를 돌려준 경우. 봉투 자체가 달라 답변 출력과 섞이지 않는다. */
    private void agentFails(String reason) {
        write(response, "{\"is_error\": true, \"result\": %s}".formatted(quoted(reason)));
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String agentPrompt() {
        return read(workspace.resolve("agent-prompt.txt"));
    }

    /** 프롬프트를 남기고 준비된 응답을 그대로 찍는 에이전트 대역. */
    private Path fakeAgent() {
        Path script = write(root.resolve("fake-claude"), """
                #!/bin/sh
                cat > agent-prompt.txt
                cat %s
                """.formatted(response.toAbsolutePath()));
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return script;
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

    private static Path write(Path file, String body) {
        try {
            return Files.writeString(file, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
