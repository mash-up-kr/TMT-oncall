package com.tmt.oncall.config;

import com.tmt.oncall.support.FilePaths;

import java.nio.file.Path;

/**
 * 봇이 담당하는 대상 서비스. 트리거·분석·PR 경로는 전역 설정을 읽지 않고
 * 이 객체를 인자로 받는다 — 담당 서비스가 늘어나도 에이전트 코드는 그대로 둔다.
 *
 * @param sentryProjectSlug 비어 있으면 에러 트리거를 띄우지 않는다
 * @param healthUrl         비어 있으면 다운 트리거를 띄우지 않는다
 */
public record Target(
        String key,
        String repo,
        String workspace,
        String buildCommand,
        String jiraProjectKey,
        String sentryProjectSlug,
        String healthUrl,
        String discordChannelId) {

    public Path workspacePath() {
        return FilePaths.expand(workspace);
    }

    public boolean hasErrorTrigger() {
        return sentryProjectSlug != null && !sentryProjectSlug.isBlank();
    }

    public boolean hasDownTrigger() {
        return healthUrl != null && !healthUrl.isBlank();
    }
}
