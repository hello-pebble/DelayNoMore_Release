package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.AuditEvent;

import java.util.List;

// 계획 변경 이력 저장소 계약 — 구현은 프로필로 선택된다:
//   기본(!postgres) = InMemoryAuditEventRepository(휘발성, 전역 링버퍼 상한)
//   postgres        = JdbcAuditEventRepository(PostgreSQL 영속화, 상한 없음 — 누적)
// 조회는 (planId, ownerId) 짝으로만 열려 있어 소유자별로 격리된다 — 이벤트에 소유자를 박아 두어
// 계획이 삭제된 뒤에도 소유자가 자신의 이력을 조회할 수 있다. 의도적으로 deleteAllByPlanId가 없다
// — 감사 이력은 계획 삭제(PLAN_DELETED)를 살아남아야 "언제 삭제됐는가"에 답할 수 있다.
public interface AuditEventRepository {

    // append=INSERT. 저장소가 발급한 전역 시퀀스 id가 실린 사본을 돌려준다.
    AuditEvent append(AuditEvent event);

    // (planId, ownerId) 짝으로 조회, id 내림차순(최신 이벤트가 앞) — 남의 계획은 빈 목록.
    List<AuditEvent> findAllByPlanIdAndOwner(long planId, String ownerId);

    int count();
}
