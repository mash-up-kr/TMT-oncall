package com.tmt.oncall.triage;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

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

    private Triages() {
    }

    static Triage parse(String output) {
        Optional<JsonNode> root = SkillOutput.read(output, "action_needed");
        // 필드가 없는 것과 false인 것을 가른다. 없으면 판정을 못 한 것이라 넘긴다.
        if (root.isEmpty() || !root.get().path("action_needed").isBoolean()) {
            return Triage.conservative("1차 분류가 약속된 형식으로 답하지 않아 분석으로 넘긴다");
        }
        return new Triage(
                root.get().path("action_needed").asBoolean(true),
                root.get().path("reason").asString("").strip(),
                root.get().path("severity").asString("unknown").strip());
    }
}
