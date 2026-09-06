package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.guard.KillSwitch;
import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.support.TestProperties;
import com.tmt.oncall.support.TestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionIntakeTest {

    /** {@code TestProperties.defaults()}의 온콜 채널. */
    private static final String ONCALL = "1";

    OncallProperties properties;
    OncallStore store;
    KillSwitch killSwitch;
    QuestionIntake intake;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        store = TestStore.create().store();
        killSwitch = new KillSwitch(properties);
        intake = new QuestionIntake(properties, store, killSwitch);
    }

    @Test
    void 온콜_채널의_질문을_역할_톤과_함께_받는다() {
        QuestionAsked question = accepted(message(ONCALL, null, List.of("300")));

        assertThat(question.audience()).isEqualTo(Audience.SPRING);
        assertThat(question.content()).isEqualTo("주문이 안 돼요");
        assertThat(question.reanalysis()).isFalse();
        assertThat(question.threadId()).isNull();
    }

    @Test
    void 역할이_없으면_디자인_톤으로_받는다() {
        assertThat(accepted(message(ONCALL, null, List.of())).audience()).isEqualTo(Audience.DESIGN);
    }

    @Test
    void 다른_채널의_메시지는_처리하지_않는다() {
        assertThat(intake.accept(message("999", null, List.of("300")))).isEqualTo(IntakeResult.IGNORED);
    }

    @Test
    void 봇_메시지는_처리하지_않는다() {
        DiscordMessage fromBot = new DiscordMessage(ONCALL, null, true, "m1", "u1", "온콜봇",
                List.of("300"), "장애 리포트");

        assertThat(intake.accept(fromBot)).isEqualTo(IntakeResult.IGNORED);
    }

    @Test
    void 안내를_다시_받아도_되묻지_않는다() {
        DiscordMessage ownNotice = new DiscordMessage(ONCALL, null, true, "m1", "u1", "온콜봇",
                List.of(), "질문 내용을 함께 적어주세요. 첨부만으로는 확인이 어렵습니다.");

        assertThat(intake.accept(ownNotice)).isEqualTo(IntakeResult.IGNORED);
    }

    @Test
    void 봇이_연_스레드의_메시지는_재분석_힌트로_받는다() {
        store.markProcessed("sentry", "4501", "t1");

        QuestionAsked question = accepted(message("t1", ONCALL, List.of("200")));

        assertThat(question.reanalysis()).isTrue();
        assertThat(question.threadId()).isEqualTo("t1");
        assertThat(question.audience()).isEqualTo(Audience.WEB);
    }

    @Test
    void 사람이_연_스레드는_처리하지_않는다() {
        assertThat(intake.accept(message("t1", ONCALL, List.of("300")))).isEqualTo(IntakeResult.IGNORED);
    }

    @Test
    void 다른_채널에_달린_스레드는_처리하지_않는다() {
        store.markProcessed("sentry", "4501", "t1");

        assertThat(intake.accept(message("t1", "999", List.of("300")))).isEqualTo(IntakeResult.IGNORED);
    }

    @Test
    void 킬_스위치가_off면_수신만_하고_처리하지_않는다() {
        killSwitch.turnOff("테스트");

        assertThat(intake.accept(message(ONCALL, null, List.of("300")))).isEqualTo(IntakeResult.IGNORED);
        assertThat(intake.accept(attachmentOnly())).isEqualTo(IntakeResult.IGNORED);
    }

    @Test
    void 본문이_없으면_질문을_적어달라고_안내한다() {
        IntakeResult result = intake.accept(attachmentOnly());

        assertThat(result).isInstanceOf(IntakeResult.Notice.class);
        IntakeResult.Notice notice = (IntakeResult.Notice) result;
        assertThat(notice.channelId()).isEqualTo(ONCALL);
        assertThat(notice.line()).isEqualTo("질문 내용을 함께 적어주세요. 첨부만으로는 확인이 어렵습니다.");
    }

    private QuestionAsked accepted(DiscordMessage message) {
        IntakeResult result = intake.accept(message);
        assertThat(result).isInstanceOf(IntakeResult.Accepted.class);
        return ((IntakeResult.Accepted) result).question();
    }

    private static DiscordMessage attachmentOnly() {
        return new DiscordMessage(ONCALL, null, false, "m1", "u1", "지영", List.of("300"), "  ");
    }

    private static DiscordMessage message(String channelId, String parentChannelId, List<String> roleIds) {
        return new DiscordMessage(channelId, parentChannelId, false, "m1", "u1", "지영",
                roleIds, "주문이 안 돼요");
    }
}
