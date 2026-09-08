package com.tmt.oncall.triage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스킬이 돌려준 출력을 JSON으로 읽는다. 세 스킬이 같은 방식으로 답하고 같은 방식으로 깨지므로
 * 읽는 쪽도 한 곳에 둔다.
 *
 * <p>
 * 관대하게 읽는다. 모델이 본문에 코드를 실으면 이스케이프가 어긋나기 쉬운데, 실제로 SQL의
 * {@code ESCAPE '\'} 하나 때문에 답변 전체가 JSON 덩어리째 채널에 올라간 적이 있다.
 * 스킬이 형식을 지키는 것과 별개로, 봇이 그 정도에 무너지지 않아야 한다.
 */
final class SkillOutput {

    private static final Logger log = LoggerFactory.getLogger(SkillOutput.class);

    /**
     * 표준 JSON이 아닌 것까지 받아들인다. 잘못된 백슬래시 이스케이프와 이스케이프하지 않은
     * 줄바꿈이 모델 출력에서 가장 흔한 두 가지다.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    private SkillOutput() {
    }

    /** @return 읽지 못하면 비어 있다. 무엇을 읽으려다 실패했는지 로그에 남는다 */
    static Optional<JsonNode> read(String output, String field) {
        String body = unfence(output);
        if (body.isBlank()) {
            log.warn("스킬 출력이 비어 있다 — {}", field);
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.isObject()) {
                return Optional.of(root);
            }
            log.warn("스킬 출력이 JSON 객체가 아니다 — {}", field);
        } catch (JacksonException e) {
            log.warn("스킬 출력을 JSON으로 읽지 못했다 — {}: {}", field, e.getOriginalMessage());
        }
        return Optional.empty();
    }

    /**
     * JSON으로는 못 읽었지만 본문 필드 하나는 건질 수 있을 때 쓴다. 사람에게 JSON 덩어리를
     * 보여주는 것보다는 낫다 — 나머지 필드는 믿지 않으므로 버튼은 붙지 않는다.
     *
     * @return 그 필드를 찾지 못하면 비어 있다
     */
    static Optional<String> salvage(String output, String field) {
        Matcher matcher = fieldPattern(field).matcher(unfence(output));
        if (!matcher.find()) {
            return Optional.empty();
        }
        log.warn("깨진 출력에서 {} 값만 건져 싣는다", field);
        return Optional.of(unescape(matcher.group(1)).strip()).filter(value -> !value.isBlank());
    }

    /** 모델이 JSON을 코드 펜스로 감싸 내보내는 경우가 잦다. 감싼 것만 벗기고 내용은 손대지 않는다. */
    static String unfence(String output) {
        String trimmed = output == null ? "" : output.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int start = trimmed.indexOf('\n');
        int end = trimmed.lastIndexOf("```");
        return start < 0 || end <= start ? trimmed : trimmed.substring(start + 1, end).strip();
    }

    /** 값 안의 이스케이프된 따옴표는 건너뛰고 닫는 따옴표를 찾는다. */
    private static Pattern fieldPattern(String field) {
        return Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                Pattern.DOTALL);
    }

    /** JSON 문자열 이스케이프를 되돌린다. 모르는 것은 백슬래시만 떼고 글자를 살린다. */
    private static String unescape(String raw) {
        StringBuilder text = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                text.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> text.append('\n');
                case 't' -> text.append('\t');
                case 'r' -> text.append('\r');
                default -> text.append(next);
            }
        }
        return text.toString();
    }
}
