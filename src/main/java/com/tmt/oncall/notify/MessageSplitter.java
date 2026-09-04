package com.tmt.oncall.notify;

import java.util.ArrayList;
import java.util.List;

/**
 * Discord 메시지 상한에 맞춰 자른다. 그냥 2000자에서 끊으면 코드 블록이 열린 채 끝나
 * 다음 조각까지 통째로 코드로 렌더되므로, 경계에서 펜스를 닫고 다시 연다.
 */
public final class MessageSplitter {

    /** Discord 메시지 본문 상한. */
    public static final int LIMIT = 2000;

    private static final String FENCE = "```";

    private MessageSplitter() {
    }

    public static List<String> split(String message) {
        return split(message, LIMIT);
    }

    static List<String> split(String message, int limit) {
        if (message.length() <= limit) {
            return List.of(message);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String openFence = null;

        for (String line : message.split("\n", -1)) {
            for (String piece : hardSplit(line, limit - FENCE.length() * 2 - 2)) {
                String candidate = current.isEmpty() ? piece : current + "\n" + piece;
                boolean needsClosing = openFence != null;
                if (candidate.length() + (needsClosing ? FENCE.length() + 1 : 0) > limit) {
                    chunks.add(needsClosing ? current + "\n" + FENCE : current.toString());
                    current = new StringBuilder(openFence == null ? piece : openFence + "\n" + piece);
                } else {
                    current = new StringBuilder(candidate);
                }
                openFence = trackFence(openFence, piece);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(openFence == null ? current.toString() : current + "\n" + FENCE);
        }
        return chunks;
    }

    /** @return 이 줄을 지난 뒤에도 열려 있는 펜스(언어 지정 포함), 닫혀 있으면 null */
    private static String trackFence(String openFence, String line) {
        if (!line.stripLeading().startsWith(FENCE)) {
            return openFence;
        }
        return openFence == null ? line.strip() : null;
    }

    /** 줄 하나가 상한을 넘으면(스택트레이스 한 줄 등) 어쩔 수 없이 문자 단위로 끊는다. */
    private static List<String> hardSplit(String line, int limit) {
        if (line.length() <= limit) {
            return List.of(line);
        }
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < line.length(); start += limit) {
            pieces.add(line.substring(start, Math.min(start + limit, line.length())));
        }
        return pieces;
    }
}
