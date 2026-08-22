package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.challenge.support.ChallengeCondition;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 초안 생성 프롬프트에 얹은 <b>목적 분류</b>를 실제 모델로 재는 평가.
 * {@code ./gradlew evalAgent}로만 돈다(@Tag("eval") — 같은 게이트를 재사용하므로 새 태스크는 없다).
 *
 * <p>이 평가가 존재하는 이유: 기존 evalAgent는 <b>도구 선택만</b> 채점해 초안 프롬프트를 덮지 않는다.
 * 프롬프트 문구는 실측 근거와 함께 바꾼다는 저장소 규칙(CLAUDE.md)을 지키려면 이 축을 따로 재야 한다.
 *
 * <p><b>판정 기준은 실행 전에 고정했고 사후에 바꾸지 않는다:</b>
 * <ol>
 *   <li>카테고리 정확 일치율 90% 이상 — 미달이면 실패</li>
 *   <li>계획 계약 유지 100% — 모든 응답이 유효한 날짜 키 + 비어 있지 않은 할 일. 이쪽이 더 중요하다:
 *       카테고리를 요구하느라 초안 자체가 무너지면 얻는 것보다 잃는 것이 크다</li>
 *   <li>하루 할 일 개수 분포는 리포트에만 남긴다(빌드 판정에 쓰지 않음)</li>
 * </ol>
 *
 * <pre>
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent -Deval.only=category
 *   OPENROUTER_API_KEY=... ./gradlew evalAgent -Deval.only=category -Deval.threads=4
 * </pre>
 */
@SpringBootTest
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+",
        disabledReason = "실제 모델 호출이 필요합니다 — OPENROUTER_API_KEY를 설정하고 ./gradlew evalAgent로 실행하세요")
class PlanCategoryEvalTest {

    private static final Path REPORT_PATH = Path.of("build", "eval", "plan-category.md");
    private static final String DATASET = "/eval/plan-category.json";
    private static final int MIN_ACCURACY_PERCENT = 90;
    // 초안 자체는 짧게 — 재려는 것은 계획의 질이 아니라 분류이므로 토큰을 최소로 쓴다.
    private static final int DURATION_DAYS = 3;

    record CategoryCase(String id, String goalName, String expected) {
    }

    record CategoryDataset(String name, String description, List<CategoryCase> cases) {
    }

    record CategoryRun(CategoryCase testCase, String actual, int dayCount, int taskCount, String error) {
        boolean correct() {
            return error == null && testCase.expected().equals(actual);
        }

        boolean contractHeld() {
            return error == null && dayCount > 0 && taskCount > 0;
        }
    }

    @Autowired
    private AiService aiService;
    @Autowired
    private OpenRouterProperties properties;

    @Test
    @DisplayName("plan category eval (goal to category)")
    void evaluateCategoryAccuracy() throws Exception {
        CategoryDataset dataset = loadDataset();
        // 이전 실행의 리포트를 먼저 지운다 — 실행이 죽었을 때 옛 리포트를 이번 결과로 오독하지 않도록
        // (AgentToolSelectionEvalTest와 같은 이유·같은 처리).
        Files.deleteIfExists(REPORT_PATH);

        int threads = Math.max(1, Integer.getInteger("eval.threads", 1));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<CategoryRun> runs = new ArrayList<>();
        try {
            List<Future<CategoryRun>> pending = new ArrayList<>();
            for (CategoryCase testCase : dataset.cases()) {
                pending.add(pool.submit(() -> runOnce(testCase)));
            }
            // 제출 순서대로 걷는다 — 완료 순서로 걷으면 리포트 행 순서가 실행마다 달라져 diff가 무의미해진다.
            for (Future<CategoryRun> future : pending) {
                runs.add(future.get());
            }
        } finally {
            pool.shutdownNow();
            writeReport(dataset, runs);
        }

        // 전부 오류면 모델 품질이 아니라 설정이 고장 난 것이다(키 만료·업스트림 장애).
        List<String> errors = runs.stream().map(CategoryRun::error).filter(Objects::nonNull).toList();
        assertThat(errors.size())
                .as("모든 케이스가 실행 오류로 끝났다 — 평가 결과가 아니라 설정 문제다. 첫 오류: %s",
                        errors.isEmpty() ? "" : errors.getFirst())
                .isLessThan(runs.size());

        long contractHeld = runs.stream().filter(CategoryRun::contractHeld).count();
        assertThat(contractHeld)
                .as("초안 계약이 깨졌다 — 카테고리를 요구하느라 날짜 키·할 일이 무너졌다면 프롬프트를 되돌린다")
                .isEqualTo(runs.size());

        long correct = runs.stream().filter(CategoryRun::correct).count();
        assertThat(correct * 100 / runs.size())
                .as("카테고리 정확 일치율이 기준(%d%%) 미달이다 — %d/%d", MIN_ACCURACY_PERCENT, correct, runs.size())
                .isGreaterThanOrEqualTo(MIN_ACCURACY_PERCENT);
    }

