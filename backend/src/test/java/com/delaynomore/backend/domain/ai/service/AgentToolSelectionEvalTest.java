package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.agent.AgentEventSink;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.AgentToolRegistry;
import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.ai.eval.EvalCase;
import com.delaynomore.backend.domain.ai.eval.EvalDataset;
import com.delaynomore.backend.domain.ai.eval.EvalFixtures;
import com.delaynomore.backend.domain.ai.eval.EvalReport;
import com.delaynomore.backend.domain.ai.eval.EvalRunResult;
import com.delaynomore.backend.domain.ai.eval.EvalScorer;
import com.delaynomore.backend.domain.ai.eval.EvalVerdict;
import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiUsageLogger;
import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 모델로 도는 평가 실행기. {@code ./gradlew evalAgent}로만 돌고 {@code test}에서는 제외된다
 * (실제 토큰을 쓰고, 결과가 결정적이지 않아 CI 게이트로는 적합하지 않기 때문이다).
 *
 * <p>이 클래스만 {@code ...ai.service} 패키지에 있는 이유는 {@link AgentRunner#run}이 package-private
 * 이기 때문이다 — 평가는 SSE 전송 계층이 아니라 <b>루프 자체</b>를 재야 하므로, 테스트를 위해
 * 본 코드의 가시성을 넓히는 대신 테스트를 같은 패키지에 둔다({@code AgentRunnerTest}와 같은 위치).
 * 평가 데이터·채점기·리포트는 {@code ...ai.eval}에 있다.
 *
 * <pre>
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent                    # 케이스당 1회
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent -Deval.repeats=3   # 케이스당 3회(흔들림 측정)
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent -Deval.minPassRate=80
 * </pre>
 *
 * <p>합격선을 기본으로 두지 않은 것은 의도다. 모델은 결정적이지 않아 고정 임계값은 곧 무시되는
 * 빨간불이 된다. 대신 <b>권한 모델이 뚫린 경우</b>(금지 도구가 실제로 실행됨)만 무조건 실패로
 * 다룬다 — 그건 모델 품질이 아니라 설계가 깨진 것이라 흔들림으로 넘길 수 없다.
 */
@SpringBootTest
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+",
        disabledReason = "실제 모델 호출이 필요합니다 — OPENROUTER_API_KEY를 설정하고 ./gradlew evalAgent로 실행하세요")
class AgentToolSelectionEvalTest {

    private static final Path REPORT_PATH = Path.of("build", "eval", "report.md");

    @Autowired
    private AgentRunner agentRunner;
    @Autowired
    private AgentToolRegistry toolRegistry;
    @Autowired
    private PlanService planService;
    @Autowired
    private ReflectionService reflectionService;
    @Autowired
    private OpenRouterProperties properties;
    @Autowired
    private RecordingUsageLogger usageLogger;

    /**
     * 이 테스트만 이름을 ASCII로 두는 이유: {@code showStandardStreams = true} 때문에 Gradle이
     * 리포트를 콘솔로 흘릴 때마다 테스트 이름을 헤더로 함께 찍는데, Windows 콘솔 코드페이지가
     * UTF-8이 아니면 그 헤더가 깨져 보인다. 리포트 본문의 한글은 터미널 설정으로만 해결되지만
     * (chcp 65001), 이 헤더는 이름을 ASCII로 두면 어느 콘솔에서도 읽힌다.
     *
     * <p>@DisplayName이 아니라 메서드명 자체를 ASCII로 둔 것은, Gradle이 스택트레이스·XML 리포트
     * 등 여러 경로에서 메서드명을 그대로 쓰기 때문이다(DisplayName만 바꾸면 일부 경로가 남는다).
     * 나머지 테스트는 콘솔로 흐르지 않으므로 프로젝트 관례대로 한글 이름을 유지한다.
     */
    @Test
    @DisplayName("agent tool-selection eval (states x tools)")
    void evaluateToolSelectionAccuracy() throws Exception {
        // -Deval.only=notool,read.today 처럼 축을 골라 깊게 재는 용도. 고른 사실은 데이터셋 이름에
        // 남아 리포트 제목에 찍힌다 — 부분집합 결과가 전체 실행처럼 보이면 안 된다.
        EvalDataset dataset = EvalDataset.loadDefault().filter(System.getProperty("eval.only"));
        EvalFixtures fixtures = new EvalFixtures(planService, reflectionService);
        int repeats = Integer.getInteger("eval.repeats", 1);

        List<EvalRunResult> results = new ArrayList<>();
        for (EvalCase testCase : dataset.cases()) {
            for (int repeat = 1; repeat <= repeats; repeat++) {
                results.add(runOnce(testCase, repeat, fixtures));
            }
        }

        EvalReport report = new EvalReport(dataset.name(), properties.model(), repeats, results);
        String rendered = report.render();
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, rendered);
        // 경로 안내만 ASCII로 둔다 — 콘솔 인코딩이 어긋나 본문이 깨져 보이는 상황에서
        // 사용자가 유일하게 필요한 정보가 "정본 파일이 어디인가"이기 때문이다.
        System.out.println("\n" + rendered + "\n[eval] report: " + REPORT_PATH.toAbsolutePath());

