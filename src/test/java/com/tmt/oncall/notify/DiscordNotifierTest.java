package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordNotifierTest {

    OncallProperties properties;
    OncallStore store;
    FakeGateway gateway;
    DiscordNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        store = TestStore.create().store();
        gateway = new FakeGateway();
        notifier = new DiscordNotifier(gateway, store);
    }

    /** 같은 건의 후속 보고가 채널에 흩어지면 진행 상황을 따라갈 수 없다. */
    @Test
    void 같은_건의_후속_보고는_같은_스레드로_간다() {
        notifier.reportDown(down(ServiceDownDetected.Kind.UNREACHABLE));
        notifier.reportRecovered(new ServiceRecovered(properties.target(), Duration.ofMinutes(3)));

        assertThat(gateway.channelSends).hasSize(1);
        assertThat(gateway.threadSends).hasSize(1);
        assertThat(gateway.threadSends.getFirst()).contains("복구 — tmt-be");
    }

    @Test
    void 첫_리포트는_채널에_올리고_스레드를_연다() {
        notifier.reportDown(down(ServiceDownDetected.Kind.UNREACHABLE));

        assertThat(gateway.openedThreadNames).containsExactly("다운 — tmt-be");
        assertThat(store.threadIdOf(IncidentRef.HEALTH, "tmt-be")).contains("thread-1");
        assertThat(gateway.lastButtons).containsExactly(ReportButton.REANALYZE, ReportButton.IGNORE);
    }

    @Test
    void 한_줄_알림은_스레드를_열지_않는다() {
        notifier.notice("1", "봇을 기동했다");

        assertThat(gateway.channelSends).containsExactly("봇을 기동했다");
        assertThat(gateway.openedThreadNames).isEmpty();
    }

    private ServiceDownDetected down(ServiceDownDetected.Kind kind) {
        return new ServiceDownDetected(properties.target(), kind, "연결할 수 없다", List.of());
    }

    static final class FakeGateway implements DiscordGateway {

        final List<String> channelSends = new ArrayList<>();
        final List<String> threadSends = new ArrayList<>();
        final List<String> openedThreadNames = new ArrayList<>();
        List<ReportButton> lastButtons = List.of();

        @Override
        public String send(String channelId, List<String> chunks, List<ReportButton> buttons, IncidentRef ref) {
            channelSends.add(String.join("\n", chunks));
            lastButtons = buttons;
            return "message-" + channelSends.size();
        }

        @Override
        public String openThread(String channelId, String messageId, String name) {
            openedThreadNames.add(name);
            return "thread-" + openedThreadNames.size();
        }

        @Override
        public void sendInThread(String threadId, List<String> chunks, List<ReportButton> buttons, IncidentRef ref) {
            threadSends.add(String.join("\n", chunks));
            lastButtons = buttons;
        }
    }
}
