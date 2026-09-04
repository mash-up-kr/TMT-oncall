package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.guard.KillSwitch;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SentryWatcherTest {

    OncallProperties properties;
    OncallStore store;
    KillSwitch killSwitch;
    ObjectMapper objectMapper;
    List<IncidentDetected> detected;
    MockRestServiceServer server;
    SentryWatcher watcher;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        store = TestStore.create().store();
        killSwitch = new KillSwitch(properties);
        objectMapper = JsonMapper.builder().build();

        detected = new ArrayList<>();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ApplicationEventPublisher publisher = event -> detected.add((IncidentDetected) event);
        watcher = new SentryWatcher(properties, new SentryClient(properties, builder, objectMapper),
                store, killSwitch, publisher);
    }

    @Test
    void 첫_폴링은_커서만_세우고_알리지_않는다() {
        expectIssues(issue("1", "new", Instant.now()));

        watcher.poll();

        assertThat(detected).isEmpty();
        assertThat(store.cursor("sentry:tmt-be")).isPresent();
    }

    @Test
    void 커서_이후의_신규_이슈를_이벤트로_낸다() {
        givenCursor(Instant.now().minus(Duration.ofHours(1)));
        expectIssues(issue("1", "new", Instant.now()));
        expectLatestEvent();

        watcher.poll();

        assertThat(detected).hasSize(1);
        assertThat(detected.getFirst().issue().id()).isEqualTo("1");
        assertThat(detected.getFirst().latestEventJson()).contains("NullPointerException");
        assertThat(store.lastProcessedAt("sentry", "1")).isPresent();
    }

    @Test
    void 계속_나던_이슈는_다시_알리지_않는다() {
        givenCursor(Instant.now().minus(Duration.ofHours(1)));
        expectIssues(issue("1", "ongoing", Instant.now()));

        watcher.poll();

        assertThat(detected).isEmpty();
    }

    @Test
    void 재발한_이슈는_알린다() {
        givenCursor(Instant.now().minus(Duration.ofHours(1)));
        expectIssues(issue("1", "regressed", Instant.now()));
        expectLatestEvent();

        watcher.poll();

        assertThat(detected).hasSize(1);
    }

    @Test
    void 억제된_이슈는_알리지_않는다() {
        givenCursor(Instant.now().minus(Duration.ofHours(1)));
        store.suppress("sentry", "1", "누군가");
        expectIssues(issue("1", "new", Instant.now()));

        watcher.poll();

        assertThat(detected).isEmpty();
    }

    @Test
    void 중복_억제_창_안이면_다시_알리지_않는다() {
        givenCursor(Instant.now().minus(Duration.ofHours(1)));
        store.markProcessed("sentry", "1", null);
        expectIssues(issue("1", "new", Instant.now()));

        watcher.poll();

        assertThat(detected).isEmpty();
    }

    @Test
    void 커서보다_오래된_이슈는_건너뛴다() {
        Instant cursor = Instant.now();
        givenCursor(cursor);
        expectIssues(issue("1", "new", cursor.minus(Duration.ofMinutes(5))));

        watcher.poll();

        assertThat(detected).isEmpty();
    }

    @Test
    void 킬_스위치가_꺼져_있으면_폴링하지_않는다() {
        killSwitch.turnOff("test");

        watcher.poll();

        assertThat(detected).isEmpty();
        server.verify();
    }

    @Test
    void 폴링에_실패해도_다음_주기를_위해_커서를_지킨다() {
        Instant cursor = Instant.now().minus(Duration.ofHours(1));
        givenCursor(cursor);
        server.expect(manyTimes(), requestTo(containsString("/issues/")))
                .andRespond(withServerError());

        watcher.poll();

        assertThat(detected).isEmpty();
        assertThat(store.cursor("sentry:tmt-be")).contains(cursor.toString());
    }

    // --- 도우미 ---

    private void givenCursor(Instant at) {
        store.saveCursor("sentry:tmt-be", at.toString());
    }

    private void expectIssues(String... issuesJson) {
        server.expect(manyTimes(), requestTo(containsString("/projects/test-org/tmt-be/issues/")))
                .andRespond(withSuccess("[" + String.join(",", issuesJson) + "]", MediaType.APPLICATION_JSON));
    }

    private void expectLatestEvent() {
        server.expect(manyTimes(), requestTo(containsString("/events/latest/")))
                .andRespond(withSuccess("""
                        {"eventID":"abc","message":"NullPointerException"}
                        """, MediaType.APPLICATION_JSON));
    }

    private static String issue(String id, String substatus, Instant lastSeen) {
        return """
                {"id":"%s","shortId":"TMT-BE-1","title":"NullPointerException",
                 "culprit":"StoreService.find","level":"error","substatus":"%s",
                 "permalink":"https://sentry.io/issues/%s/","count":3,
                 "firstSeen":"%s","lastSeen":"%s"}
                """.formatted(id, substatus, id, lastSeen.minus(Duration.ofMinutes(1)), lastSeen);
    }
}
