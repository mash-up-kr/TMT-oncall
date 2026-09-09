package com.tmt.oncall.config;

import com.tmt.oncall.core.Audience;
import com.tmt.oncall.core.BillingMode;
import com.tmt.oncall.core.CallPath;
import com.tmt.oncall.support.FilePaths;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

@ConfigurationProperties(prefix = "oncall")
public record OncallProperties(
        boolean enabled,
        Target target,
        Discord discord,
        Jira jira,
        Github github,
        Sentry sentry,
        Agent agent,
        Guard guard,
        Store store) {

    public record Discord(String botToken, Roles roles) {

        public record Roles(String design, String web, String spring) {
        }

        /**
         * 질문자의 역할 ID 목록으로 답변 톤을 정한다. 여러 역할을 가진 경우
         * 가장 기술적인 톤(스프링 → 웹 → 디자인)을 고른다.
         */
        public Audience audienceOf(Collection<String> roleIds) {
            if (matches(roles.spring(), roleIds)) {
                return Audience.SPRING;
            }
            if (matches(roles.web(), roleIds)) {
                return Audience.WEB;
            }
            if (matches(roles.design(), roleIds)) {
                return Audience.DESIGN;
            }
            return Audience.DEFAULT;
        }

        private static boolean matches(String configured, Collection<String> roleIds) {
            return configured != null && !configured.isBlank() && roleIds.contains(configured);
        }
    }

    public record Jira(String baseUrl, String email, String apiToken) {
    }

    public record Github(String token) {
    }

    public record Sentry(String baseUrl, String authToken, String orgSlug, Duration pollInterval) {
    }

    /**
     * 봇이 헤드리스로 실행하는 에이전트 CLI.
     *
     * @param billing 종량제 / 구독제. 인증 방식과 비용 집계 여부가 여기서 함께 정해진다.
     *                전환은 이 값 하나만 바꾸면 되고 호출 코드는 그대로다
     * @param scratch 소스를 읽지 않는 경로(1차 분류)에 주는 빈 작업 디렉터리. 대상 클론을 주면
     *                읽지 않기로 한 소스가 CLI의 시야에 들어온다
     * @param models  경로별 모델과 단가. 모델을 바꾸면 단가도 같이 바꿔야 비용 집계가 어긋나지 않는다
     * @param maxEventChars 프롬프트에 싣는 Sentry 대표 이벤트 원본의 상한(글자). 이벤트에는 상한이
     *                없어 컨텍스트를 넘길 수 있는데, 그때 CLI는 호출료를 다 쓴 뒤에 실패한다
     */
    public record Agent(String binary, Duration timeout, BillingMode billing, String scratch,
                        int maxEventChars, Map<CallPath, Model> models) {

        public record Model(String id, double inputPerMtok, double outputPerMtok) {
        }

        public Path scratchPath() {
            return FilePaths.expand(scratch);
        }

        public Model modelFor(CallPath path) {
            Model model = models.get(path);
            if (model == null) {
                throw new IllegalStateException("모델 설정이 없는 경로: " + path);
            }
            return model;
        }
    }

    /**
     * 비용 집계 여부는 여기 있지 않다 — {@link Agent#billing()}에서 파생된다.
     *
     * @param duplicateWindow 같은 건을 다시 알리지 않는 최소 간격
     */
    public record Guard(
            int maxCallsPerHour,
            int maxCallsPerDay,
            double maxCostPerMonth,
            Duration duplicateWindow) {
    }

    public record Store(String path) {

        public Path resolvedPath() {
            return FilePaths.expand(path);
        }
    }
}
