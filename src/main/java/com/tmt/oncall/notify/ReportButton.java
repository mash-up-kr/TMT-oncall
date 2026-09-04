package com.tmt.oncall.notify;

import java.util.Optional;

/** 리포트에 붙는 승인 버튼. 봇은 분석까지만 하고, 실행은 사람이 누른 뒤에 시작한다. */
public enum ReportButton {

    CREATE_PR("create-pr", "PR 만들기"),
    REANALYZE("reanalyze", "다시 분석"),
    IGNORE("ignore", "무시");

    /** 다른 컴포넌트의 상호작용을 우리 것으로 오인하지 않게 하는 표식. */
    private static final String PREFIX = "oncall";
    private static final String SEPARATOR = "|";

    private final String code;
    private final String label;

    ReportButton(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Discord는 상호작용에 custom ID 문자열만 돌려주므로 어느 건의 버튼인지를 여기 실어 보낸다.
     * 봇이 재시작해 메모리를 잃어도 눌린 버튼을 해석할 수 있어야 한다.
     */
    public String customId(IncidentRef ref) {
        return String.join(SEPARATOR, PREFIX, code, ref.sourceKey(), ref.externalId());
    }

    /** @return 우리가 만든 버튼이 아니면 비어 있다 */
    public static Optional<Press> parse(String customId) {
        if (customId == null) {
            return Optional.empty();
        }
        String[] parts = customId.split("\\" + SEPARATOR, 4);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        for (ReportButton button : values()) {
            if (button.code.equals(parts[1])) {
                return Optional.of(new Press(button, new IncidentRef(parts[2], parts[3])));
            }
        }
        return Optional.empty();
    }

    public record Press(ReportButton button, IncidentRef ref) {
    }
}
