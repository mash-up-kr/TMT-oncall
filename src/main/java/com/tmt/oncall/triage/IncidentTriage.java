package com.tmt.oncall.triage;

import com.tmt.oncall.agent.AgentCall;
import com.tmt.oncall.agent.AgentResult;
import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.notify.Analysis;
import com.tmt.oncall.notify.DiscordNotifier;
import com.tmt.oncall.notify.IncidentRef;
import com.tmt.oncall.notify.IncidentReports;
import com.tmt.oncall.store.IncidentAnalysis;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.SentryIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 에러 경로를 엮는다 — 싼 모델로 조치가 필요한 건인지 먼저 거르고, 통과한 것만 소스를 읽는
 * 분석으로 넘겨 리포트를 낸다.
 *
 * <p>
 * 걸러낸 건은 채널에 올리지 않고 처리했다고만 표시한다. 노이즈까지 리포트로 만들면 정작
 * 봐야 할 건이 묻히고, 표시하지 않으면 폴링마다 같은 건을 다시 분류하게 된다.
 */
@Component
public class IncidentTriage {

    private static final Logger log = LoggerFactory.getLogger(IncidentTriage.class);

    /** 봇은 스킬 이름만 알고 판정 기준은 마켓플레이스 플러그인이 갖는다. */
    static final String TRIAGE_SKILL = "incident-triage";
    static final String ANALYZE_SKILL = "incident-analyze";

    private final OncallProperties properties;
    private final AgentRunner runner;
    private final DiscordNotifier notifier;
    private final OncallStore store;

    IncidentTriage(OncallProperties properties, AgentRunner runner, DiscordNotifier notifier,
                   OncallStore store) {
        this.properties = properties;
        this.runner = runner;
        this.notifier = notifier;
        this.store = store;
    }

    /**
     * 감지 스레드에서 떼어 다른 스레드로 넘긴다. 분류와 분석은 분 단위로 걸리는데 폴링 스레드를
     * 붙잡고 있으면 그동안 나는 다른 이슈를 감지하지 못한다.
     */
    @Async
    @EventListener
    public void on(IncidentDetected event) {
        handle(event);
    }

    void handle(IncidentDetected event) {
        SentryIssue issue = event.issue();
        IncidentRef ref = IncidentRef.sentry(issue.id());

        if (store.isSuppressed(ref.sourceKey(), ref.externalId())) {
            log.debug("무시 처리된 건이라 분석하지 않는다 — {}", issue.shortId());
            return;
        }
        // 트리거는 분석에 넘기기 전에 이력을 남기므로 처리 시각만으로는 완료 여부를 알 수 없다.
        // 완료 표시는 리포트를 낸 스레드다 — 재시작 뒤에도 이미 알린 건을 다시 분석하지 않는다.
        if (store.threadIdOf(ref.sourceKey(), ref.externalId()).filter(id -> !id.isBlank()).isPresent()) {
            log.debug("이미 리포트를 낸 건이라 다시 분석하지 않는다 — {}", issue.shortId());
            return;
        }

        if (!needsAction(event, ref)) {
            return;
        }

        AgentResult analyzed = runner.run(AgentCall.of(
                CallPath.ANALYZE, ANALYZE_SKILL, prompt(event), event.target().workspacePath()));
        if (!analyzed.isOk()) {
            log.warn("분석에 실패했다 — {}: {}", issue.shortId(), analyzed.message());
            notifier.notice(event.target().discordChannelId(),
                    IncidentReports.analysisFailed(issue, analyzed.message()));
            return;
        }

        Analysis analysis = Analyses.parse(analyzed.message());
        String threadId = notifier.reportIncident(event, analysis);
        store.saveAnalysis(new IncidentAnalysis(ref.sourceKey(), ref.externalId(),
                issue.title(), plan(analysis), analysis.stackExcerpt(), issue.permalink(),
                issue.lastSeen()));
        store.markProcessed(ref.sourceKey(), ref.externalId(), threadId);
    }

