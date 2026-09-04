package com.tmt.oncall.trigger;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.config.Target;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.guard.KillSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 받은 메시지를 처리할지 정하고 답변 톤을 판정한다. JDA에서 떼어 둔 이유는 봇 토큰 없이
 * 검증할 수 있는 것이 여기까지이기 때문이다 — 리스너는 값을 옮기기만 한다.
 */
@Component
public class QuestionIntake {

    private static final Logger log = LoggerFactory.getLogger(QuestionIntake.class);

    private final OncallProperties properties;
    private final KillSwitch killSwitch;

    QuestionIntake(OncallProperties properties, KillSwitch killSwitch) {
        this.properties = properties;
        this.killSwitch = killSwitch;
    }

    public Optional<QuestionAsked> accept(DiscordMessage message) {
        Target target = properties.target();
        if (!isOncall(target, message) || message.fromBot()) {
            return Optional.empty();
        }
        if (message.content().isBlank()) {
            // 첨부만 올린 메시지. 질문 본문이 없으면 분석에 넣을 것이 없다.
            return Optional.empty();
        }
        if (!killSwitch.isEnabled()) {
            log.info("킬 스위치가 off라 질문을 처리하지 않는다 — {}", message.authorName());
            return Optional.empty();
        }

        Audience audience = properties.discord().audienceOf(message.roleIds());
        log.info("질문 수신 — {} ({} 톤){}", message.authorName(), audience,
                message.inThread() ? ", 재분석 힌트" : "");
        return Optional.of(new QuestionAsked(
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
