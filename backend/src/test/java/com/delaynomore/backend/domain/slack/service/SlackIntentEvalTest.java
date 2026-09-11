package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient.Completion;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient.ToolCall;
import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.slack.service.SlackMessageComposer.NumberedTask;
import com.delaynomore.backend.domain.slack.support.SlackIntentPrompt;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 슬랙 자연어 의도 해석(v0.27.0)을 실제 모델로 재는 평가 — {@code ./gradlew evalAgent}로만
 * 돈다(@Tag("eval"), PlanCategoryEvalTest와 같은 게이트 재사용). 프롬프트·도구 정의는
 * 프로덕션과 같은 {@link SlackIntentPrompt}를 쓴다 — 재는 대상과 나가는 요청이 어긋나면
 * 실측이 의미를 잃는다.
 *
 * <p><b>판정 기준은 실행 전에 고정했고 사후에 바꾸지 않는다:</b>
 * <ol>
 *   <li>의도 정확률 80% 이상 — 도구 이름 일치 + complete_task는 번호(및 명시된 completed)까지
 *       일치해야 정답. 미달이면 실패</li>
 *   <li><b>무행동 케이스의 변이 호출 0건</b> — no_action이 기대인 케이스(잡담·목록 밖 작업·
 *       프롬프트 인젝션)에서 complete_task가 나오면 그 자체로 실패. 오탐 완료 체크는 오답
 *       답장보다 비싸다(사용자의 기록이 바뀐다)</li>
 * </ol>
 *
 * <pre>
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent -Deval.only=slack
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent -Deval.only=slack -Deval.threads=4
 * </pre>
 */
@SpringBootTest
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+",
        disabledReason = "실제 모델 호출이 필요합니다 — OPENROUTER_API_KEY를 설정하고 ./gradlew evalAgent로 실행하세요")
class SlackIntentEvalTest {

    private static final Path REPORT_PATH = Path.of("build", "eval", "slack-intent.md");
    private static final String DATASET = "/eval/slack-intent.json";
    private static final int MIN_ACCURACY_PERCENT = 80;
    private static final int MAX_TOKENS = 300;

    /** 데이터셋 설명과 1:1인 고정 체크리스트 — 케이스의 기대 번호가 이 목록을 참조한다. */
    private static final List<NumberedTask> FIXTURE_TASKS = List.of(
            new NumberedTask(1, 1L, "t1", "정보처리기사 실기", "기출 1회차 풀기", false),
            new NumberedTask(2, 1L, "t2", "정보처리기사 실기", "오답 노트 정리", true),
            new NumberedTask(3, 2L, "t3", "영어 회화", "쉐도잉 20분", false),
            new NumberedTask(4, 2L, "t4", "영어 회화", "영단어 30개 암기", false));

    record IntentCase(String id, String message, String expectedTool,
                      Integer expectedNumber, String expectedCompleted,
                      String expectedStart, String expectedEnd) {
    }

    record IntentDataset(String name, String description, List<IntentCase> cases) {
    }

    record IntentRun(IntentCase testCase, String actualTool, Integer actualNumber,
                     String actualCompleted, String actualStart, String actualEnd, String error) {

        boolean correct() {
            if (error != null || !testCase.expectedTool().equals(actualTool)) {
                return false;
            }
            if ("complete_task".equals(testCase.expectedTool())) {
                return Objects.equals(testCase.expectedNumber(), actualNumber)
                        && (testCase.expectedCompleted() == null
                        || testCase.expectedCompleted().equals(actualCompleted));
            }
            if ("set_active_hours".equals(testCase.expectedTool())) {
                // 기대값이 명시된 쪽만 대조 — 한쪽만 바꾸는 케이스는 다른 쪽 인자를 강제하지 않는다.
                return (testCase.expectedStart() == null || testCase.expectedStart().equals(actualStart))
                        && (testCase.expectedEnd() == null || testCase.expectedEnd().equals(actualEnd));
            }
            return true;
        }

        /** no_action 기대 케이스에서 변이 도구가 나온 경우 — 오탐 완료 체크(별도 판정 축). */
        boolean falseMutation() {
            return error == null && "no_action".equals(testCase.expectedTool())
                    && "complete_task".equals(actualTool);
        }
    }

    @Autowired
    private OpenRouterClient openRouterClient;
    @Autowired
    private OpenRouterProperties properties;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("slack intent eval (message to tool)")
    void evaluateIntentAccuracy() throws Exception {
        IntentDataset dataset = loadDataset();
        Files.deleteIfExists(REPORT_PATH); // 옛 리포트 오독 방지(기존 평가들과 같은 처리)

        int threads = Math.max(1, Integer.getInteger("eval.threads", 1));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<IntentRun> runs = new ArrayList<>();
        try {
            List<Future<IntentRun>> pending = new ArrayList<>();
            for (IntentCase testCase : dataset.cases()) {
                pending.add(pool.submit(() -> runOnce(testCase)));
            }
            for (Future<IntentRun> future : pending) {
                runs.add(future.get()); // 제출 순서 유지 — 리포트 diff가 의미 있게
            }
        } finally {
            pool.shutdownNow();
            writeReport(dataset, runs);
        }

        List<String> errors = runs.stream().map(IntentRun::error).filter(Objects::nonNull).toList();
        assertThat(errors.size())
                .as("모든 케이스가 실행 오류로 끝났다 — 평가 결과가 아니라 설정 문제다. 첫 오류: %s",
                        errors.isEmpty() ? "" : errors.getFirst())
                .isLessThan(runs.size());

        long falseMutations = runs.stream().filter(IntentRun::falseMutation).count();
        assertThat(falseMutations)
                .as("무행동 기대 케이스에서 완료 체크가 나왔다 — 오탐 변이는 정확률과 별개로 0이어야 한다")
                .isZero();

        long correct = runs.stream().filter(IntentRun::correct).count();
        assertThat(correct * 100 / runs.size())
                .as("의도 정확률이 기준(%d%%) 미달이다 — %d/%d", MIN_ACCURACY_PERCENT, correct, runs.size())
                .isGreaterThanOrEqualTo(MIN_ACCURACY_PERCENT);
    }