    private CategoryRun runOnce(CategoryCase testCase) {
        try {
            AiService.DraftResult result = aiService.createDraft(new AiDraftRequest(
                    testCase.goalName(), DURATION_DAYS, 2, "초급", null, null, null));
            // 목록 밖 라벨은 파서가 이미 걸러 null로 온다 — 그 경우 모델은 사실상 "기타"를 고른 것이다.
            String actual = result.category() != null ? result.category() : ChallengeCondition.UNCLASSIFIED;
            int taskCount = result.plan().values().stream()
                    .mapToInt(day -> day instanceof List<?> list ? list.size() : 0).sum();
            return new CategoryRun(testCase, actual, result.plan().size(), taskCount, null);
        } catch (Exception e) {
            return new CategoryRun(testCase, null, 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static CategoryDataset loadDataset() throws Exception {
        try (InputStream in = PlanCategoryEvalTest.class.getResourceAsStream(DATASET)) {
            if (in == null) {
                throw new IllegalStateException("평가 데이터셋을 찾을 수 없습니다: " + DATASET);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            CategoryDataset loaded = JsonMapper.builder().build().readValue(json, CategoryDataset.class);
            String only = System.getProperty("eval.only");
            if (only == null || only.isBlank()) {
                return loaded;
            }
            // evalAgent의 -Deval.only는 두 평가(도구 선택·목적 분류)가 공유하는 스위치다. 이 축과
            // 무관한 값이면(예: -Deval.only=notool) 케이스가 0개가 되는데, 그때 전체를 대신 돌리면
            // 고르지도 않은 20건의 토큰을 태우게 된다. 실패도 아니다 — 다른 축을 골랐을 뿐이라
            // 건너뛴다(스킵은 리포트에 남아 "재지 않았다"가 눈에 보인다).
            List<CategoryCase> filtered = loaded.cases().stream()
                    .filter(c -> Arrays.stream(only.split(","))
                            .anyMatch(prefix -> c.id().startsWith(prefix.trim())))
                    .toList();
            if (filtered.isEmpty()) {
                org.junit.jupiter.api.Assumptions.abort(
                        "-Deval.only=" + only + " 는 이 축(category.*)의 케이스와 맞지 않아 건너뜁니다.");
            }
            return new CategoryDataset(loaded.name() + " (only=" + only + ")", loaded.description(), filtered);
        }
    }

    private void writeReport(CategoryDataset dataset, List<CategoryRun> runs) throws Exception {
        long correct = runs.stream().filter(CategoryRun::correct).count();
        StringBuilder out = new StringBuilder();
        out.append("# 계획 목적 분류 평가 — ").append(dataset.name()).append("\n\n");
        out.append("- 모델: `").append(properties.model()).append("`\n");
        out.append("- 케이스 ").append(runs.size()).append("개 · 기간 ").append(DURATION_DAYS).append("일\n");
        out.append("- 정확 일치율: **").append(runs.isEmpty() ? 0 : correct * 100 / runs.size())
                .append("%** (").append(correct).append("/").append(runs.size())
                .append(") · 합격선 ").append(MIN_ACCURACY_PERCENT).append("%\n\n");
        out.append("| 케이스 | 목표 | 기대 | 실제 | 날짜 | 할 일 |\n");
        out.append("| :--- | :--- | :--- | :--- | ---: | ---: |\n");
        for (CategoryRun run : runs) {
            out.append("| `").append(run.testCase().id()).append("` | ")
                    .append(run.testCase().goalName()).append(" | ")
                    .append(run.testCase().expected()).append(" | ")
                    .append(run.error() != null ? "오류: " + run.error()
                            : (run.correct() ? "정답 " : "오답 ") + run.actual()).append(" | ")
                    .append(run.dayCount()).append(" | ").append(run.taskCount()).append(" |\n");
        }
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, out.toString());
        System.out.println(out);
    }
}
