package com.tmt.oncall.trigger;

import com.tmt.oncall.config.Target;

import java.time.Duration;
import java.time.Instant;

/** 다운으로 알린 서비스가 돌아왔다. 알린 건은 끝을 알려야 채널이 상태를 오해하지 않는다. */
public record ServiceRecovered(Target target, Duration downFor, Instant recoveredAt) {
}
