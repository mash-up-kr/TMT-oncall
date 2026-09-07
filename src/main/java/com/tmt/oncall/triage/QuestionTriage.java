package com.tmt.oncall.triage;

import com.tmt.oncall.agent.AgentCall;
import com.tmt.oncall.agent.AgentResult;
import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.notify.Answer;
import com.tmt.oncall.notify.DiscordNotifier;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.notify.IncidentReports;
import com.tmt.oncall.store.IncidentAnalysis;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.store.QuestionThread;
import com.tmt.oncall.trigger.QuestionAsked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 질문 경로를 엮는다 — 역할로 정해진 톤의 답변 스킬을 부르고, 결과를 채널에 올린다.
 *
 * <p>
 * 여기서는 티켓을 만들지 않는다. 답변만 하고 끝나는 경로의 기록은 Discord 스레드다.
 * 코드 수정이 필요한 질문이면 수정 계획과 버튼까지만 붙이고, 실제 수정은 사람이 누른 뒤
 * 에러 경로와 같은 PR 경로로 간다.
 */
@Component
public class QuestionTriage {

    private static final Logger log = LoggerFactory.getLogger(QuestionTriage.class);

    /** 커밋·PR 제목이 되는 길이. TMT-BE 규칙대로 한 줄로 읽히는 선에서 자른다. */
    private static final int SUMMARY_LIMIT = 72;

    private final AgentRunner runner;
    private final DiscordNotifier notifier;
    private final OncallStore store;

    QuestionTriage(AgentRunner runner, DiscordNotifier notifier, OncallStore store) {
        this.runner = runner;
        this.notifier = notifier;
        this.store = store;
    }

    /**
     * 수신 스레드에서 떼어 다른 스레드로 넘긴다. 분석은 분 단위로 걸리는데 JDA 이벤트 스레드를
     * 붙잡고 있으면 그동안 받은 다른 메시지가 밀린다.
     */
    @Async
    @EventListener
    public void on(QuestionAsked question) {
        answer(question);
    }

    void answer(QuestionAsked question) {
        QuestionThread origin = null;
        if (question.reanalysis()) {
            origin = store.questionThread(question.threadId()).orElse(null);
            if (origin == null) {
                // 에러 리포트 스레드에 달린 힌트다. 그 재분석은 '다시 분석' 버튼이 맡는다.
                // 버튼 상호작용에는 본문이 실려 오지 않으므로, 힌트는 받은 지금 남겨 둬야 한다.
                store.saveThreadHint(question.threadId(), question.authorName(), question.content());
                log.debug("질문 스레드가 아니라 답하지 않고 힌트로만 남긴다 — {}", question.threadId());
                return;
            }
        }

        IncidentRef ref = IncidentRef.question(origin == null ? question.messageId() : origin.messageId());
        AgentResult result = runner.run(AgentCall.of(
                CallPath.ANALYZE,
                question.audience().skill(),
                prompt(question, origin),
                question.target().workspacePath()));

        if (!result.isOk()) {
            log.warn("답변에 실패했다 — {}", result.message());
            notifier.notice(replyChannel(question), IncidentReports.answerFailed(result.message()));
            return;
        }

        Answer answer = Answers.parse(result.message());
        String threadId = notifier.reportAnswer(question, ref, answer);
        if (origin == null) {
            store.saveQuestionThread(new QuestionThread(
                    threadId, question.messageId(), question.audience(), question.content()));
        }
        saveFixMaterials(ref, origin == null ? question.content() : origin.content(), answer);
    }

    /**
     * 'PR 만들기'를 붙였으면 그 버튼이 쓸 재료도 함께 남긴다. 버튼은 봇이 재시작한 뒤에도 눌리는데
     * 재료가 없으면 눌러도 아무 일이 없어, 사람 눈에는 봇이 고장 난 것으로 보인다.
     *
     * <p>
     * 버튼을 붙이지 않은 답변은 저장하지 않는다 — 재분석으로 수정이 필요 없어졌더라도 앞선 답변에
     * 달린 버튼은 그대로 남아 있고, 그 버튼이 약속한 것은 그때 보여준 계획이다.
     */
    private void saveFixMaterials(IncidentRef ref, String question, Answer answer) {
        if (!answer.codeFixNeeded()) {
            return;
        }
        store.saveAnalysis(new IncidentAnalysis(
                ref.sourceKey(), ref.externalId(),
                summary(question),
                String.join("\n", answer.fixPlan()),
                // 질문에는 스택도 Sentry 이슈도 없다. 없는 값을 지어내지 않고 빈 채로 둔다.
                "", "",
                Instant.now()));
    }

    /** 요약은 커밋·PR 제목이 되므로 원 질문을 한 줄로 줄인다. 질문 전문은 스레드에 그대로 있다. */
    private static String summary(String question) {
        String line = question.strip().lines().findFirst().orElse("").strip();
        return line.length() <= SUMMARY_LIMIT ? line : line.substring(0, SUMMARY_LIMIT - 1) + "…";
    }

    /**
     * 재분석은 원 질문에 힌트를 얹어 다시 묻는다. 스레드의 대화를 통째로 넣지 않는 이유는
     * 사람끼리 오간 말까지 되먹이면 답이 무엇에 대한 것인지가 흐려지기 때문이다.
     */
    private static String prompt(QuestionAsked question, QuestionThread origin) {
        if (origin == null) {
            return "질문자: %s%n질문:%n%s".formatted(question.authorName(), question.content());
        }
        return "질문자: %s%n먼저 받은 질문:%n%s%n%n스레드에 남긴 힌트:%n%s"
                .formatted(question.authorName(), origin.content(), question.content());
    }

    /** 재분석 실패는 물어본 스레드에, 첫 질문의 실패는 채널에 알린다. */
    private static String replyChannel(QuestionAsked question) {
        return question.reanalysis() ? question.threadId() : question.channelId();
    }
}
