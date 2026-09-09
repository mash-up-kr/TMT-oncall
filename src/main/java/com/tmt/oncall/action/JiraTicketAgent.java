package com.tmt.oncall.action;

import com.tmt.oncall.config.OncallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코드 수정이 필요할 때만 티켓을 만든다. 분석·답변만 하고 끝나는 경로는 티켓을 남기지 않는다 —
 * Discord 스레드가 기록이다.
 *
 * <p>
 * Atlassian MCP가 아니라 REST API를 직접 부른다. MCP는 OAuth라 헤드리스 VM에서
 * 재인증에 사람이 붙어야 한다.
 */
@Component
public class JiraTicketAgent {

    private static final Logger log = LoggerFactory.getLogger(JiraTicketAgent.class);

    /** 사람이 만든 티켓과 구분하는 표식. 잘못 만들어진 티켓을 이 라벨로 찾아 지울 수 있다. */
    private static final String AUTO_LABEL = "oncall-auto";

    /** TMT 프로젝트의 Task 타입 이름. */
    private static final String ISSUE_TYPE = "작업";

    /** 팀의 Summary 규칙은 {@code [태그] 본문}이다. 사람이 만든 티켓과 한눈에 갈린다. */
    private static final String SUMMARY_TAG = "[온콜] ";

    /** 없으면 생성이 거절되는 필수 필드. 자동 생성분은 승인된 개별 수정이라 늘 L3다. */
    private static final String DECISION_LEVEL_FIELD = "customfield_10147";
    private static final String DECISION_LEVEL = "L3";

    private static final DateTimeFormatter OCCURRED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private final OncallProperties properties;
    private final RestClient restClient;

    @Autowired
    JiraTicketAgent(OncallProperties properties) {
        this(properties, RestClient.builder());
    }

    JiraTicketAgent(OncallProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        OncallProperties.Jira jira = properties.jira();
        this.restClient = builder
                .baseUrl(jira.baseUrl())
                .defaultHeaders(headers -> headers.setBasicAuth(jira.email(), jira.apiToken()))
                .build();
    }

    public TicketResult create(TicketRequest request) {
        try {
            JsonNode created = restClient.post()
                    .uri("/rest/api/3/issue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(request))
                    .retrieve()
                    .body(JsonNode.class);

            String key = created == null ? "" : created.path("key").asString("");
            if (key.isBlank()) {
                return new TicketResult.Failed("Jira 응답에 이슈 키가 없다");
            }
            String url = properties.jira().baseUrl() + "/browse/" + key;
            log.info("티켓 생성 — {} ({} 요청)", key, request.requestedBy());
            return new TicketResult.Created(key, url);
        } catch (RestClientException e) {
            log.error("티켓 생성에 실패했다: {}", e.getMessage());
            return new TicketResult.Failed(e.getMessage());
        }
    }

    private Map<String, Object> payload(TicketRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", request.target().jiraProjectKey()));
        fields.put("issuetype", Map.of("name", ISSUE_TYPE));
        fields.put("summary", SUMMARY_TAG + request.summary());
        fields.put("labels", List.of(AUTO_LABEL));
        fields.put(DECISION_LEVEL_FIELD, Map.of("value", DECISION_LEVEL));
        fields.put("description", description(request));
        return Map.of("fields", fields);
    }

    /**
     * Jira Cloud v3는 본문을 ADF로 받는다. 본문 구조는 팀의 Task 템플릿(jira-creator)을 따른다 —
     * 목적·작업 내용·체크리스트·관련. 봇이 만든 티켓만 다른 모양이면 보드에서 읽는 리듬이 끊긴다.
     *
     * <p>
     * 값이 없는 항목은 헤딩째로 뺀다. 질문에서 시작한 티켓에는 Sentry 이슈도 스택도 없는데,
     * 빈 값을 헤딩과 함께 남기면 읽는 사람이 링크가 깨진 줄 안다.
     */
    private Object description(TicketRequest request) {
        List<Object> content = new ArrayList<>();

        content.add(heading("🎯 목적"));
        content.add(paragraph(request.summary()));

        content.add(heading("📋 작업 내용"));
        content.add(paragraph("온콜 봇이 감지·분석한 건입니다. %s님이 'PR 만들기'로 승인했습니다."
                .formatted(request.requestedBy())));
        content.add(paragraph("발생 시각: " + OCCURRED_AT.format(request.occurredAt())));

        List<String> steps = planSteps(request.plan());
        if (!steps.isEmpty()) {
            content.add(heading("✅ 체크리스트"));
            content.add(taskList(steps));
        }

        if (isPresent(request.stackSummary())) {
            content.add(heading("스택 요약"));
            content.add(codeBlock(request.stackSummary()));
        }

        content.add(heading("🔗 관련"));
        if (isPresent(request.sentryIssueUrl())) {
            content.add(paragraph("Sentry: " + request.sentryIssueUrl()));
        }
        content.add(paragraph(isPresent(request.threadUrl())
                ? "Discord 스레드: " + request.threadUrl()
                : "Discord 스레드를 찾지 못했습니다."));

        return Map.of("type", "doc", "version", 1, "content", content);
    }

    /**
     * 수정 계획은 저장할 때 줄바꿈으로 이어 붙인 한 덩어리라 여기서 다시 줄로 가른다. 앞에 붙은
     * 번호는 뗀다 — 체크리스트가 자체 번호를 매기므로 그대로 두면 두 벌이 된다.
     */
    private static List<String> planSteps(String plan) {
        if (!isPresent(plan)) {
            return List.of();
        }
        return plan.strip().lines()
                .map(line -> line.strip().replaceFirst("^(\\d+[.)]|[-*])\\s*", ""))
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static Map<String, Object> heading(String text) {
        return Map.of("type", "heading", "attrs", Map.of("level", 3),
                "content", List.of(text(text)));
    }

    private static Map<String, Object> paragraph(String body) {
        return body.isEmpty()
                ? Map.of("type", "paragraph")
                : Map.of("type", "paragraph", "content", List.of(text(body)));
    }

    private static Map<String, Object> codeBlock(String body) {
        return Map.of("type", "codeBlock", "attrs", Map.of(), "content", List.of(text(body)));
    }

    /**
     * 체크박스는 마크다운이 아니라 ADF {@code taskList}로 넣는다. 마크다운 표기는 Jira에서
     * 클릭되지 않아 사람이 진행 상황을 표시할 수 없다.
     */
    private static Map<String, Object> taskList(List<String> steps) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            items.add(Map.of("type", "taskItem",
                    "attrs", Map.of("localId", "step-" + (i + 1), "state", "TODO"),
                    "content", List.of(text(steps.get(i)))));
        }
        return Map.of("type", "taskList", "attrs", Map.of("localId", "fix-plan"), "content", items);
    }

    private static Map<String, Object> text(String value) {
        return Map.of("type", "text", "text", value);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