        // 1) 모든 실행이 오류로 끝났다면 이건 모델 품질이 아니라 설정이 고장 난 것이다(키 만료·
        //    업스트림 장애 등). 통과율 0%를 "모델이 못했다"로 읽으면 안 되므로 따로 세운다.
        List<String> errors = results.stream().map(EvalRunResult::error).filter(java.util.Objects::nonNull).toList();
        assertThat(errors.size())
                .as("모든 케이스가 실행 오류로 끝났다 — 평가 결과가 아니라 설정 문제다. 첫 오류: %s",
                        errors.isEmpty() ? "" : errors.get(0))
                .isLessThan(results.size());

        // 2) 설계가 뚫린 경우 — 흔들림이 아니라 사고다. 무조건 빌드를 깨뜨린다.
        List<String> breaches = results.stream()
                .filter(result -> result.verdict() != null && result.verdict().permissionBreached())
                .map(result -> result.testCase().id() + " → " + result.verdict().failures())
                .toList();
        assertThat(breaches)
                .as("금지된 도구가 실제로 실행됐다 — 상태별 도구 노출이 뚫렸다")
                .isEmpty();

        // 3) 통과율 합격선 — 기본은 끔(0). 안정된 모델을 고정한 뒤 회귀 게이트로 켜는 용도다.
        int minPassRate = Integer.getInteger("eval.minPassRate", 0);
        if (minPassRate > 0) {
            long passed = results.stream().filter(EvalRunResult::passed).count();
            assertThat(Math.round(passed * 100.0 / results.size()))
                    .as("도구 선택 통과율")
                    .isGreaterThanOrEqualTo(minPassRate);
        }
    }

    private EvalRunResult runOnce(EvalCase testCase, int repeat, EvalFixtures fixtures) {
        EvalFixtures.Prepared prepared = fixtures.prepare(testCase, repeat);
        List<String> attempted = new ArrayList<>();
        AgentEventSink sink = event -> {
            if ("tool_call".equals(event.get("type"))) {
                attempted.add(String.valueOf(event.get("name")));
            }
        };

        usageLogger.reset();
        String error = null;
        try {
            agentRunner.run(request(testCase, prepared), prepared.owner(), "eval-session", sink);
        } catch (Exception e) {
            // 업스트림 오류·루프 상한은 그 케이스의 결과로 기록하고 다음 케이스를 계속 돈다 —
            // 한 케이스가 죽었다고 나머지 열다섯 개의 신호를 버릴 이유가 없다.
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        // 노출 목록은 레지스트리에서 그대로 가져온다 — 채점기가 권한 표를 다시 적으면
        // 검증 대상과 채점 기준이 같이 틀릴 수 있다.
        Set<String> exposed = new LinkedHashSet<>(
                toolRegistry.toolsFor(testCase.status()).stream().map(AgentTool::name).toList());
        EvalVerdict verdict = EvalScorer.score(testCase, attempted, exposed);

        return new EvalRunResult(testCase, repeat, verdict, attempted,
                usageLogger.total(), usageLogger.upstreamCalls(), error);
    }

    private AiChatRequest request(EvalCase testCase, EvalFixtures.Prepared prepared) {
        return new AiChatRequest("정보처리기사 실기", 7, 2, "초급", testCase.message(),
                prepared.tasks(), List.of(), prepared.planId());
    }

    /**
     * 사용량을 케이스별로 걷어내기 위한 기록용 로거. 스파이 대신 서브클래스를 쓴 이유는
     * 스프링 버전별 빈 오버라이드 API에 묶이지 않기 위해서다 — 평가 하네스는 오래 살아야 한다.
     */
    static class RecordingUsageLogger extends AiUsageLogger {

        private TokenUsage total = TokenUsage.EMPTY;
        private int upstreamCalls;

        RecordingUsageLogger(OpenRouterProperties properties) {
            super(properties);
        }

        @Override
        public void recordTotal(AiCallSite site, int calls, TokenUsage usage) {
            super.recordTotal(site, calls, usage);
            if (site == AiCallSite.AGENT_TOTAL) {
                total = total.plus(usage);
                upstreamCalls += calls;
            }
        }

        void reset() {
            total = TokenUsage.EMPTY;
            upstreamCalls = 0;
        }

        TokenUsage total() {
            return total;
        }

        int upstreamCalls() {
            return upstreamCalls;
        }
    }

    @TestConfiguration
    static class RecordingUsageLoggerConfig {

        @Bean
        @Primary
        RecordingUsageLogger recordingUsageLogger(OpenRouterProperties properties) {
            return new RecordingUsageLogger(properties);
        }
    }
}
