package com.delaynomore.backend.domain.metrics;

import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 앱 오픈 기록(v0.37.0) — 지표 A("앱 오픈 → 그 방문에서 첫 완료 체크 도달률")의 분모.
 * 프론트가 ChatCoach 마운트마다 본문 없이 1회 호출한다. GET /plans에 부작용을 두지 않고 별도
 * POST로 둔 이유: 목록 재조회(새로고침·생성 후)와 "오픈"을 서버가 구분할 수 없기 때문이다.
 * 방문 ID(X-Visit-Id)는 페이지 로드마다 새로 만들어지며 PK라 중복 호출은 무시된다.
 */
@Tag(name = "metrics")
@RestController
@RequestMapping("/api/v1/app-opens")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AppOpenController {

    private static final int MAX_VISIT_ID_LENGTH = 64;

    private final AppOpenRepository appOpenRepository;

    @Operation(summary = "앱 오픈 기록 — 방문당 1회(멱등), 지표 집계용")
    @PostMapping
    public ApiResponse<Void> record(@RequestHeader(value = "X-Visit-Id", required = false) String visitId,
                                    @Owner String owner) {
        String trimmed = visitId == null ? "" : visitId.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VISIT_ID_REQUIRED);
        }
        if (trimmed.length() > MAX_VISIT_ID_LENGTH) {
            trimmed = trimmed.substring(0, MAX_VISIT_ID_LENGTH); // AuditEventService의 세션 ID 절단 관례와 동일
        }
        appOpenRepository.record(trimmed, owner, Instant.now().toString());
        return ApiResponse.ok(null);
    }
}
