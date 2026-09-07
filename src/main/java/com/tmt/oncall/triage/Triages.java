package com.tmt.oncall.triage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 1차 분류 스킬의 출력을 값으로 옮긴다. 스킬은 아래 형태를 돌려주기로 약속돼 있다.
 *
 * <pre>
 * {"action_needed": true, "reason": "...", "severity": "high|medium|low"}
 * </pre>
 *
 * <p>
 * 약속이 깨지면 조치가 필요한 것으로 본다 — 질문 경로와 폴백 방향이 반대다. 질문은 답을 못 받은
 * 사람이 다시 물으면 되지만, 에러는 여기서 걸러내면 아무도 모르는 채로 묻힌다. 헛되이 한 번 더
 * 분석하는 비용이 장애를 놓치는 것보다 싸다.
 */
final class Triages {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Triages() {
    }

    static Triage parse(String output) {
        JsonNode root = readTree(unfence(output));
        // 필드가 없는 것과 false인 것을 가른다. 없으면 판정을 못 한 것이라 넘긴다.
        if (root == null || !root.path("action_needed").isBoolean()) {
            return Triage.conservative("1차 분류가 약속된 형식으로 답하지 않아 분석으로 넘긴다");
        }
        return new Triage(
                root.path("action_needed").asBoolean(true),
                root.path("reason").asString("").strip(),
                root.path("severity").asString("unknown").strip());
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
