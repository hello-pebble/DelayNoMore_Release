package com.delaynomore.backend.domain.plan.dto;

// 메타 API의 선택지 한 건 — 코드(저장·검증용)와 한글 라벨(표시용) 쌍.
public record MetaOptionResponse(String code, String label) {
}
