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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코드 수정이 필요할 때만 티켓을 만든다. 분석·답변만 하고 끝나는 경로는 티켓을 남기지 않는다 —
 * Discord 스레드가 기록이다.
 *
 * <p>Atlassian MCP가 아니라 REST API를 직접 부른다. MCP는 OAuth라 헤드리스 VM에서
 * 재인증에 사람이 붙어야 한다.
 */
@Component
public class JiraTicketAgent {

    private static final Logger log = LoggerFactory.getLogger(JiraTicketAgent.class);

    /** 사람이 만든 티켓과 구분하는 표식. 잘못 만들어진 티켓을 이 라벨로 찾아 지울 수 있다. */
    private static final String AUTO_LABEL = "oncall-auto";

    /** TMT 프로젝트의 Task 타입 이름. */
    private static final String ISSUE_TYPE = "작업";

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
        fields.put("summary", request.summary());
        fields.put("labels", List.of(AUTO_LABEL));
        fields.put(DECISION_LEVEL_FIELD, Map.of("value", DECISION_LEVEL));
        fields.put("description", description(request));
        return Map.of("fields", fields);
    }

    /** Jira Cloud v3는 본문을 ADF로 받는다. 문단 목록이면 충분해 표·링크 노드는 쓰지 않는다. */
    private Object description(TicketRequest request) {
        List<String> lines = List.of(
                "봇이 자동 생성한 티켓입니다. 요청자: " + request.requestedBy(),
                "발생 시각: " + OCCURRED_AT.format(request.occurredAt()),
                "Sentry: " + request.sentryIssueUrl(),
                "Discord 스레드: " + request.threadUrl(),
                "",
                "스택 요약",
                request.stackSummary());

        List<Object> paragraphs = lines.stream()
                .map(line -> line.isEmpty()
                        ? Map.<String, Object>of("type", "paragraph")
                        : Map.<String, Object>of("type", "paragraph",
                                "content", List.of(Map.of("type", "text", "text", line))))
                .map(Object.class::cast)
                .toList();

        return Map.of("type", "doc", "version", 1, "content", paragraphs);
    }
}
