package com.tmt.oncall.triage;

import com.tmt.oncall.notify.Analysis;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

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

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String UNKNOWN = "확인이 필요합니다.";

    private Analyses() {
    }

    static Analysis parse(String output) {
        JsonNode root = readTree(unfence(output));
        if (root == null) {
            return unparsed(output);
        }
        String cause = root.path("cause").asString("").strip();
        if (cause.isBlank()) {
            return unparsed(output);
        }
        return new Analysis(
                cause,
                fixPlan(root),
                text(root, "impact"),
                text(root, "related_deploy"),
                root.path("stack_excerpt").asString("").strip(),
                root.path("code_fix_possible").asBoolean(false));
    }

    private static Analysis unparsed(String output) {
        return new Analysis(output == null ? UNKNOWN : output.strip(),
                List.of(), UNKNOWN, UNKNOWN, "", false);
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

    /** 모델이 JSON을 코드 펜스로 감싸 내보내는 경우가 잦다. 감싼 것만 벗기고 내용은 손대지 않는다. */
    private static String unfence(String output) {
        String trimmed = output == null ? "" : output.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int start = trimmed.indexOf('\n');
        int end = trimmed.lastIndexOf("```");
        return start < 0 || end <= start ? trimmed : trimmed.substring(start + 1, end).strip();
    }

    private static JsonNode readTree(String output) {
        if (output.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(output);
            return root.isObject() ? root : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
