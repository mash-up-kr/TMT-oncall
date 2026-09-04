package com.tmt.oncall.notify;

import com.tmt.oncall.store.OncallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 버튼 상호작용을 처리한다. JDA 이벤트에서 떼어 놓아 버튼 하나가 무엇을 하는지를
 * 토큰 없이 검증할 수 있게 한다.
 */
@Component
public class ButtonHandler {

    private static final Logger log = LoggerFactory.getLogger(ButtonHandler.class);

    private final OncallStore store;
    private final ObjectProvider<IncidentActions> actions;

    /**
     * 이미 실행을 시작한 건. 같은 버튼을 두 번 눌러 티켓·PR이 두 벌 생기는 것을 막는다.
     * 재시작하면 비므로 이것만으로는 중복을 다 막지 못한다 — 티켓 생성 자체의 멱등성
     * (Sentry 이슈 ID 라벨 조회 후 생성)은 PR 경로에서 따로 보장한다.
     */
    private final Set<IncidentRef> running = ConcurrentHashMap.newKeySet();

    public ButtonHandler(OncallStore store, ObjectProvider<IncidentActions> actions) {
        this.store = store;
        this.actions = actions;
    }

    /** @return 누른 사람에게만 보여줄 응답 문구 */
    public String handle(ReportButton button, IncidentRef ref, String actor) {
        log.info("버튼 {} — {}/{} ({})", button, ref.sourceKey(), ref.externalId(), actor);
        return switch (button) {
            case IGNORE -> ignore(ref, actor);
            case CREATE_PR -> createPullRequest(ref, actor);
            case REANALYZE -> reanalyze(ref, actor);
        };
    }

    private String ignore(IncidentRef ref, String actor) {
        store.suppress(ref.sourceKey(), ref.externalId(), actor);
        return "무시했습니다. 같은 건으로 다시 알리지 않는다 — `/oncall unmute "
                + ref.externalId() + "`로 해제할 수 있다.";
    }

    private String createPullRequest(IncidentRef ref, String actor) {
        if (store.isSuppressed(ref.sourceKey(), ref.externalId())) {
            return "무시 처리된 건이다. `/oncall unmute " + ref.externalId() + "`로 해제한 뒤 다시 눌러달라.";
        }
        if (!running.add(ref)) {
            return "이미 실행 중이다. 진행 상황은 이 스레드에 올라온다.";
        }
        IncidentActions target = actions.getIfAvailable();
        if (target == null) {
            running.remove(ref);
            return "수정·PR 경로가 아직 연결되지 않았다.";
        }
        try {
            target.createPullRequest(ref, actor);
        } catch (RuntimeException e) {
            // 실패한 채로 잠가 두면 사람이 다시 누를 길이 없어진다
            running.remove(ref);
            throw e;
        }
        return "PR 작업을 시작한다. 요청자는 " + actor + "로 기록된다.";
    }

    private String reanalyze(IncidentRef ref, String actor) {
        IncidentActions target = actions.getIfAvailable();
        if (target == null) {
            return "분석 경로가 아직 연결되지 않았다.";
        }
        target.reanalyze(ref, actor);
        return "다시 분석한다. 스레드에 남긴 힌트를 함께 반영한다.";
    }

    /** 실행이 끝난 건은 잠금을 푼다 — 결과를 보고 사람이 다시 누를 수 있어야 한다. */
    public void finished(IncidentRef ref) {
        running.remove(ref);
    }
}
