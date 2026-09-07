package com.tmt.oncall.triage;

import com.tmt.oncall.notify.Answer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 답변 스킬의 출력을 값으로 옮긴다. 스킬은 아래 형태를 돌려주기로 약속돼 있다.
 *
 * <pre>
 * {"answer": "...", "code_fix_needed": true, "fix_plan": ["...", "..."]}
 * </pre>
 *
 * <p>
 * 약속이 깨져도 답변 본문은 살린다 — 형식 하나 때문에 질문한 사람이 아무 답도 못 받는 것이
 * 버튼 없는 답변보다 나쁘다. 대신 수정 경로는 닫는다. 형식을 지키지 못한 출력에서
 * 수정이 필요하다는 판단만 골라 믿을 근거가 없다.
 */
final class Answers {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Answers() {
    }

    static Answer parse(String output) {
        JsonNode root = readTree(unfence(output));
        if (root == null) {
            return Answer.plain(output.strip());
        }
        String text = root.path("answer").asString("");
        if (text.isBlank()) {
            return Answer.plain(output.strip());
        }
        return new Answer(text.strip(), fixPlan(root), root.path("code_fix_needed").asBoolean(false));
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
