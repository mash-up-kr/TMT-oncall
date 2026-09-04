package com.tmt.oncall.notify;

import java.util.List;

/**
 * 리포트에 실을 분석 결과. 렌더러가 분석 단계에 의존하지 않도록 값으로만 받는다 —
 * 실제 분석 연결은 TMT-330에서 한다.
 *
 * @param stackExcerpt    에러 경로는 항상 '스프링' 톤이라 스택을 가공하지 않고 그대로 싣는다
 * @param codeFixPossible 원인이 코드 밖(인프라·외부 API)이면 수정 버튼을 붙이지 않는다
 */
public record Analysis(
        String cause,
        List<String> fixPlan,
        String impact,
        String relatedDeploy,
        String stackExcerpt,
        boolean codeFixPossible) {
}