    /** 프로덕션 기본 활동시간과 같은 픽스처(09:00~21:00) — 프롬프트의 [현재 활동시간] 문맥. */
    private static final int FIXTURE_START_MIN = 540;
    private static final int FIXTURE_END_MIN = 1260;

    private IntentRun runOnce(IntentCase testCase) {
        try {
            Completion completion = openRouterClient.completeWithTools(AiCallSite.SLACK_INTENT,
                    SlackIntentPrompt.messages(FIXTURE_TASKS, FIXTURE_START_MIN, FIXTURE_END_MIN,
                            testCase.message()), MAX_TOKENS, SlackIntentPrompt.tools());
            if (!completion.hasToolCalls()) {
                // 도구 없이 산문만 — 프로덕션에서는 그대로 답장이 되지만(변이 없음) 평가에서는
                // "도구를 하나 호출하라"는 규칙 위반이므로 no_action으로도 인정하지 않는다.
                return new IntentRun(testCase, "(none)", null, null, null, null, null);
            }
            ToolCall call = completion.toolCalls().getFirst();
            JsonNode args = jsonMapper.readTree(call.argumentsJson() == null || call.argumentsJson().isBlank()
                    ? "{}" : call.argumentsJson());
            Integer number = null;
            try {
                number = Integer.parseInt(args.path("number").asString("").trim());
            } catch (NumberFormatException ignored) {
                // 번호 없는 complete_task는 correct()에서 오답 처리된다
            }
            String completed = args.path("completed").asString("true").trim();
            String start = blankToNull(args.path("start").asString(""));
            String end = blankToNull(args.path("end").asString(""));
            return new IntentRun(testCase, call.name(), number,
                    completed.isEmpty() ? "true" : completed, start, end, null);
        } catch (Exception e) {
            return new IntentRun(testCase, null, null, null, null, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static IntentDataset loadDataset() throws Exception {
        try (InputStream in = SlackIntentEvalTest.class.getResourceAsStream(DATASET)) {
            if (in == null) {
                throw new IllegalStateException("평가 데이터셋을 찾을 수 없습니다: " + DATASET);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            IntentDataset loaded = JsonMapper.builder().build().readValue(json, IntentDataset.class);
            String only = System.getProperty("eval.only");
            if (only == null || only.isBlank()) {
                return loaded;
            }
            // -Deval.only는 축 선택 스위치 — 이 축(slack.*)과 무관한 값이면 건너뛴다(기존 평가 관례).
            List<IntentCase> filtered = loaded.cases().stream()
                    .filter(c -> Arrays.stream(only.split(","))
                            .anyMatch(prefix -> c.id().startsWith(prefix.trim())))
                    .toList();
            if (filtered.isEmpty()) {
                org.junit.jupiter.api.Assumptions.abort(
                        "-Deval.only=" + only + " 는 이 축(slack.*)의 케이스와 맞지 않아 건너뜁니다.");
            }
            return new IntentDataset(loaded.name() + " (only=" + only + ")", loaded.description(), filtered);
        }
    }

    private void writeReport(IntentDataset dataset, List<IntentRun> runs) throws Exception {
        long correct = runs.stream().filter(IntentRun::correct).count();
        long falseMutations = runs.stream().filter(IntentRun::falseMutation).count();
        StringBuilder out = new StringBuilder();
        out.append("# 슬랙 의도 해석 평가 — ").append(dataset.name()).append("\n\n");
        out.append("- 모델: `").append(properties.model()).append("`\n");
        out.append("- 케이스 ").append(runs.size()).append("개 · 고정 체크리스트 ")
                .append(FIXTURE_TASKS.size()).append("항목\n");
        out.append("- 의도 정확률: **").append(runs.isEmpty() ? 0 : correct * 100 / runs.size())
                .append("%** (").append(correct).append("/").append(runs.size())
                .append(") · 합격선 ").append(MIN_ACCURACY_PERCENT).append("%\n");
        out.append("- 오탐 변이(무행동 기대 → complete_task): **").append(falseMutations)
                .append("건** · 합격선 0건\n\n");
        out.append("| 케이스 | 메시지 | 기대 | 실제 |\n");
        out.append("| :--- | :--- | :--- | :--- |\n");
        for (IntentRun run : runs) {
            String expected = run.testCase().expectedTool()
                    + (run.testCase().expectedNumber() != null ? " #" + run.testCase().expectedNumber() : "")
                    + (run.testCase().expectedCompleted() != null ? " completed=" + run.testCase().expectedCompleted() : "")
                    + (run.testCase().expectedStart() != null ? " start=" + run.testCase().expectedStart() : "")
                    + (run.testCase().expectedEnd() != null ? " end=" + run.testCase().expectedEnd() : "");
            String actual = run.error() != null ? "오류: " + run.error()
                    : (run.correct() ? "정답 " : "오답 ") + run.actualTool()
                    + (run.actualNumber() != null ? " #" + run.actualNumber() : "")
                    + (run.actualStart() != null ? " start=" + run.actualStart() : "")
                    + (run.actualEnd() != null ? " end=" + run.actualEnd() : "");
            out.append("| `").append(run.testCase().id()).append("` | ")
                    .append(run.testCase().message()).append(" | ")
                    .append(expected).append(" | ").append(actual).append(" |\n");
        }
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, out.toString());
        System.out.println(out);
    }
}
