package com.tmt.oncall.notify;

/** 임베드 왼쪽 띠 색. 채널을 훑을 때 심각도를 색으로 먼저 읽게 한다. */
public enum ReportColor {

    /** 다운·에러 */
    RED(0xE0_3131),
    /** 의존성 이상 */
    ORANGE(0xF0_8C00),
    /** 복구 */
    GREEN(0x2F_9E44),
    /** 질문 답변 */
    BLUE(0x1C_7ED6);

    private final int rgb;

    ReportColor(int rgb) {
        this.rgb = rgb;
    }

    public int rgb() {
        return rgb;
    }
}
