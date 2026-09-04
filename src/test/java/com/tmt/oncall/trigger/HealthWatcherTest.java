package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.guard.KillSwitch;
import com.tmt.oncall.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HealthWatcherTest {

    private static final String DOWN_BODY = """
            {"status":"DOWN","components":{"db":{"status":"DOWN"}}}
            """;

    OncallProperties properties;
    List<Object> published;
    MockRestServiceServer server;
    HealthWatcher watcher;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        published = new ArrayList<>();

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ApplicationEventPublisher publisher = published::add;
        watcher = new HealthWatcher(properties, builder,
                new SentryClient(properties, RestClient.builder(), JsonMapper.builder().build()),
                new KillSwitch(properties), publisher);
    }

    @Test
    void 임계_전의_실패는_알리지_않는다() {
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        watcher.poll();
        watcher.poll();

        assertThat(published).isEmpty();
    }

    @Test
    void 연속_실패가_임계에_닿으면_다운으로_알린다() {
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN_BODY)
                .contentType(MediaType.APPLICATION_JSON));

        pollTimes(3);

        assertThat(published).hasSize(1);
        assertThat(published.getFirst()).isInstanceOf(ServiceDownDetected.class);
    }

    @Test
    void 다운이_이어지는_동안은_다시_알리지_않는다() {
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        pollTimes(10);

        assertThat(published).hasSize(1);
    }

    @Test
    void 임계_전에_회복하면_실패_횟수를_되돌린다() {
        server.expect(manyTimes(), requestTo(containsString("/actuator/health")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        watcher.poll();
        watcher.poll();
        server.reset();

        expectHealth(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
        watcher.poll();
        server.reset();

        // 되돌지 않았다면 이 두 번으로 임계에 닿아 알림이 나간다
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        watcher.poll();
        watcher.poll();

        assertThat(published).isEmpty();
    }

    @Test
    void 다운에서_돌아오면_복구를_알린다() {
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        pollTimes(3);
        server.reset();

        expectHealth(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
        watcher.poll();

        assertThat(published).hasSize(2);
        assertThat(published.getLast()).isInstanceOf(ServiceRecovered.class);
        assertThat(((ServiceRecovered) published.getLast()).downFor()).isNotNull();
    }

    /** 리포트가 "실패 3회" 대신 지속 시간으로 말할 수 있게 트리거가 환산해 넘긴다. */
    @Test
    void 무응답_구간을_폴링_주기로_환산해_넘긴다() {
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN_BODY)
                .contentType(MediaType.APPLICATION_JSON));

        pollTimes(3);

        ServiceDownDetected down = (ServiceDownDetected) published.getFirst();
        assertThat(down.unresponsiveFor()).isEqualTo(java.time.Duration.ofMinutes(3));
        assertThat(down.detectedAt()).isNotNull();
        assertThat(down.responseBody()).contains("\"db\"");
    }

    /** 앱이 응답하면 의존성 문제, 응답이 없으면 프로세스 문제 — 리포트에서 조치가 갈린다. */
    @Test
    void 응답이_오면_의존성_문제로_본다() {
        expectHealth(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN_BODY)
                .contentType(MediaType.APPLICATION_JSON));

        pollTimes(3);

        ServiceDownDetected down = (ServiceDownDetected) published.getFirst();
        assertThat(down.kind()).isEqualTo(ServiceDownDetected.Kind.DEGRADED);
    }

    @Test
    void 응답도_liveness도_없으면_프로세스가_죽은_것으로_본다() {
        server.expect(manyTimes(), requestTo(containsString("/actuator/health")))
                .andRespond(request -> {
                    throw new java.io.IOException("연결할 수 없다");
                });

        pollTimes(3);

        ServiceDownDetected down = (ServiceDownDetected) published.getFirst();
        assertThat(down.kind()).isEqualTo(ServiceDownDetected.Kind.UNREACHABLE);
    }

    @Test
    void liveness가_살아_있으면_의존성_문제로_본다() {
        server.expect(manyTimes(), requestTo(containsString("/actuator/health/liveness")))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo("http://localhost:9/actuator/health"))
                .andRespond(request -> {
                    throw new java.io.IOException("연결할 수 없다");
                });

        pollTimes(3);

        ServiceDownDetected down = (ServiceDownDetected) published.getFirst();
        assertThat(down.kind()).isEqualTo(ServiceDownDetected.Kind.DEGRADED);
    }

    @Test
    void 킬_스위치가_꺼져_있으면_폴링하지_않는다() {
        KillSwitch killSwitch = new KillSwitch(properties);
        killSwitch.turnOff("test");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer strict = MockRestServiceServer.bindTo(builder).build();
        HealthWatcher stopped = new HealthWatcher(properties, builder,
                new SentryClient(properties, RestClient.builder(), JsonMapper.builder().build()),
                killSwitch, published::add);

        stopped.poll();

        assertThat(published).isEmpty();
        strict.verify();
    }

    private void pollTimes(int times) {
        for (int i = 0; i < times; i++) {
            watcher.poll();
        }
    }

    private void expectHealth(org.springframework.test.web.client.ResponseCreator response) {
        server.expect(manyTimes(), requestTo(containsString("/actuator/health")))
                .andRespond(response);
    }
}
