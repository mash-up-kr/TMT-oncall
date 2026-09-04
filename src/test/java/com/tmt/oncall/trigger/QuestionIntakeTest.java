package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.guard.KillSwitch;
import com.tmt.oncall.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionIntakeTest {

    /** {@code TestProperties.defaults()}의 온콜 채널. */
    private static final String ONCALL = "1";

    OncallProperties properties;
    KillSwitch killSwitch;
    QuestionIntake intake;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        killSwitch = new KillSwitch(properties);
        intake = new QuestionIntake(properties, killSwitch);
    }

    @Test
    void 온콜_채널의_질문을_역할_톤과_함께_받는다() {
        var accepted = intake.accept(message(ONCALL, null, List.of("300")));

        assertThat(accepted).isPresent();
        assertThat(accepted.get().audience()).isEqualTo(Audience.SPRING);
        assertThat(accepted.get().content()).isEqualTo("주문이 안 돼요");
        assertThat(accepted.get().reanalysis()).isFalse();
        assertThat(accepted.get().threadId()).isNull();
    }

    @Test
    void 역할이_없으면_디자인_톤으로_받는다() {
        var accepted = intake.accept(message(ONCALL, null, List.of()));

        assertThat(accepted).isPresent();
        assertThat(accepted.get().audience()).isEqualTo(Audience.DESIGN);
    }

    @Test
    void 다른_채널의_메시지는_처리하지_않는다() {
        assertThat(intake.accept(message("999", null, List.of("300")))).isEmpty();
    }

    @Test
    void 봇_메시지는_처리하지_않는다() {
        DiscordMessage fromBot = new DiscordMessage(ONCALL, null, true, "m1", "u1", "온콜봇",
                List.of("300"), "장애 리포트");

        assertThat(intake.accept(fromBot)).isEmpty();
    }

    @Test
    void 온콜_채널의_스레드는_재분석_힌트로_받는다() {
        var accepted = intake.accept(message("t1", ONCALL, List.of("200")));

        assertThat(accepted).isPresent();
        assertThat(accepted.get().reanalysis()).isTrue();
        assertThat(accepted.get().threadId()).isEqualTo("t1");
        assertThat(accepted.get().audience()).isEqualTo(Audience.WEB);
    }

    @Test
    void 다른_채널에_달린_스레드는_처리하지_않는다() {
        assertThat(intake.accept(message("t1", "999", List.of("300")))).isEmpty();
    }

    @Test
    void 킬_스위치가_off면_수신만_하고_처리하지_않는다() {
        killSwitch.turnOff("테스트");

        assertThat(intake.accept(message(ONCALL, null, List.of("300")))).isEmpty();
    }

    @Test
    void 본문이_없는_메시지는_처리하지_않는다() {
        DiscordMessage attachmentOnly = new DiscordMessage(ONCALL, null, false, "m1", "u1", "지영",
                List.of("300"), "  ");

        assertThat(intake.accept(attachmentOnly)).isEmpty();
    }

    private static DiscordMessage message(String channelId, String parentChannelId, List<String> roleIds) {
        return new DiscordMessage(channelId, parentChannelId, false, "m1", "u1", "지영",
                roleIds, "주문이 안 돼요");
    }
}
