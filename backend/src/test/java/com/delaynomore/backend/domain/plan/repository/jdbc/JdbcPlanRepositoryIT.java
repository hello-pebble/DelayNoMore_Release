package com.delaynomore.backend.domain.plan.repository.jdbc;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// JdbcPlanRepository 통합 테스트 — 실제 PostgreSQL에 대한 저장·조회·원자 가드 동작을 검증한다.
class JdbcPlanRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private ReflectionRepository reflectionRepository;

    private static Plan draft(String owner, String goal, long savedAt) {
        Map<String, Object> tasks = Map.of(
                "2026-07-21", List.of(Map.of("id", "t1", "content", "1강 수강", "completed", true)),
                "2026-07-22", List.of(Map.of("id", "t2", "content", "2강 수강", "completed", false)));
        return new Plan(null, owner, goal, 2, 3, "초급", tasks,
                "DRAFT", null, "2026-07-21", "2026-07-22", "2026-07-21T09:00:00Z", savedAt);
    }

    @Test
    void save_assignsId_andFindByIdRoundtripsAllFieldsIncludingJsonbTasks() {
        Plan saved = planRepository.save(draft("guest-1", "정보처리기사", 1000L));

        assertThat(saved.id()).isNotNull().isPositive();
        Optional<Plan> found = planRepository.findById(saved.id());
        assertThat(found).isPresent();
        Plan p = found.get();
        assertThat(p.owner()).isEqualTo("guest-1");
        assertThat(p.goalName()).isEqualTo("정보처리기사");
        assertThat(p.duration()).isEqualTo(2);
        assertThat(p.dailyHours()).isEqualTo(3);
        assertThat(p.status()).isEqualTo("DRAFT");
        assertThat(p.startDate()).isEqualTo("2026-07-21");
        assertThat(p.savedAt()).isEqualTo(1000L);
        // JSONB tasks 왕복 — 중첩 구조와 completed 플래그가 그대로 복원된다.
        assertThat(p.tasks()).containsKeys("2026-07-21", "2026-07-22");
        assertThat(p.countAllTasks()).isEqualTo(new Plan.TaskCounts(1, 2));
    }

    @Test
    void findAllByOwner_ordersBySavedAtThenIdDesc_andExcludesOtherOwners() {
        Plan a = planRepository.save(draft("guest-1", "A", 100L));
        Plan b = planRepository.save(draft("guest-1", "B", 300L));
        Plan c = planRepository.save(draft("guest-1", "C", 300L)); // b와 동률 savedAt → id 내림차순
        planRepository.save(draft("guest-2", "남의 계획", 999L));

        List<Plan> mine = planRepository.findAllByOwner("guest-1");

        assertThat(mine).extracting(Plan::id).containsExactly(c.id(), b.id(), a.id());
        assertThat(mine).extracting(Plan::goalName).doesNotContain("남의 계획");
    }

    @Test
    void countByOwner_andCount() {
        planRepository.save(draft("guest-1", "A", 1L));
        planRepository.save(draft("guest-1", "B", 2L));
        planRepository.save(draft("guest-2", "C", 3L));

        assertThat(planRepository.countByOwner("guest-1")).isEqualTo(2L);
        assertThat(planRepository.countByOwner("guest-2")).isEqualTo(1L);
        assertThat(planRepository.count()).isEqualTo(3);
    }

    @Test
    void update_returnsPreviousRow_andPersistsNewValues() {
        Plan saved = planRepository.save(draft("guest-1", "원래 목표", 1L));
        Plan updated = new Plan(saved.id(), "guest-1", "바뀐 목표", 2, 3, "중급",
                saved.tasks(), "DRAFT", null, "2026-07-21", "2026-07-22", saved.createdAt(), 2L);

        Plan previous = planRepository.update(updated, current -> { /* no guard */ });

        assertThat(previous.goalName()).isEqualTo("원래 목표"); // 반환은 교체 전 값
        assertThat(planRepository.findById(saved.id()).orElseThrow().goalName()).isEqualTo("바뀐 목표");
    }

    @Test
    void update_whenGuardThrows_rollsBackAndRowUnchanged() {
        Plan saved = planRepository.save(draft("guest-1", "원래 목표", 1L));
        Plan attempted = new Plan(saved.id(), "guest-1", "침범 시도", 2, 3, "중급",
                saved.tasks(), "DRAFT", null, "2026-07-21", "2026-07-22", saved.createdAt(), 2L);

        assertThatThrownBy(() -> planRepository.update(attempted, current -> {
            throw new IllegalStateException("가드 거부");
        })).isInstanceOf(IllegalStateException.class);

        // 가드가 예외를 던졌으므로 트랜잭션 롤백 → 행은 원래 값 그대로.
        assertThat(planRepository.findById(saved.id()).orElseThrow().goalName()).isEqualTo("원래 목표");
    }

    @Test
    void update_returnsNull_whenAbsent() {
        Plan ghost = new Plan(9999L, "guest-1", "없음", 1, 1, "초급", Map.of(),
                "DRAFT", null, "2026-07-21", "2026-07-21", "x", 1L);
        assertThat(planRepository.update(ghost, current -> { })).isNull();
    }

    @Test
    void mutate_readModifyWrite_extendsPlan() {
        Plan saved = planRepository.save(draft("guest-1", "이월 대상", 1L));

        Plan result = planRepository.mutate(saved.id(), current -> new Plan(
                current.id(), current.owner(), current.goalName(), 3, current.dailyHours(),
                current.currentLevel(), current.tasks(), current.status(), current.confirmedAt(),
                current.startDate(), "2026-07-23", current.createdAt(), 5L));

        assertThat(result.duration()).isEqualTo(3);
        assertThat(result.endDate()).isEqualTo("2026-07-23");
        assertThat(planRepository.findById(saved.id()).orElseThrow().endDate()).isEqualTo("2026-07-23");
    }

    @Test
    void deleteById_returnsRemovedRow_andCascadesReflections() {
        Plan saved = planRepository.save(draft("guest-1", "삭제 대상", 1L));
        // 회고를 하나 붙여 FK ON DELETE CASCADE가 함께 지우는지 확인한다.
        reflectionRepository.upsert(saved.id(), "2026-07-21", existing ->
                new com.delaynomore.backend.domain.plan.entity.Reflection(
                        saved.id(), "2026-07-21", 1, 2, "NORMAL", "AS_PLANNED",
                        "2026-07-21T10:00:00Z", "2026-07-21T10:00:00Z"));
        assertThat(reflectionRepository.findAllByPlanId(saved.id())).hasSize(1);

        Plan removed = planRepository.deleteById(saved.id(), current -> { });

        assertThat(removed.goalName()).isEqualTo("삭제 대상");
        assertThat(planRepository.findById(saved.id())).isEmpty();
        assertThat(reflectionRepository.findAllByPlanId(saved.id())).isEmpty(); // 캐스케이드
    }

    @Test
    void deleteById_whenGuardThrows_rowIntact() {
        Plan saved = planRepository.save(draft("guest-1", "보존", 1L));

        assertThatThrownBy(() -> planRepository.deleteById(saved.id(), current -> {
            throw new IllegalStateException("소유자 불일치");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(planRepository.findById(saved.id())).isPresent();
    }
}
