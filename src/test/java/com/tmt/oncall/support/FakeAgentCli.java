package com.tmt.oncall.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 준비된 응답을 순서대로 돌려주는 에이전트 CLI 대역. 스킬은 아직 마켓플레이스에 없으므로
 * 여기서 봐야 하는 것은 답변 문구가 아니라 어떤 스킬을 무엇을 담아 어디서 불렀는가다.
 *
 * <p>
 * 호출마다 인자와 작업 디렉터리를 파일로 남긴다 — 소스를 읽지 않기로 한 경로가 정말
 * 빈 디렉터리에서 돌았는지는 그 기록으로만 확인할 수 있다.
 */
public final class FakeAgentCli {

    private final Path root;
    private final Path binary;

    private FakeAgentCli(Path root, Path binary) {
        this.root = root;
        this.binary = binary;
    }

    public static FakeAgentCli create() {
        try {
            Path root = Files.createTempDirectory("fake-agent-cli");
            return new FakeAgentCli(root, script(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String binary() {
        return binary.toAbsolutePath().toString();
    }

    /** 다음 호출이 돌려줄 스킬 출력. 호출별로 지정하지 않은 순번은 이 값을 쓴다. */
    public void answers(String skillOutput) {
        write(root.resolve("response.json"), envelope(skillOutput));
    }

    /** @param call 1부터 세는 호출 순번 */
    public void answers(int call, String skillOutput) {
        write(root.resolve("response-" + call + ".json"), envelope(skillOutput));
    }

    /** CLI가 오류를 돌려준 경우. 봉투 자체가 달라 정상 출력과 섞이지 않는다. */
    public void fails(int call, String reason) {
        write(root.resolve("response-" + call + ".json"),
                "{\"is_error\": true, \"result\": %s}".formatted(quoted(reason)));
    }

    /** @return 그 호출에 넘어간 인자 전부 */
    public String arguments(int call) {
        return read(root.resolve("agent-args-" + call + ".txt"));
    }

    /** @return stdin으로 넘어간 프롬프트. 스킬 이름이 첫 줄에 있다 */
    public String prompt(int call) {
        return read(root.resolve("agent-prompt-" + call + ".txt"));
    }

    public String workingDirectory(int call) {
        return read(root.resolve("cwd-" + call + ".txt")).strip();
    }

    public int calls() {
        int call = 0;
        while (Files.exists(root.resolve("agent-args-" + (call + 1) + ".txt"))) {
            call++;
        }
        return call;
    }

    private static String envelope(String skillOutput) {
        return "{\"is_error\": false, \"result\": %s, \"usage\": {}}"
                .formatted(quoted(skillOutput.strip()));
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static Path script(Path root) throws IOException {
        Path script = Files.writeString(root.resolve("fake-claude"), """
                #!/bin/sh
                dir=%s
                i=1
                while [ -f "$dir/agent-args-$i.txt" ]; do i=$((i+1)); done
                : > "$dir/agent-args-$i.txt"
                for arg in "$@"; do printf '%%s\\n' "$arg" >> "$dir/agent-args-$i.txt"; done
                cat > "$dir/agent-prompt-$i.txt"
                pwd > "$dir/cwd-$i.txt"
                if [ -f "$dir/response-$i.json" ]; then
                  cat "$dir/response-$i.json"
                else
                  cat "$dir/response.json"
                fi
                """.formatted(root.toAbsolutePath()));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static void write(Path file, String body) {
        try {
            Files.writeString(file, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
