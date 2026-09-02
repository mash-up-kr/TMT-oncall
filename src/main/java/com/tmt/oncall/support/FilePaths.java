package com.tmt.oncall.support;

import java.nio.file.Path;

public final class FilePaths {

    private FilePaths() {
    }

    /**
     * 앞의 {@code ~}를 사용자 홈으로 펼친다. 환경변수로 받은 경로는 셸을 거치지 않아
     * {@code ~}가 그대로 들어오기 때문에 직접 처리한다.
     */
    public static Path expand(String raw) {
        String trimmed = raw.trim();
        if (trimmed.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (trimmed.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), trimmed.substring(2));
        }
        return Path.of(trimmed);
    }
}
