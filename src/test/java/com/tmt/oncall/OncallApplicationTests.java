package com.tmt.oncall;

import com.tmt.oncall.config.OncallProperties;
import com.tmt.oncall.core.Audience;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.core.CallPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "oncall.store.path=build/test-store/context/state.db")
@ActiveProfiles("test")
class OncallApplicationTests {

    @Autowired
    OncallProperties properties;

    /** 기본값이 구독제로 넘어가면 결제 방식이 조용히 바뀐다. 경로별 모델도 여기서 굳힌다. */
    @Test
    void 기본_과금_모드는_종량제이고_경로별_모델이_고정된다() {
        assertThat(properties.agent().billing()).isEqualTo(BillingMode.API_KEY);
        assertThat(properties.agent().modelFor(CallPath.TRIAGE).id()).isEqualTo("claude-haiku-4-5");
        assertThat(properties.agent().modelFor(CallPath.ANALYZE).id()).isEqualTo("claude-sonnet-5");
        assertThat(properties.agent().modelFor(CallPath.FIX).id()).isEqualTo("claude-opus-5");
    }

    @Test
    void 역할로_답변_톤을_정하고_없으면_디자인으로_답한다() {
        assertThat(properties.discord().audienceOf(List.of("300"))).isEqualTo(Audience.SPRING);
        assertThat(properties.discord().audienceOf(List.of("200"))).isEqualTo(Audience.WEB);
        assertThat(properties.discord().audienceOf(List.of("100"))).isEqualTo(Audience.DESIGN);
        // 여러 역할을 가지면 가장 기술적인 톤을 고른다
        assertThat(properties.discord().audienceOf(List.of("100", "300"))).isEqualTo(Audience.SPRING);
        assertThat(properties.discord().audienceOf(List.of("100", "200"))).isEqualTo(Audience.WEB);
        // 역할이 없으면 디자인 톤
        assertThat(properties.discord().audienceOf(List.of())).isEqualTo(Audience.DESIGN);
    }
}
