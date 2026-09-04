package com.tmt.oncall.guard;

import com.tmt.oncall.config.OncallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 봇 전체를 즉시 정지·재개한다. 기동 시 기본 상태는 환경변수로 정하고,
 * 이후에는 슬래시 명령({@code /oncall off} / {@code on})으로 바꾼다.
 */
@Component
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);

    private final AtomicBoolean enabled;

    public KillSwitch(OncallProperties properties) {
        this.enabled = new AtomicBoolean(properties.enabled());
        log.info("킬 스위치 초기 상태: {}", state());
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /** @return 상태가 실제로 바뀌었으면 true */
    public boolean turnOn(String by) {
        boolean changed = enabled.compareAndSet(false, true);
        log.info("킬 스위치 on 요청 ({}), 변경됨={}", by, changed);
        return changed;
    }

    /** @return 상태가 실제로 바뀌었으면 true */
    public boolean turnOff(String by) {
        boolean changed = enabled.compareAndSet(true, false);
        log.info("킬 스위치 off 요청 ({}), 변경됨={}", by, changed);
        return changed;
    }

    public String state() {
        return enabled.get() ? "on" : "off";
    }
}
