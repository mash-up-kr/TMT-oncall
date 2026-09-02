package com.tmt.oncall.core;

/**
 * 에이전트 응답의 {@code usage} 집계. 실제 토큰으로 비용을 누적한다.
 */
public record Usage(
        long inputTokens,
        long outputTokens,
        long cacheCreationTokens,
        long cacheReadTokens) {

    public static final Usage NONE = new Usage(0, 0, 0, 0);

    public long totalTokens() {
        return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
    }
}
