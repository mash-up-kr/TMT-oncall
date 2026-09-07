package com.tmt.oncall.core;

/**
 * 답변 톤을 정하는 대상. Discord 역할이 그대로 audience가 된다.
 * 역할이 없거나 판정되지 않으면 {@link #DESIGN}으로 답한다.
 */
public enum Audience {
    DESIGN("answer-design"),
    WEB("answer-web"),
    SPRING("answer-spring");

    public static final Audience DEFAULT = DESIGN;

    private final String skill;

    Audience(String skill) {
        this.skill = skill;
    }

    /** 톤별 답변 스킬 이름. 봇은 이름만 알고 문구는 마켓플레이스 플러그인이 갖는다. */
    public String skill() {
        return skill;
    }
}
