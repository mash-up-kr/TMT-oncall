package com.tmt.oncall.notify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSplitterTest {

    @Test
    void 상한_안이면_자르지_않는다() {
        assertThat(MessageSplitter.split("짧은 리포트")).containsExactly("짧은 리포트");
    }

    @Test
    void 상한을_넘으면_모든_조각이_상한_안에_들어간다() {
        String message = ("스택 한 줄\n").repeat(500);

        List<String> chunks = MessageSplitter.split(message);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(2000));
        assertThat(String.join("\n", chunks).replace("\n", "")).isEqualTo(message.replace("\n", ""));
    }

    @Test
    void 경계에_딱_맞으면_그대로_보낸다() {
        String message = "a".repeat(2000);

        assertThat(MessageSplitter.split(message)).containsExactly(message);
    }

    @Test
    void 한_글자만_넘어도_나눈다() {
        String message = "a".repeat(2001);

        assertThat(MessageSplitter.split(message)).hasSize(2);
    }

    /** 코드 블록이 열린 채로 조각이 끝나면 다음 조각까지 통째로 코드로 렌더된다. */
    @Test
    void 코드_블록_경계에서_펜스를_닫고_다시_연다() {
        String message = "머리말\n```java\n" + "System.out.println(1);\n".repeat(200) + "```\n꼬리말";

        List<String> chunks = MessageSplitter.split(message);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(2000);
            // 조각 안에서 펜스가 짝을 이뤄야 열린 채로 끝나지 않는다
            assertThat(chunk.split("```", -1).length % 2).isEqualTo(1);
        });
        assertThat(chunks.get(1)).startsWith("```java");
        assertThat(chunks.getLast()).endsWith("꼬리말");
    }

    /** 스택트레이스 한 줄이 상한보다 길 수 있다. 버리지 않고 문자 단위로 끊는다. */
    @Test
    void 상한보다_긴_한_줄도_전부_보낸다() {
        String line = "x".repeat(5000);

        List<String> chunks = MessageSplitter.split(line);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(2000));
        assertThat(String.join("", chunks).replace("\n", "")).isEqualTo(line);
    }
}
