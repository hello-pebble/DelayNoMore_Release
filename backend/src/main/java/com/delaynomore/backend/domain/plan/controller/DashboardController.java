package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.domain.plan.support.OwnerGuestId;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DashboardController {

    private final TodayDashboardService todayDashboardService;

    @Operation(summary = "오늘 화면에 필요한 계획, 작업, 회고를 한 번에 조회")
    @GetMapping("/today")
    public ApiResponse<TodayDashboardResponse> getToday(
            @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(todayDashboardService.get(OwnerGuestId.resolve(rawGuestId)));
    }
}
