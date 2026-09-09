package com.tmt.oncall.triage;

import com.tmt.oncall.action.JiraTicketAgent;
import com.tmt.oncall.action.PullRequestAgent;
import com.tmt.oncall.action.PullRequestRequest;
import com.tmt.oncall.action.PullRequestResult;
import com.tmt.oncall.action.TicketRequest;
import com.tmt.oncall.action.TicketResult;
import com.tmt.oncall.agent.AgentCall;
import com.tmt.oncall.agent.AgentResult;
import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.notify.Analysis;
import com.tmt.oncall.notify.ButtonHandler;
import com.tmt.oncall.notify.DiscordNotifier;
import com.tmt.oncall.notify.IncidentActions;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.notify.IncidentReports;
import com.tmt.oncall.store.IncidentAnalysis;
import com.tmt.oncall.store.OncallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 승인 버튼이 실제로 돌리는 일. 누른 결과는 전부 그 건의 스레드로 보고한다 — 누른 사람에게만
 * 보이는 응답은 '시작합니다'까지고, 무엇이 됐는지는 스레드를 봐야 한다.
 *
 * <p>
 * 재료는 메모리가 아니라 저장소에서 꺼낸다. 버튼은 봇이 재시작한 뒤에도 눌리고, 그때
 * 요약·수정 계획이 없으면 티켓도 PR도 만들 수 없다.
 */
@Component
public class ButtonActions implements IncidentActions {

    private static final Logger log = LoggerFactory.getLogger(ButtonActions.class);

    /** 재분석에 싣는 힌트 개수. 스레드가 길어져도 입력이 무한정 늘지 않게 최근 것만 본다. */
    private static final int HINT_LIMIT = 5;

    private final OncallProperties properties;
    private final OncallStore store;
    private final JiraTicketAgent jira;
    private final PullRequestAgent pullRequests;
    private final AgentRunner runner;
    private final DiscordNotifier notifier;
    private final ButtonHandler buttonHandler;

    ButtonActions(OncallProperties properties, OncallStore store, JiraTicketAgent jira,
                  PullRequestAgent pullRequests, AgentRunner runner, DiscordNotifier notifier,
                  ButtonHandler buttonHandler) {
        this.properties = properties;
        this.store = store;
        this.jira = jira;
        this.pullRequests = pullRequests;
        this.runner = runner;
        this.notifier = notifier;
        this.buttonHandler = buttonHandler;
    }

    @Override
    public void createPullRequest(IncidentRef ref, String requestedBy) {
        // 어디서 끝나든 잠금은 푼다. 남겨 두면 그 건의 버튼이 재시작 전까지 잠긴 채로 있다.
        try {
            store.analysisOf(ref.sourceKey(), ref.externalId())
                    .ifPresentOrElse(analysis -> openPullRequest(ref, analysis, requestedBy),
                            () -> reportMissing(ref));
        } catch (RuntimeException e) {
            log.error("PR 경로가 예외로 끝났다 — {}/{}: {}",
                    ref.sourceKey(), ref.externalId(), e.getMessage(), e);
            notice(ref, "PR 작업이 오류로 중단됐습니다 — " + e.getMessage());
        } finally {
            buttonHandler.finished(ref);
        }
    }

    @Override
    public void reanalyze(IncidentRef ref, String requestedBy) {
        try {
            store.analysisOf(ref.sourceKey(), ref.externalId())
                    .ifPresentOrElse(analysis -> runAnalysis(ref, analysis, requestedBy),
                            () -> reportMissing(ref));
        } catch (RuntimeException e) {
            log.error("재분석이 예외로 끝났다 — {}/{}: {}",
                    ref.sourceKey(), ref.externalId(), e.getMessage(), e);
            notice(ref, "재분석이 오류로 중단됐습니다 — " + e.getMessage());
        } finally {
            buttonHandler.finished(ref);
        }
    }

    private void openPullRequest(IncidentRef ref, IncidentAnalysis analysis, String requestedBy) {
        Target target = properties.target();
        String ticketKey = createTicket(ref, target, analysis, requestedBy);

        PullRequestResult result = pullRequests.open(new PullRequestRequest(
                target, ticketKey, analysis.summary(), analysis.plan(), requestedBy));

        if (result instanceof PullRequestResult.BuildFailed) {
            // 빌드 실패는 우리 수정이 틀렸다는 뜻이라 사람이 할 일이 다르다. 한 줄이 아니라 리포트로 낸다.
            notifier.reportProgress(ref, target.discordChannelId(),
                    IncidentReports.buildFailed(target.buildCommand()), List.of());
            return;
        }
        notice(ref, result.message());
    }

