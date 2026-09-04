package com.tmt.oncall.notify;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.SentryIssue;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
        notifier.reportDown(down());
        notifier.reportRecovered(new ServiceRecovered(properties.target(),
                Duration.ofMinutes(3), Instant.now()));

        assertThat(gateway.channelEmbeds).hasSize(1);
        assertThat(gateway.threadEmbeds).hasSize(1);
        assertThat(gateway.threadEmbeds.getFirst().title()).isEqualTo("✅ 복구");
    }

    @Test
    void 첫_리포트는_채널에_올리고_스레드를_연다() {
        notifier.reportDown(down());

        assertThat(gateway.openedThreadNames).containsExactly("다운 — tmt-be");
        assertThat(store.threadIdOf(IncidentRef.HEALTH, "tmt-be")).contains("thread-1");
    }

    /**
     * 헬스체크만으로는 코드 원인을 알 수 없고, 원인이 코드라면 그 예외는 Sentry에 잡혀
     * 에러 경로가 자기 리포트를 버튼과 함께 낸다.
     */
    @Test
    void 다운_리포트에는_버튼을_붙이지_않는다() {
        notifier.reportDown(down());

        assertThat(gateway.lastButtons).isEmpty();
    }

    /** 스택이 임베드 위에 오면 원인·수정 계획이 밀린다. */
    @Test
    void 스택은_스레드에_따로_보낸다() {
        notifier.reportIncident(new IncidentDetected(properties.target(), issue(), "{}"),
                new Analysis("원인", List.of("계획"), "영향", "배포",
                        "java.lang.NullPointerException: null", true));

        assertThat(gateway.channelEmbeds).hasSize(1);
        assertThat(gateway.threadEmbeds).isEmpty();
        assertThat(gateway.threadFollowUps).hasSize(1);
        assertThat(gateway.threadFollowUps.getFirst()).contains("**스택**", "NullPointerException");
    }

    @Test
    void 한_줄_알림은_스레드를_열지_않는다() {
        notifier.notice("1", "봇을 기동했습니다");

        assertThat(gateway.notices).containsExactly("봇을 기동했습니다");
        assertThat(gateway.openedThreadNames).isEmpty();
    }

    private ServiceDownDetected down() {
        return new ServiceDownDetected(properties.target(), ServiceDownDetected.Kind.UNREACHABLE,
                "Connection refused", Duration.ofMinutes(3), Instant.now(), "", List.of());
    }

    private static SentryIssue issue() {
        return new SentryIssue("4501", "TMT-BE-7", "NullPointerException", "MenuService.findById",
                "error", "new", "https://sentry.io/issues/4501/", 3, Instant.now(), Instant.now());
    }

    static final class FakeGateway implements DiscordGateway {

        final List<ReportEmbed> channelEmbeds = new ArrayList<>();
        final List<ReportEmbed> threadEmbeds = new ArrayList<>();
        final List<String> threadFollowUps = new ArrayList<>();
        final List<String> openedThreadNames = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        List<ReportButton> lastButtons = List.of();

        @Override
        public String send(String channelId, ReportEmbed embed, List<ReportButton> buttons, IncidentRef ref) {
            channelEmbeds.add(embed);
            lastButtons = buttons;
            return "message-" + channelEmbeds.size();
        }

        @Override
        public String openThread(String channelId, String messageId, String name) {
            openedThreadNames.add(name);
            return "thread-" + openedThreadNames.size();
        }

        @Override
        public void sendInThread(String threadId, ReportEmbed embed, List<String> followUps,
                                 List<ReportButton> buttons, IncidentRef ref) {
            if (embed != null) {
                threadEmbeds.add(embed);
            }
            threadFollowUps.addAll(followUps);
        }

        @Override
        public void sendNotice(String channelId, List<String> chunks) {
            notices.addAll(chunks);
        }
    }
}
