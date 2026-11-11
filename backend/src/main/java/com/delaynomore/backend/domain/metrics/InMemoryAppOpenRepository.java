package com.delaynomore.backend.domain.metrics;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 인메모리(휘발성) — 지표는 postgres 프로필에서만 의미가 있고, 여기서는 멱등 계약만 테스트한다.
@Repository
@Profile("!postgres")
public class InMemoryAppOpenRepository implements AppOpenRepository {

    private final Map<String, String> opensByVisit = new ConcurrentHashMap<>(); // visitId → ownerId

    @Override
    public void record(String visitId, String ownerId, String createdAt) {
        opensByVisit.putIfAbsent(visitId, ownerId);
    }

    @Override
    public int count() {
        return opensByVisit.size();
    }
}
