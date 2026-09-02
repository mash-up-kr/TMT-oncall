package com.tmt.oncall.core;

/**
 * 에이전트 호출 경로. 경로마다 다른 모델을 쓰고, 예산 소진 시 비싼 경로부터 막힌다.
 */
public enum CallPath {
    /** 질문 분류 — 조치가 필요한 건인지 판정. 소스를 읽지 않는다. */
    TRIAGE,
    /** 분석·답변 — 소스를 읽고 원인·수정 계획 도출. */
    ANALYZE,
    /** 수정·PR — 버튼 승인 뒤에만 실행. */
    FIX
}
