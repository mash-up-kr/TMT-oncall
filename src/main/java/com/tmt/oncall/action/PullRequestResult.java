package com.tmt.oncall.action;

/**
 * PR 생성 결과. 세 갈래 모두 채널 보고로 끝나므로 흐름을 끊는 예외가 아니라 값으로 돌려준다.
 */
public sealed interface PullRequestResult {

    /** PR까지 올라갔다. {@code ticketLinked}가 거짓이면 채널에 티켓을 이어달라고 알린다. */
    record Created(String branch, String url, boolean ticketLinked) implements PullRequestResult {
    }

    /**
     * 수정은 했지만 빌드가 깨졌다. 우리 수정이 틀렸다는 뜻이라 PR을 올리지 않는다 —
     * 티켓만 못 만든 경우와 달리 결과물을 내보내지 않는다.
     */
    record BuildFailed(String branch, String reason) implements PullRequestResult {
    }

    /** 브랜치·수정·push·PR 중 어디선가 막혔다. */
    record Failed(String reason) implements PullRequestResult {
    }

    /** 채널 보고용 한 줄. */
    default String message() {
        return switch (this) {
            case Created created -> "PR을 올렸습니다 — " + created.url();
            case BuildFailed failed -> "분석은 됐지만 자동 수정에 실패했습니다 — " + failed.reason();
            case Failed failed -> failed.reason();
        };
    }
}
