package com.tmt.oncall.triage;

import com.tmt.oncall.notify.Analysis;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 분석 스킬의 출력을 값으로 옮긴다. {@link Analysis}의 필드가 그대로 출력 계약이다.
 *
 * <pre>
 * {"cause": "...", "fix_plan": ["...", "..."], "impact": "...",
 *  "related_deploy": "...", "stack_excerpt": "...", "code_fix_possible": true}
 * </pre>
 *
 * <p>
 * 약속이 깨져도 리포트는 낸다 — 1차 분류가 이미 조치가 필요하다고 본 건이라 알리지 않는 쪽이
 * 더 나쁘다. 대신 본문을 원인 자리에 그대로 싣고 수정 경로는 닫는다. 형식을 지키지 못한 출력에서
 * 수정 계획만 골라 믿을 근거가 없다.
 */
final class Analyses {

    private static final String UNKNOWN = "확인이 필요합니다.";

    private Analyses() {
    }

    static Analysis parse(String output) {
        Optional<JsonNode> root = SkillOutput.read(output, "cause");
        if (root.isPresent()) {
            String cause = root.get().path("cause").asString("").strip();
            if (!cause.isBlank()) {
                return new Analysis(
                        cause,
                        fixPlan(root.get()),
                        text(root.get(), "impact"),
                        text(root.get(), "related_deploy"),
                        root.get().path("stack_excerpt").asString("").strip(),
                        root.get().path("code_fix_possible").asBoolean(false));
            }
        }
        return unparsed(output);
    }

    /** 원인 자리에 JSON 덩어리를 싣지 않는다. 건질 수 있으면 원인만, 아니면 형식이 깨졌다고 적는다. */
    private static Analysis unparsed(String output) {
        String cause = SkillOutput.salvage(output, "cause")
                .orElseGet(() -> "분석 결과를 읽지 못했습니다. 스킬이 약속된 형식으로 답하지 않았습니다.");
        return new Analysis(cause, List.of(), UNKNOWN, UNKNOWN, "", false);
    }

    private static String text(JsonNode root, String field) {
        String value = root.path(field).asString("").strip();
        return value.isEmpty() ? UNKNOWN : value;
    }

    private static List<String> fixPlan(JsonNode root) {
        JsonNode steps = root.path("fix_plan");
        if (!steps.isArray()) {
            return List.of();
        }
        List<String> plan = new ArrayList<>();
        steps.forEach(step -> {
            String line = step.asString("").strip();
            if (!line.isEmpty()) {
                plan.add(line);
            }
        });
        return plan;
    }
}
