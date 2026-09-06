package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.guard.KillSwitch;
import com.tmt.oncall.store.OncallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 받은 메시지를 처리할지 정하고 답변 톤을 판정한다. JDA에서 떼어 둔 이유는 봇 토큰 없이
 * 검증할 수 있는 것이 여기까지이기 때문이다 — 리스너는 값을 옮기고 결과를 실어 나르기만 한다.
 */
@Component
public class QuestionIntake {

    private static final Logger log = LoggerFactory.getLogger(QuestionIntake.class);

    private static final String NEED_TEXT = "질문 내용을 함께 적어주세요. 첨부만으로는 확인이 어렵습니다.";

    private final OncallProperties properties;
    private final OncallStore store;
    private final KillSwitch killSwitch;

    QuestionIntake(OncallProperties properties, OncallStore store, KillSwitch killSwitch) {
        this.properties = properties;
        this.store = store;
        this.killSwitch = killSwitch;
    }

    public IntakeResult accept(DiscordMessage message) {
        Target target = properties.target();
        if (!isOncall(target, message) || message.fromBot()) {
            return IntakeResult.IGNORED;
        }
        // 스레드는 특정 건에 딸린 대화라, 봇이 연 것이 아니면 채널 질문과 맥락이 어긋난다.
        if (message.inThread() && !store.hasThread(message.channelId())) {
            return IntakeResult.IGNORED;
        }
        if (!killSwitch.isEnabled()) {
            log.info("킬 스위치가 off라 질문을 처리하지 않는다 — {}", message.authorName());
            return IntakeResult.IGNORED;
        }
        if (message.content().isBlank()) {
            // 스크린샷만 올린 경우. 분석에 넣을 것이 없으니 에이전트를 부르지 않고 되묻기만 한다.
            return new IntakeResult.Notice(message.channelId(), NEED_TEXT);
        }

        Audience audience = properties.discord().audienceOf(message.roleIds());
        log.info("질문 수신 — {} ({} 톤){}", message.authorName(), audience,
                message.inThread() ? ", 재분석 힌트" : "");
        return new IntakeResult.Accepted(new QuestionAsked(
                target,
                audience,
                message.channelId(),
                message.inThread() ? message.channelId() : null,
                message.messageId(),
                message.authorId(),
                message.authorName(),
                message.content(),
                message.inThread()));
    }

    /** 스레드는 부모가 온콜 채널일 때만 본다 — 다른 채널의 스레드까지 열어 두면 채널 필터가 무의미해진다. */
    private static boolean isOncall(Target target, DiscordMessage message) {
        String channelId = message.inThread() ? message.parentChannelId() : message.channelId();
        return target.discordChannelId().equals(channelId);
    }
}