    /**
     * 분류는 한 번만 산다. 답을 남겨 두지 않으면 중복 창이 지날 때마다, 재시도로 들어올 때마다
     * 같은 이슈에 같은 답을 다시 사게 된다.
     */
    private boolean needsAction(IncidentDetected event, IncidentRef ref) {
        Optional<Boolean> decided = store.triageDecision(ref.sourceKey(), ref.externalId());
        if (decided.isPresent()) {
            if (!decided.get()) {
                log.debug("앞서 걸러낸 건이라 다시 분류하지 않는다 — {}", event.issue().shortId());
            }
            return decided.get();
        }

        Triage triage = triage(event);
        store.saveTriageDecision(ref.sourceKey(), ref.externalId(), triage.actionNeeded());
        if (!triage.actionNeeded()) {
            // 채널에는 올리지 않는다. 걸러낸 근거는 로그에만 남겨 두고 나중에 기준을 손볼 때 본다.
            log.info("조치가 필요 없다고 판정해 분석하지 않는다 — {} [{}] {}",
                    event.issue().shortId(), triage.severity(), triage.reason());
        }
        return triage.actionNeeded();
    }

    /**
     * 1차 분류는 소스를 읽지 않는다. 대상 클론을 주면 읽지 않기로 한 소스가 CLI의 시야에 들어와
     * 싼 모델로 걸러내려던 이유가 없어지므로 빈 스크래치 디렉터리를 준다.
     */
    private Triage triage(IncidentDetected event) {
        AgentResult result = runner.run(AgentCall.of(
                CallPath.TRIAGE, TRIAGE_SKILL, prompt(event), properties.agent().scratchPath()));
        if (!result.isOk()) {
            // 분류가 죽었다고 에러를 묻지 않는다 — 형식이 깨졌을 때와 같은 이유로 분석까지 보낸다.
            log.warn("1차 분류에 실패해 분석으로 넘긴다 — {}: {}", event.issue().shortId(), result.message());
            return Triage.conservative(result.message());
        }
        return Triages.parse(result.message());
    }

    /** 두 스킬에 같은 재료를 준다 — 분류와 분석의 차이는 소스를 읽느냐지 입력이 아니다. */
    private String prompt(IncidentDetected event) {
        return issueSummary(event.issue()) + eventJson(event, properties.agent().maxEventChars());
    }

    static String issueSummary(SentryIssue issue) {
        return """
                Sentry 이슈 ID: %s
                짧은 ID: %s
                제목: %s
                발생 위치: %s
                레벨: %s
                상태: %s
                발생 횟수: %d
                최초 발생: %s
                최근 발생: %s
                Sentry 링크: %s
                """.formatted(issue.id(), issue.shortId(), issue.title(), issue.culprit(),
                issue.level(), issue.substatus(), issue.count(),
                issue.firstSeen(), issue.lastSeen(), issue.permalink());
    }

    /**
     * 대표 이벤트에는 상한이 없다. 스택트레이스는 앞쪽에 있고 뒤로 갈수록 브레드크럼·컨텍스트라
     * 뒤를 버린다. 자르지 않으면 컨텍스트를 넘겨, CLI가 호출료를 다 쓴 뒤에 실패한다.
     */
    static String eventJson(IncidentDetected event, int maxChars) {
        String json = event.latestEventJson();
        if (json == null || json.isBlank()) {
            return "%n대표 이벤트(JSON):%n(없음)%n".formatted();
        }
        if (json.length() > maxChars) {
            log.info("대표 이벤트가 길어 {}자에서 잘랐다 — {} ({}자)",
                    maxChars, event.issue().shortId(), json.length());
            json = json.substring(0, maxChars) + "%n… (길이 상한 %d자에서 잘림)".formatted(maxChars);
        }
        return "%n대표 이벤트(JSON):%n%s%n".formatted(json);
    }

    /** 수정 계획은 저장할 때 한 덩어리 문장으로 굳힌다 — 수정 에이전트가 받는 입력이 이 형태다. */
    static String plan(Analysis analysis) {
        return String.join("\n", analysis.fixPlan());
    }
}
