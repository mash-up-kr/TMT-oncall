package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Sentry API 조회. 인바운드 웹훅을 열지 않기로 했으므로 봇이 나가서 가져온다. */
@Component
public class SentryClient {

    private static final Logger log = LoggerFactory.getLogger(SentryClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    SentryClient(OncallProperties properties, ObjectMapper objectMapper) {
        this(properties, RestClient.builder(), objectMapper);
    }

    SentryClient(OncallProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder
                .baseUrl(properties.sentry().baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.sentry().authToken())
                .build();
        this.objectMapper = objectMapper;
    }

    /** 미해결 이슈를 최근 발생 순으로 가져온다. */
    public List<SentryIssue> unresolvedIssues(String orgSlug, String projectSlug, int limit) {
        String body = restClient.get()
                .uri(uri -> uri.path("/api/0/projects/{org}/{project}/issues/")
                        .queryParam("query", "is:unresolved")
                        .queryParam("limit", limit)
                        .build(orgSlug, projectSlug))
                .retrieve()
                .body(String.class);

        JsonNode root = readTree(body);
        if (root == null || !root.isArray()) {
            throw new RestClientException("Sentry 이슈 응답을 배열로 읽지 못했다");
        }
        return root.valueStream().map(SentryClient::toIssue).toList();
    }

    /**
     * 이슈의 대표 이벤트 원본. 분석 입력으로 그대로 넘긴다. 실패해도 이슈 메타데이터만으로
     * 1차 분류는 할 수 있으므로 호출부를 막지 않고 빈 값을 돌려준다.
     */
    public String latestEventJson(String issueId) {
        try {
            return restClient.get()
                    .uri("/api/0/issues/{id}/events/latest/", issueId)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("이슈 {}의 대표 이벤트를 가져오지 못했다: {}", issueId, e.getMessage());
            return "";
        }
    }

    private static SentryIssue toIssue(JsonNode node) {
        return new SentryIssue(
                node.path("id").asString(""),
                node.path("shortId").asString(""),
                node.path("title").asString(""),
                node.path("culprit").asString(""),
                node.path("level").asString(""),
                node.path("substatus").asString(""),
                node.path("permalink").asString(""),
                node.path("count").asLong(0),
                parseInstant(node.path("firstSeen").asString("")),
                parseInstant(node.path("lastSeen").asString("")));
    }

    private static Instant parseInstant(String value) {
        try {
            return value.isBlank() ? Instant.EPOCH : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }
}
