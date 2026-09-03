package com.tmt.oncall.agent;

import com.tmt.oncall.core.CallPath;

import java.nio.file.Path;

/**
 * 에이전트 CLI 한 번의 호출.
 *
 * @param path             호출 경로. 모델과 예산 정책이 여기서 파생된다
 * @param skill            실행할 스킬 이름. 없으면 프롬프트만 보낸다
 * @param prompt           스킬에 넘길 입력
 * @param workingDirectory CLI가 볼 디렉터리. 소스를 읽지 않는 경로는 빈 디렉터리를 준다
 */
public record AgentCall(CallPath path, String skill, String prompt, Path workingDirectory) {

    public static AgentCall of(CallPath path, String skill, String prompt, Path workingDirectory) {
        return new AgentCall(path, skill, prompt, workingDirectory);
    }

    /** 스킬은 슬래시 명령으로 넘긴다. 봇은 이름만 알고 내용은 플러그인이 갖는다. */
    String fullPrompt() {
        if (skill == null || skill.isBlank()) {
            return prompt;
        }
        return "/%s%n%n%s".formatted(skill, prompt);
    }
}
