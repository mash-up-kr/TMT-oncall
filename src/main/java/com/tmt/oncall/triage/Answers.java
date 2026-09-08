package com.tmt.oncall.triage;

import com.tmt.oncall.notify.Answer;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 *
 * <p>
 * JSON으로 읽히지 않으면 본문만 건져 싣고, 그것도 안 되면 원문 앞에 형식이 깨졌다고 적는다.
 * JSON 덩어리를 그대로 답변이라고 올리면 읽는 사람은 봇이 고장 난 것으로 본다.
 */
final class Answers {

    private static final String BROKEN = "스킬이 약속된 형식으로 답하지 않아 원문을 그대로 싣습니다.";

    private Answers() {
    }

    static Answer parse(String output) {
        Optional<JsonNode> root = SkillOutput.read(output, "answer");
        if (root.isPresent()) {
            String text = root.get().path("answer").asString("").strip();
            if (!text.isBlank()) {
                return new Answer(text, fixPlan(root.get()),
                        root.get().path("code_fix_needed").asBoolean(false));
            }
        }
        return SkillOutput.salvage(output, "answer")
                .map(Answer::plain)
                .orElseGet(() -> Answer.plain(BROKEN + "\n\n" + safe(output)));
    }

    private static String safe(String output) {
        return output == null ? "" : output.strip();
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
