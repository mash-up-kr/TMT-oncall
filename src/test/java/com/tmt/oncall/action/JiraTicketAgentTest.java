package com.tmt.oncall.action;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraTicketAgentTest {

    OncallProperties properties;
    MockRestServiceServer server;
    JiraTicketAgent agent;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        agent = new JiraTicketAgent(properties, builder);
    }

    @Test
    void 자동_생성분은_라벨로_사람이_만든_티켓과_구분된다() {
        server.expect(requestTo("https://ttalkkak.atlassian.net/rest/api/3/issue"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.fields.project.key").value("TMT"))
                .andExpect(jsonPath("$.fields.issuetype.name").value("작업"))
                .andExpect(jsonPath("$.fields.labels[0]").value("oncall-auto"))
                // 없으면 생성이 거절된다
                .andExpect(jsonPath("$.fields.customfield_10147.value").value("L3"))
                .andRespond(withSuccess("""
                        {"id":"1","key":"TMT-400"}
                        """, MediaType.APPLICATION_JSON));

        TicketResult result = agent.create(request());

        assertThat(result).isInstanceOf(TicketResult.Created.class);
        TicketResult.Created created = (TicketResult.Created) result;
        assertThat(created.key()).isEqualTo("TMT-400");
        assertThat(created.url()).isEqualTo("https://ttalkkak.atlassian.net/browse/TMT-400");
    }

    @Test
    void 본문에_출처와_요청자를_남긴다() {
        server.expect(requestTo("https://ttalkkak.atlassian.net/rest/api/3/issue"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("https://sentry.io/issues/1/"),
                        org.hamcrest.Matchers.containsString("https://discord.com/thread/1"),
                        org.hamcrest.Matchers.containsString("minseo"),
                        org.hamcrest.Matchers.containsString("NullPointerException"))))
                .andRespond(withSuccess("{\"key\":\"TMT-400\"}", MediaType.APPLICATION_JSON));

        assertThat(agent.create(request())).isInstanceOf(TicketResult.Created.class);
    }

    @Test
    void API_토큰_Basic_인증으로_호출한다() {
        server.expect(requestTo("https://ttalkkak.atlassian.net/rest/api/3/issue"))
                .andExpect(header(HttpHeaders.AUTHORIZATION,
                        org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess("{\"key\":\"TMT-400\"}", MediaType.APPLICATION_JSON));

        assertThat(agent.create(request())).isInstanceOf(TicketResult.Created.class);
    }

    @Test
    void 생성에_실패하면_사유를_돌려준다() {
        server.expect(requestTo("https://ttalkkak.atlassian.net/rest/api/3/issue"))
                .andRespond(withServerError());

        TicketResult result = agent.create(request());

        assertThat(result).isInstanceOf(TicketResult.Failed.class);
        // 라벨은 리포트가 붙인다. 사유에 같은 말이 겹치지 않아야 한다
        assertThat(((TicketResult.Failed) result).reason())
                .contains("500")
                .doesNotContain("티켓을 만들지 못했다");
    }

    /** 키가 없으면 뒤따르는 브랜치·PR 이름이 깨지므로 성공으로 보지 않는다. */
    @Test
    void 응답에_이슈_키가_없으면_실패로_본다() {
        server.expect(requestTo("https://ttalkkak.atlassian.net/rest/api/3/issue"))
                .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

        assertThat(agent.create(request())).isInstanceOf(TicketResult.Failed.class);
    }

    private TicketRequest request() {
        return new TicketRequest(
                properties.target(),
                "[온콜] NullPointerException — StoreService.find",
                "https://sentry.io/issues/1/",
                Instant.parse("2026-09-04T12:00:00Z"),
                "java.lang.NullPointerException at StoreService.find(StoreService.java:42)",
                "https://discord.com/thread/1",
                "minseo");
    }
}
