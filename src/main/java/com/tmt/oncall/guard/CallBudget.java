package com.tmt.oncall.guard;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.core.Usage;
import com.tmt.oncall.store.OncallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 호출 상한과 비용 상한을 지킨다. 봇이 통째로 멈춰 장애를 놓치는 것이 최악이므로
 * 한도에 다다르면 비싼 경로부터 끊고 알림 경로는 마지막까지 살린다.
 * <p>
 * 비용 집계 여부는 {@code oncall.agent.billing}에서 파생된다. 구독제에서는
 * 달러 집계와 단계적 차단이 빠지고 시간당·일일 호출 상한만 남는다.
 */
@Component
public class CallBudget {

    private static final Logger log = LoggerFactory.getLogger(CallBudget.class);

    /** 프롬프트 캐싱 단가는 입력 단가에서 파생한다. */
    private static final double CACHE_WRITE_MULTIPLIER = 1.25;
    private static final double CACHE_READ_MULTIPLIER = 0.10;

    private static final double WARN_RATIO = 0.70;
    private static final double FIX_BLOCKED_RATIO = 0.85;
    private static final double EXHAUSTED_RATIO = 1.00;

    private final OncallProperties properties;
    private final OncallStore store;
    private final ZoneId zone = ZoneId.systemDefault();

    CallBudget(OncallProperties properties, OncallStore store) {
        this.properties = properties;
        this.store = store;
        log.info("과금 모드: {} (비용 집계 {})",
                properties.agent().billing(),
                tracksCost() ? "켬" : "끔 — 호출 횟수 상한만 적용");
    }

    private boolean tracksCost() {
        return properties.agent().billing().tracksCost();
    }

    public enum Stage {
        /** 여유 있음 */
        NORMAL,
        /** 70% — 채널에 경고 */
        WARN,
        /** 85% — 수정·PR 차단, 분석·답변은 계속 */
        FIX_BLOCKED,
        /** 100% — 1차 분류만 유지, 에러 알림은 끊기지 않는다 */
        TRIAGE_ONLY
    }

    public record Decision(boolean allowed, String reason) {

        static Decision allow() {
            return new Decision(true, null);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision check(CallPath path) {
        OncallProperties.Guard guard = properties.guard();

        int lastHour = store.countCallsSince(Instant.now().minus(1, ChronoUnit.HOURS));
        if (lastHour >= guard.maxCallsPerHour()) {
            return Decision.deny("시간당 호출 상한 %d회에 걸렸다 (현재 %d회)"
                    .formatted(guard.maxCallsPerHour(), lastHour));
        }

        int lastDay = store.countCallsSince(Instant.now().minus(1, ChronoUnit.DAYS));
        if (lastDay >= guard.maxCallsPerDay()) {
            return Decision.deny("일일 호출 상한 %d회에 걸렸다 (현재 %d회)"
                    .formatted(guard.maxCallsPerDay(), lastDay));
        }

        if (!tracksCost()) {
            return Decision.allow();
        }

        Stage stage = stage();
        return switch (stage) {
            case TRIAGE_ONLY -> path == CallPath.TRIAGE
                    ? Decision.allow()
                    : Decision.deny("월 비용 상한을 다 썼다 — 1차 분류만 유지한다 (%s)".formatted(costSummary()));
            case FIX_BLOCKED -> path == CallPath.FIX
                    ? Decision.deny("월 비용의 85%%를 넘겨 수정·PR 경로를 막았다 (%s)".formatted(costSummary()))
                    : Decision.allow();
            case NORMAL, WARN -> Decision.allow();
        };
    }

    /** 호출이 끝난 뒤 실제 토큰으로 비용을 누적한다. */
    public void record(CallPath path, Usage usage) {
        OncallProperties.Agent.Model model = properties.agent().modelFor(path);
        double cost = costOf(model, usage);
        store.recordCall(path, model.id(), usage, cost);

        if (tracksCost()) {
            log.info("{} 호출 기록 — 모델={}, 토큰={}, 비용=${}, 누적={}",
                    path, model.id(), usage.totalTokens(), "%.4f".formatted(cost), costSummary());
        } else {
            log.info("{} 호출 기록 — 모델={}, 토큰={}", path, model.id(), usage.totalTokens());
        }
    }

    public Stage stage() {
        if (!tracksCost()) {
            return Stage.NORMAL;
        }
        double ratio = monthlyRatio();
        if (ratio >= EXHAUSTED_RATIO) {
            return Stage.TRIAGE_ONLY;
        }
        if (ratio >= FIX_BLOCKED_RATIO) {
            return Stage.FIX_BLOCKED;
        }
        if (ratio >= WARN_RATIO) {
            return Stage.WARN;
        }
        return Stage.NORMAL;
    }

    public double monthlyCost() {
        return store.costSince(monthStart());
    }

    public double monthlyRatio() {
        double limit = properties.guard().maxCostPerMonth();
        return limit <= 0 ? 0d : monthlyCost() / limit;
    }

    double costOf(OncallProperties.Agent.Model model, Usage usage) {
        double input = usage.inputTokens() * model.inputPerMtok();
        double output = usage.outputTokens() * model.outputPerMtok();
        double cacheWrite = usage.cacheCreationTokens() * model.inputPerMtok() * CACHE_WRITE_MULTIPLIER;
        double cacheRead = usage.cacheReadTokens() * model.inputPerMtok() * CACHE_READ_MULTIPLIER;
        return (input + output + cacheWrite + cacheRead) / 1_000_000d;
    }

    private String costSummary() {
        return "$%.2f / $%.2f".formatted(monthlyCost(), properties.guard().maxCostPerMonth());
    }

    private Instant monthStart() {
        return YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant();
    }
}
