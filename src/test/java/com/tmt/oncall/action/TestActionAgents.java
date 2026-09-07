package com.tmt.oncall.action;

import com.tmt.oncall.agent.AgentRunner;
import com.tmt.oncall.config.OncallProperties;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Jira·GitHub에 나가지 않고 결과만 정해 주는 대역들. 두 에이전트 모두 패키지 안에서만 열려 있는
 * 생성자를 쓰므로 다른 패키지의 테스트가 세우려면 이 통로가 필요하다.
 */
public final class TestActionAgents {

    private TestActionAgents() {
    }

    public static final class Jira extends JiraTicketAgent {

        public final List<TicketRequest> requests = new ArrayList<>();
        private TicketResult result = new TicketResult.Created("TMT-401", "https://jira/TMT-401");

        public Jira(OncallProperties properties) {
            super(properties, RestClient.builder());
        }

        public void returns(TicketResult next) {
            this.result = next;
        }

        @Override
        public TicketResult create(TicketRequest request) {
            requests.add(request);
            return result;
        }
    }

    public static final class PullRequests extends PullRequestAgent {

        public final List<PullRequestRequest> requests = new ArrayList<>();
        private PullRequestResult result =
                new PullRequestResult.Created("oncall/fix", "https://github/pr/1", false);
        private RuntimeException failure;

        public PullRequests(OncallProperties properties, AgentRunner runner) {
            super(properties, runner, "gh");
        }

        public void returns(PullRequestResult next) {
            this.result = next;
        }

        /** 실행 중 예외가 나도 잠금이 풀리는지 보려면 던지는 경우가 필요하다. */
        public void throwsFailure(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public PullRequestResult open(PullRequestRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
