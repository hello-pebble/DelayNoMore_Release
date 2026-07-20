package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.MetaOptionResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionOptionsResponse;
import com.delaynomore.backend.domain.plan.entity.AuditEventType;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 메타(선택지·라벨) API — 프론트가 하드코딩하던 회고 선택지·이력 라벨의 소스오브트루스를
 * 서버로 옮긴 읽기 전용 엔드포인트. 상태 없는 enum 매핑뿐이라 별도 서비스 없이 여기서 조립한다.
 * 프론트는 마운트 시 한 번 받아 쓰고, 서버 미가용 시엔 자체 폴백 사본으로 화면을 지킨다.
 */
@Tag(name = "meta")
@RestController
@RequestMapping("/api/v1/meta")
@CrossOrigin(origins = "*")
public class MetaController {

    @Operation(summary = "회고 선택지 조회 (체감 난이도·이유, 코드+라벨)")
    @GetMapping("/reflection-options")
    public ApiResponse<ReflectionOptionsResponse> getReflectionOptions() {
        return ApiResponse.ok(ReflectionOptionsResponse.create());
    }

    @Operation(summary = "변경 이력 이벤트 종류 조회 (코드+라벨)")
    @GetMapping("/audit-event-types")
    public ApiResponse<List<MetaOptionResponse>> getAuditEventTypes() {
        return ApiResponse.ok(Arrays.stream(AuditEventType.values())
                .map(t -> new MetaOptionResponse(t.name(), t.getLabel()))
                .toList());
    }
}
