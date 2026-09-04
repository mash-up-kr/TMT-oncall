package com.tmt.oncall.notify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 채널에 올릴 리포트 한 건. JDA에 의존하지 않는 값이라 봇 토큰 없이 제목·필드 단위로 검증할 수 있다.
 *
 * <p>Discord 임베드 제한을 여기서 지킨다. 한도를 넘기면 전송이 통째로 거절되는데,
 * 장애를 알리는 메시지가 길이 때문에 사라지는 것이 잘린 채 도착하는 것보다 나쁘다.
 */
public record ReportEmbed(String title, String description, List<Field> fields,
                          ReportColor color, Instant timestamp) {

    public static final int TITLE_LIMIT = 256;
    public static final int DESCRIPTION_LIMIT = 4096;
    public static final int FIELD_NAME_LIMIT = 256;
    public static final int FIELD_VALUE_LIMIT = 1024;
    public static final int TOTAL_LIMIT = 6000;

    private static final String ELLIPSIS = "…";

    public ReportEmbed {
        title = truncate(title, TITLE_LIMIT);
        description = truncate(description, DESCRIPTION_LIMIT);
        fields = fitTotal(title, description, List.copyOf(fields));
    }

    public record Field(String name, String value, boolean inline) {

        public Field {
            name = truncate(name, FIELD_NAME_LIMIT);
            value = truncate(value, FIELD_VALUE_LIMIT);
        }

        int length() {
            return name.length() + value.length();
        }
    }

    /** 전체 한도를 넘으면 뒤쪽 필드부터 버린다 — 앞에 놓은 것일수록 먼저 읽혀야 하는 값이다. */
    private static List<Field> fitTotal(String title, String description, List<Field> fields) {
        int used = title.length() + description.length();
        List<Field> kept = new ArrayList<>();
        for (Field field : fields) {
            if (used + field.length() > TOTAL_LIMIT) {
                break;
            }
            used += field.length();
            kept.add(field);
        }
        return List.copyOf(kept);
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit
                ? value
                : value.substring(0, limit - ELLIPSIS.length()) + ELLIPSIS;
    }
}
