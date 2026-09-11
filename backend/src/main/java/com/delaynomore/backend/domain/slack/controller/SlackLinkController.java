package com.delaynomore.backend.domain.slack.controller;

import com.delaynomore.backend.domain.slack.dto.SlackDtos.LinkCodeResponse;
import com.delaynomore.backend.domain.slack.dto.SlackDtos.LinkStatusResponse;
import com.delaynomore.backend.domain.slack.service.SlackLinkService;
import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.config.SlackProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 슬랙 연결 API — 코드 발급·상태 조회·해제. 전부 로그인(회원) 전용이다: @Owner는 Bearer가
 * 없으면 게스트로 폴백하므로, 컨트롤러가 Authorization 헤더 존재를 먼저 확인해 게스트를
 * 막는다(SLACK_LOGIN_REQUIRED). 게스트를 막는 이유는 ErrorCode 주석 참고.
 */
@Tag(name = "slack")
@RestController
@RequestMapping("/api/v1/slack")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SlackLinkController {

    private final SlackLinkService linkService;
    private final SlackProperties properties;

    @Operation(summary = "슬랙 연결 코드 발급 — 봇 DM에 입력할 1회용 코드(10분 유효)")
    @PostMapping("/link-code")
    public ApiResponse<LinkCodeResponse> issueCode(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Owner String owner) {
        requireEnabled();
        requireMember(authorization);
        return ApiResponse.ok(LinkCodeResponse.of(linkService.issueCode(owner)));
    }

    @Operation(summary = "슬랙 연결 상태 — 연결 여부와 활동시간")
    @GetMapping("/link")
    public ApiResponse<LinkStatusResponse> status(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Owner String owner) {
        requireMember(authorization);
        return ApiResponse.ok(linkService.findByOwner(owner)
                .map(LinkStatusResponse::from)
                .orElse(LinkStatusResponse.notLinked()));
    }

    @Operation(summary = "슬랙 연결 해제")
    @DeleteMapping("/link")
    public ApiResponse<Void> unlink(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Owner String owner) {
        requireMember(authorization);
        linkService.unlink(owner);
        return ApiResponse.ok(null);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SLACK_DISABLED);
        }
    }

    private static void requireMember(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new BusinessException(ErrorCode.SLACK_LOGIN_REQUIRED);
        }
    }
}
