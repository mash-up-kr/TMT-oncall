package com.tmt.oncall.triage;

import com.tmt.oncall.notify.Analysis;
import com.tmt.oncall.notify.Answer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스킬이 형식을 어겼을 때 봇이 어디까지 버티는지 본다.
 *
 * <p>
 * 운영에서 겪은 것 — 답변 본문에 실린 SQL의 {@code ESCAPE '\'} 가 JSON에서 유효하지 않은
 * 이스케이프라 파싱이 통째로 깨졌고, 답변이 JSON 덩어리째 채널에 올라갔다. 스킬이 형식을
 * 지키는 것과 별개로 봇이 그 정도에 무너지면 안 된다.
 */
class SkillOutputTest {

    @Test
    void 잘못된_백슬래시_이스케이프가_있어도_읽는다() {
        String output = """
                {"answer": "ILIKE :q ESCAPE '\\' 로 나갑니다",
                 "code_fix_needed": true,
                 "fix_plan": ["인덱스를 추가한다"]}
                """;

        Answer answer = Answers.parse(output);

        assertThat(answer.text()).contains("ESCAPE");
        assertThat(answer.codeFixNeeded()).isTrue();
        assertThat(answer.fixPlan()).containsExactly("인덱스를 추가한다");
    }

    @Test
    void 이스케이프하지_않은_줄바꿈이_있어도_읽는다() {
        String output = "{\"answer\": \"첫 줄\n둘째 줄\", \"code_fix_needed\": false}";

        Answer answer = Answers.parse(output);

        assertThat(answer.text()).contains("첫 줄").contains("둘째 줄");
        assertThat(answer.codeFixNeeded()).isFalse();
    }

    /** JSON 덩어리를 답변이라고 올리면 읽는 사람은 봇이 고장 난 것으로 본다. */
    @Test
    void 끝내_못_읽으면_본문만_건져_싣는다() {
        String output = """
                {"answer": "인덱스가 없습니다", "code_fix_needed": true, "fix_plan": [깨짐
                """;

        Answer answer = Answers.parse(output);

        assertThat(answer.text()).isEqualTo("인덱스가 없습니다");
        // 나머지 필드는 믿지 않는다 — 수정이 필요하다는 판단만 골라 믿을 근거가 없다.
        assertThat(answer.codeFixNeeded()).isFalse();
        assertThat(answer.fixPlan()).isEmpty();
    }

    @Test
    void 건질_것도_없으면_형식이_깨졌다고_알린다() {
        Answer answer = Answers.parse("그냥 이런 저런 말만 했습니다");

        assertThat(answer.text()).contains("약속된 형식으로 답하지 않아");
        assertThat(answer.text()).contains("그냥 이런 저런 말만 했습니다");
        assertThat(answer.codeFixNeeded()).isFalse();
    }

    @Test
    void 코드_펜스로_감싸도_읽는다() {
        String output = """
                ```json
                {"answer": "됩니다", "code_fix_needed": false}
                ```
                """;

        assertThat(Answers.parse(output).text()).isEqualTo("됩니다");
    }

    /** 에러 경로도 같은 지뢰를 밟는다. 리포트의 원인 자리에 JSON이 실리면 안 된다. */
    @Test
    void 분석_출력이_깨지면_원인만_건진다() {
        String output = """
                {"cause": "MenuService에서 null을 참조한다", "fix_plan": [깨짐
                """;

        Analysis analysis = Analyses.parse(output);

        assertThat(analysis.cause()).isEqualTo("MenuService에서 null을 참조한다");
        assertThat(analysis.codeFixPossible()).isFalse();
        assertThat(analysis.fixPlan()).isEmpty();
    }

    @Test
    void 분석_출력에서_건질_것이_없으면_원인_자리에_원문을_싣지_않는다() {
        Analysis analysis = Analyses.parse("분석하다 말았습니다");

        assertThat(analysis.cause()).doesNotContain("분석하다 말았습니다");
        assertThat(analysis.cause()).contains("읽지 못했습니다");
    }

    /** 판정은 폴백 방향이 반대다. 못 읽으면 넘긴다. */
    @Test
    void 판정을_못_읽으면_분석으로_넘긴다() {
        assertThat(Triages.parse("판정하기 어렵습니다").actionNeeded()).isTrue();
        assertThat(Triages.parse("{\"action_needed\": false}").actionNeeded()).isFalse();
    }
}
