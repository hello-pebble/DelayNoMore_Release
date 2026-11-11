package com.delaynomore.backend.domain.metrics;

// 앱 오픈 기록 저장소(v0.37.0) — 방문 ID가 PK라 같은 방문의 재기록은 무시된다(멱등).
// 조회 API는 없다: 집계는 운영자가 SQL로 한다(docs/OPERATIONS.md "지표 조회").
public interface AppOpenRepository {

    void record(String visitId, String ownerId, String createdAt);

    int count();
}
