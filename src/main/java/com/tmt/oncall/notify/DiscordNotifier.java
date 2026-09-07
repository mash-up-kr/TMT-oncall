package com.tmt.oncall.notify;

import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.QuestionAsked;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 리포트를 채널에 올린다. 같은 건의 후속 보고는 첫 리포트에 열린 스레드로 들어간다 —
 * 채널에는 건마다 한 줄만 남고, 진행 상황은 스레드를 따라가면 된다.
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    /** Discord 스레드 이름 상한. */
    private static final int THREAD_NAME_LIMIT = 100;

    private final DiscordGateway gateway;
    private final OncallStore store;

    public DiscordNotifier(DiscordGateway gateway, OncallStore store) {
        this.gateway = gateway;
        this.store = store;
    }

    /**
     * 헬스체크 경로는 알리기만 하고 버튼을 붙이지 않는다. 헬스체크만으로는 코드 원인을 알 수 없고,
     * 원인이 코드라면 그 예외는 Sentry에 잡혀 에러 경로가 자기 리포트를 버튼과 함께 낸다 —
     * 여기에 버튼을 두면 같은 일을 두 경로에서 하게 된다.
     */
    public void reportDown(ServiceDownDetected event) {
        report(event.target().discordChannelId(), IncidentRef.health(event.target().key()),
                "다운 — " + event.target().key(),
                IncidentReports.down(event), List.of(), List.of());
    }

    public void reportRecovered(ServiceRecovered event) {
        report(event.target().discordChannelId(), IncidentRef.health(event.target().key()),
                "다운 — " + event.target().key(),
                IncidentReports.recovered(event), List.of(), List.of());
    }

    /**
     * 스택은 임베드가 아니라 뒤따르는 코드 블록 메시지로 보낸다 — 원인·수정 계획이 먼저 읽혀야 한다.
     *
     * @return 리포트를 실은 스레드. 처리 완료 표시에 이 값이 필요하다
     */
    public String reportIncident(IncidentDetected event, Analysis analysis) {
        return report(event.target().discordChannelId(), IncidentRef.sentry(event.issue().id()),
                event.issue().shortId() + " " + event.issue().title(),
                IncidentReports.incident(event, analysis),
                MessageSplitter.split(IncidentReports.codeBlock("스택", analysis.stackExcerpt())),
                IncidentReports.buttonsFor(analysis));
    }

    /**
     * 질문 답변을 올린다. 첫 답변은 채널에 올려 스레드를 열고, 그 스레드에 달린 힌트로 다시 답한
     * 결과는 같은 스레드로 들어간다 — 채널에는 질문 하나당 한 줄만 남는다.
     *
     * @return 답변을 실은 스레드. 첫 답변이면 이때 열린 스레드다
     */
    public String reportAnswer(QuestionAsked question, IncidentRef ref, Answer answer) {
        ReportEmbed embed = IncidentReports.answer(question, answer);
        List<ReportButton> buttons = IncidentReports.buttonsFor(answer);
        if (question.reanalysis()) {
            gateway.sendInThread(question.threadId(), embed, List.of(), buttons, ref);
            return question.threadId();
        }
        return report(question.channelId(), ref, "질문 — " + question.authorName(),
                embed, List.of(), buttons);
    }

    /**
     * 버튼이 돌린 작업의 결과를 그 건의 스레드에 보고한다. 스레드를 못 찾으면 채널에 올린다 —
     * 사람이 누른 결과가 어디에도 남지 않는 것이 채널이 한 줄 늘어나는 것보다 나쁘다.
     * 새 스레드를 열지는 않는다. 이미 리포트가 있는 건의 후속 보고라 열 자리가 없다.
     */
    public void reportProgress(IncidentRef ref, String channelId, ReportEmbed embed,
                               List<ReportButton> buttons) {
        threadOf(ref)
                .ifPresentOrElse(threadId -> gateway.sendInThread(threadId, embed, List.of(), buttons, ref),
                        () -> gateway.send(channelId, embed, buttons, ref));
    }

    /** 임베드로 세울 것이 없는 진행 보고. 도착지는 {@link #reportProgress}와 같다. */
    public void noticeProgress(IncidentRef ref, String channelId, String line) {
        notice(threadOf(ref).orElse(channelId), line);
    }

    /** 기동·종료·예산 경고처럼 특정 건에 묶이지 않는 한 줄 알림. 스레드를 열지 않는다. */
    public void notice(String channelId, String line) {
        gateway.sendNotice(channelId, MessageSplitter.split(line));
    }

    /**
     * 이미 스레드가 있으면 그 안에, 없으면 채널에 올리고 스레드를 연다.
     *
     * @return 리포트가 들어간 스레드
     */
    private String report(String channelId, IncidentRef ref, String threadName, ReportEmbed embed,
                          List<String> followUps, List<ReportButton> buttons) {
        Optional<String> threadId = threadOf(ref);
        if (threadId.isPresent()) {
            gateway.sendInThread(threadId.get(), embed, followUps, buttons, ref);
            return threadId.get();
        }

        String messageId = gateway.send(channelId, embed, buttons, ref);
        String openedThreadId = gateway.openThread(channelId, messageId, threadName(threadName));
        if (!followUps.isEmpty()) {
            gateway.sendInThread(openedThreadId, null, followUps, List.of(), ref);
        }
        store.markProcessed(ref.sourceKey(), ref.externalId(), openedThreadId);
        log.info("리포트 전송 — {}/{} 스레드 {}", ref.sourceKey(), ref.externalId(), openedThreadId);
        return openedThreadId;
    }

    /** 트리거가 분석 전에 남긴 이력에는 스레드가 비어 있으므로 빈 문자열도 없는 것으로 본다. */
    private Optional<String> threadOf(IncidentRef ref) {
        return store.threadIdOf(ref.sourceKey(), ref.externalId()).filter(id -> !id.isBlank());
    }

    private static String threadName(String name) {
        return name.length() <= THREAD_NAME_LIMIT ? name : name.substring(0, THREAD_NAME_LIMIT);
    }
}
