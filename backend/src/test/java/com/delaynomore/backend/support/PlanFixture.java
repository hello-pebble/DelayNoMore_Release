package com.delaynomore.backend.support;

import com.delaynomore.backend.domain.plan.entity.Plan;

import java.time.Instant;
import java.util.Map;

/**
 * 챌린지 참가 자격용 계획 픽스처 — <b>고정(CONFIRMED)된, 조건이 분명한 계획 한 건</b>.
 *
 * <p><b>왜 생겼나.</b> v0.25.0이 참가 전제조건을 추가했다(같은 조건의 CONFIRMED 계획이 있어야
 * 참가할 수 있다). 그때 PostgreSQL IT 네 곳이 각자 {@code new Plan(...)}을 손으로 쓰고 있었고,
 * 그 중 두 곳이 전제조건을 따라오지 못해 CI가 8개 릴리스 동안 red였다
 * (docs/QA_RESULT_v0.26.0-v0.32.0.md 3-1장). 복사본을 하나 더 손으로 만드는 것이 바로 그
 * 사고의 양식이라, 15개 컴포넌트를 쓰는 자리를 여기 하나로 모은다.
 *
 * <p><b>왜 {@code AbstractPostgresIntegrationTest}가 아니라 여기인가.</b> 그 클래스는 DB 수명주기
 * (컨테이너·TRUNCATE)를 소유하고, 이건 도메인 픽스처다. 여기 두면 인메모리 테스트도 같은 픽스처를
 * 쓸 수 있다 — 이미 {@link PointsFixture}가 같은 자리에 있다.
 *
 * <p><b>조건 키의 소유자는 category다.</b> {@code conditionKey()}는 (category, 기간 버킷)의 순수
 * 함수라, 여기서 기간을 {@link #DURATION_DAYS}로 고정하면 키는 언제나 {@code "<category>:14"}다.
 * goalName은 키에 영향을 주지 않으므로(category가 있으면 키워드 폴백을 타지 않는다) 읽기용으로만
 * 둔다.
 *
 * <p>저장은 호출부가 한다 — 이 클래스는 저장되지 않은 {@link Plan}만 만든다. 저장소를 주입받지
 * 않으므로 JDBC·인메모리 어느 쪽에서도 쓸 수 있고, id가 필요한 호출부는
 * {@code planRepository.save(...).id()}로 받는다.
 */
public final class PlanFixture {

    /**
     * 기본 기간 — 챌린지 조건의 기간 버킷(7·14·30·60·90) 중 하나여야 조건이 성립한다.
     * 14를 쓰는 이유는 기존 IT들이 모두 {@code "<category>:14"}를 기대하기 때문이다.
     */
    public static final int DURATION_DAYS = 14;

    private static final int DAILY_HOURS = 2;
    private static final String LEVEL = "초급";
    private static final String START_DATE = "2026-09-01";
    private static final String END_DATE = "2026-09-14";

    private PlanFixture() {
    }

    /** 할 일이 없는 고정 계획 — 참가 자격만 필요한 테스트용(완주 판정은 0/0이라 "완주 아님"). */
    public static Plan confirmed(String owner, String category) {
        return confirmed(owner, category, Map.of());
    }

    /**
     * 할 일을 지정한 고정 계획 — 완료율이 판정에 쓰이는 테스트용(정산의 승·패, 예치 환급률).
     *
     * @param tasks 프론트 스키마 그대로의 {@code {날짜: [{id, content, completed}]}}
     */
    public static Plan confirmed(String owner, String category, Map<String, Object> tasks) {
        String now = Instant.now().toString();
        return new Plan(null, owner, category + " 연습", DURATION_DAYS, DAILY_HOURS, LEVEL, tasks,
                "CONFIRMED", now, null, START_DATE, END_DATE, now, System.currentTimeMillis(), category);
    }
}
