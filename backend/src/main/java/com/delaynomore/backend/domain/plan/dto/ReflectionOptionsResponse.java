package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.ReflectionDifficulty;
import com.delaynomore.backend.domain.plan.entity.ReflectionReason;

import java.util.Arrays;
import java.util.List;

// 회고 화면의 선택지 묶음 — 난이도·이유는 한 화면에서 함께 쓰이므로 한 응답으로 내려준다.
public record ReflectionOptionsResponse(
        List<MetaOptionResponse> difficulties,
        List<MetaOptionResponse> reasons
) {

    public static ReflectionOptionsResponse create() {
        return new ReflectionOptionsResponse(
                Arrays.stream(ReflectionDifficulty.values())
                        .map(d -> new MetaOptionResponse(d.name(), d.getLabel()))
                        .toList(),
                Arrays.stream(ReflectionReason.values())
                        .map(r -> new MetaOptionResponse(r.name(), r.getLabel()))
                        .toList());
    }
}