    /**
     * 티켓 생성 실패는 PR을 막지 않는다 — 이 자동화의 산출물은 수정과 PR이고 티켓은 기록이다.
     *
     * @return 만들지 못했으면 {@code null}. 키를 지어내지 않는다
     */
    private String createTicket(IncidentRef ref, Target target, IncidentAnalysis analysis,
                                String requestedBy) {
        TicketResult result = jira.create(new TicketRequest(
                target, analysis.summary(), analysis.sentryIssueUrl(), analysis.occurredAt(),
                analysis.stackExcerpt(), analysis.plan(), threadReference(ref), requestedBy));

        return switch (result) {
            case TicketResult.Created created -> {
                notice(ref, "티켓을 만들었습니다 — " + created.url());
                yield created.key();
            }
            case TicketResult.Failed failed -> {
                notifier.reportProgress(ref, target.discordChannelId(),
                        IncidentReports.ticketFailed(failed.reason()), List.of());
                yield null;
            }
        };
    }

    private void runAnalysis(IncidentRef ref, IncidentAnalysis stored, String requestedBy) {
        Target target = properties.target();
        List<String> hints = store.threadIdOf(ref.sourceKey(), ref.externalId())
                .map(threadId -> store.threadHints(threadId, HINT_LIMIT))
                .orElse(List.of());

        AgentResult result = runner.run(AgentCall.of(
                CallPath.ANALYZE, IncidentTriage.ANALYZE_SKILL,
                reanalyzePrompt(stored, hints), target.workspacePath()));
        if (!result.isOk()) {
            log.warn("재분석에 실패했다 — {}/{}: {}", ref.sourceKey(), ref.externalId(), result.message());
            notice(ref, "다시 분석하지 못했습니다 — " + result.message());
            return;
        }

        Analysis analysis = Analyses.parse(result.message());
        notifier.reportProgress(ref, target.discordChannelId(),
                IncidentReports.reanalysis(analysis, requestedBy),
                IncidentReports.buttonsFor(analysis));
        // 이후에 눌리는 'PR 만들기'는 방금 나온 계획으로 돌아야 한다.
        store.saveAnalysis(new IncidentAnalysis(ref.sourceKey(), ref.externalId(),
                stored.summary(), IncidentTriage.plan(analysis),
                analysis.stackExcerpt().isBlank() ? stored.stackExcerpt() : analysis.stackExcerpt(),
                stored.sentryIssueUrl(), stored.occurredAt()));
    }

    /**
     * 재분석은 첫 리포트의 재료에 스레드 힌트를 얹어 다시 묻는다. 이슈 원본(대표 이벤트 JSON)은
     * 남겨 두지 않는다 — 크기가 커서 매 건 쌓이고, 스택 요약과 Sentry 링크로 같은 곳을 짚을 수 있다.
     */
    private static String reanalyzePrompt(IncidentAnalysis stored, List<String> hints) {
        String hintBlock = hints.isEmpty()
                ? "(스레드에 남은 힌트 없음)"
                : String.join("\n", hints);
        return """
                먼저 낸 분석이 충분하지 않아 다시 분석한다.

                제목: %s
                Sentry 링크: %s
                발생 시각: %s

                먼저 세운 수정 계획:
                %s

                스택 요약:
                %s

                스레드에 남긴 힌트:
                %s
                """.formatted(stored.summary(), stored.sentryIssueUrl(), stored.occurredAt(),
                stored.plan(), stored.stackExcerpt(), hintBlock);
    }

    /**
     * 티켓 본문이 가리킬 스레드. 길드 ID를 들고 있지 않아 링크를 만들지 못하므로 ID만 남긴다 —
     * 사람이 Discord에서 찾아갈 수 있는 값이고, 지어낸 주소보다 낫다.
     */
    /** 티켓에서 스레드로 갈 수 있어야 한다 — 배경을 스레드에 두기로 한 이상 주소가 곧 본문이다. */
    private String threadReference(IncidentRef ref) {
        return store.threadIdOf(ref.sourceKey(), ref.externalId())
                .map(notifier::threadUrl)
                .filter(url -> !url.isBlank())
                .orElse("");
    }

    private void reportMissing(IncidentRef ref) {
        log.warn("분석 결과가 없어 버튼을 처리하지 못했다 — {}/{}", ref.sourceKey(), ref.externalId());
        notice(ref, IncidentReports.analysisMissing());
    }

    private void notice(IncidentRef ref, String line) {
        notifier.noticeProgress(ref, properties.target().discordChannelId(), line);
    }
}
