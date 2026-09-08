package com.tmt.oncall.notify;

import com.tmt.oncall.store.OncallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 버튼 상호작용을 처리한다. JDA 이벤트에서 떼어 놓아 버튼 하나가 무엇을 하는지를
 * 토큰 없이 검증할 수 있게 한다.
 */
@Component
public class ButtonHandler {

    private static final Logger log = LoggerFactory.getLogger(ButtonHandler.class);

    /**
     * 이보다 오래된 잠금은 만료로 본다. 에이전트 타임아웃(10분)에 빌드·PR 시간을 얹어도
     * 남는 값이라, 실행이 조용히 끝나 {@link #finished(IncidentRef)}가 불리지 않아도
     * 그 건이 재시작 전까지 잠긴 채 남지 않는다.
     */
    private static final Duration LOCK_EXPIRY = Duration.ofMinutes(30);

    private final OncallStore store;
    private final ObjectProvider<IncidentActions> actions;
    private final Clock clock;

    /**
     * 실행을 시작한 건과 그 시각. 같은 버튼을 두 번 눌러 티켓·PR이 두 벌 생기는 것을 막는다.
     * 재시작하면 비므로 이것만으로는 중복을 다 막지 못한다 — 티켓 생성 자체의 멱등성
     * (Sentry 이슈 ID 라벨 조회 후 생성)은 PR 경로에서 따로 보장한다.
     */
    private final Map<IncidentRef, Instant> running = new ConcurrentHashMap<>();

    @Autowired
    ButtonHandler(OncallStore store, ObjectProvider<IncidentActions> actions) {
        this(store, actions, Clock.systemUTC());
    }

    ButtonHandler(OncallStore store, ObjectProvider<IncidentActions> actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
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
        return "무시했습니다. 같은 건으로 다시 알리지 않습니다. `/oncall unmute "
                + ref.externalId() + "`로 해제할 수 있습니다.";
    }

    private String createPullRequest(IncidentRef ref, String actor) {
        if (store.isSuppressed(ref.sourceKey(), ref.externalId())) {
            return "이미 무시 처리된 건입니다. `/oncall unmute " + ref.externalId()
                    + "`로 해제한 뒤 다시 눌러주세요.";
        }
        if (!claim(ref)) {
            return "이미 실행 중입니다. 진행 상황은 이 스레드에 올라옵니다.";
        }
        IncidentActions target = actions.getIfAvailable();
        if (target == null) {
            running.remove(ref);
            return "수정·PR 경로가 아직 연결되지 않았습니다.";
        }
        try {
            target.createPullRequest(ref, actor);
        } catch (RuntimeException e) {
            // 실패한 채로 잠가 두면 사람이 다시 누를 길이 없어진다
            running.remove(ref);
            throw e;
        }
        return "PR 작업을 시작합니다. 요청자는 " + actor + "로 기록됩니다.";
    }

    /**
     * 재분석도 PR 경로와 같은 잠금을 쓴다. 한 건에는 한 작업만 돈다 — 잠그지 않으면 연타한
     * 만큼 분석 호출이 나가고, 더 나쁘게는 재분석이 끝나며 푸는 잠금이 **그 사이 돌던 PR 작업의
     * 것**이라 같은 건에 티켓·PR이 두 벌 생긴다.
     */
    private String reanalyze(IncidentRef ref, String actor) {
        IncidentActions target = actions.getIfAvailable();
        if (target == null) {
            return "분석 경로가 아직 연결되지 않았습니다.";
        }
        if (!claim(ref)) {
            return "이미 실행 중입니다. 진행 상황은 이 스레드에 올라옵니다.";
        }
        try {
            target.reanalyze(ref, actor);
        } catch (RuntimeException e) {
            // 실패한 채로 잠가 두면 사람이 다시 누를 길이 없다
            running.remove(ref);
            throw e;
        }
        return "다시 분석합니다. 스레드에 남긴 힌트를 함께 반영합니다.";
    }

    /** @return 잠금을 새로 얻었으면 true. 만료된 잠금은 없는 것으로 보고 다시 준다 */
    private boolean claim(IncidentRef ref) {
        Instant now = clock.instant();
        Instant expiredBefore = now.minus(LOCK_EXPIRY);
        AtomicBoolean granted = new AtomicBoolean();
        running.compute(ref, (key, heldSince) -> {
            if (heldSince != null && heldSince.isAfter(expiredBefore)) {
                return heldSince;
            }
            granted.set(true);
            return now;
        });
        return granted.get();
    }

    /** 실행이 끝난 건은 잠금을 푼다 — 결과를 보고 사람이 다시 누를 수 있어야 한다. */
    public void finished(IncidentRef ref) {
        running.remove(ref);
    }
}
