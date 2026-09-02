package com.tmt.oncall.core;

/**
 * 답변 톤을 정하는 대상. Discord 역할이 그대로 audience가 된다.
 * 역할이 없거나 판정되지 않으면 {@link #DESIGN}으로 답한다.
 */
public enum Audience {
    DESIGN,
    WEB,
    SPRING;

    public static final Audience DEFAULT = DESIGN;
}
