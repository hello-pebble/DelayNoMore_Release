package com.delaynomore.backend.domain.ai.controller;

import com.delaynomore.backend.domain.ai.dto.AgentCatalogResponse;
import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.ai.dto.AiChatResponse;
import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.ai.dto.AiHealthResponse;
import com.delaynomore.backend.domain.ai.dto.PlanDraftSessionMessageRequest;
import com.delaynomore.backend.domain.ai.dto.PlanDraftSessionResponse;
import com.delaynomore.backend.domain.ai.service.AgentRunner;
import com.delaynomore.backend.domain.ai.service.AgentToolCatalogService;
import com.delaynomore.backend.domain.ai.service.AiService;
import com.delaynomore.backend.domain.ai.service.PlanDraftSessionService;
import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


/**
 * "대화 → 투두리스트 생성" 데모용 AI 프록시.
 * OpenRouter API 키는 서버에만 두고, 프론트가 요청한 계획 초안 생성·자유 대화를 대행한다.
 */
@Tag(name = "ai")
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AiController {

    private final AiService aiService;
    private final AgentRunner agentRunner;
    private final AgentToolCatalogService agentToolCatalogService;
    private final PlanDraftSessionService planDraftSessionService;

    @Operation(summary = "서버 소유 계획 작성 대화 시작")
    @PostMapping("/plan-draft-sessions")
    public ApiResponse<PlanDraftSessionResponse> createPlanDraftSession(
            @Owner String owner) {
        return ApiResponse.ok(planDraftSessionService.create(owner));
    }

    @Operation(summary = "서버 소유 계획 작성 대화에 메시지 전송")
    @PostMapping("/plan-draft-sessions/{sessionId}/messages")
    public ApiResponse<PlanDraftSessionResponse> sendPlanDraftSessionMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody PlanDraftSessionMessageRequest request,
            @Owner String owner,
            @RequestHeader(value = "X-Session-Id", required = false) String requestSessionId) {
        return ApiResponse.ok(planDraftSessionService.accept(sessionId, owner,
                request.message(), requestSessionId));
    }

    @Operation(summary = "AI 연결 상태 점검")
    @GetMapping("/health")
    public ApiResponse<AiHealthResponse> getHealth() {
        return ApiResponse.ok(aiService.getHealth());
    }

    @Operation(summary = "계획 초안 생성")
    @PostMapping("/drafts")
    public ApiResponse<Object> createDraft(@Valid @RequestBody AiDraftRequest request) {
        log.info("Received request for draft");
        return ApiResponse.ok(aiService.createDraft(request));
    }

    @Operation(summary = "계획 초안 생성 (SSE 스트리밍)")
    @PostMapping(value = "/drafts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDraft(@Valid @RequestBody AiDraftRequest request) {
        log.info("Received request for draft (stream)");
        return aiService.streamDraft(request);
    }

    @Operation(summary = "계획 코치 자유 대화")
    @PostMapping("/chats")
    public ApiResponse<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
        log.info("Received request for chat");
        return ApiResponse.ok(aiService.chat(request));
    }

    @Operation(summary = "계획 코치 자유 대화 (SSE 스트리밍)")
    @PostMapping(value = "/chats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AiChatRequest request) {
        log.info("Received request for chat (stream)");
        return aiService.streamChat(request);
    }

    // ── 에이전트(도구 호출) 경로 ────────────────────────────────────────
    // 위 /chats·/chats/stream은 그대로 남는다 — 하위 호환이자, 도구 미지원 모델에서의 폴백
    // 경로이자, 두 방식을 나란히 비교할 수 있는 대조군이다.
    //
    // 계획 API와 달리 AI 프록시는 지금까지 X-Guest-Id를 쓰지 않았지만(소유 데이터를 만지지
    // 않았으므로), 에이전트는 도구로 남의 계획을 읽거나 바꿀 수 있으므로 소유자 스코프가
    // 필수다. 해석 규칙은 계획 API와 같은 OwnerGuestId를 그대로 쓴다.

    @Operation(summary = "에이전트 카탈로그 (현재 계획 상태의 프로필 + 노출되는 도구)")
    @GetMapping("/agent/tools")
    public ApiResponse<AgentCatalogResponse> getAgentTools(
            @RequestParam(required = false) Long planId,
            @Owner String owner) {
        return ApiResponse.ok(agentToolCatalogService.list(planId, owner));
    }

    @Operation(summary = "계획 코치 에이전트 대화 (SSE 스트리밍, 도구 호출)")
    @PostMapping(value = "/agent/chats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgentChat(@Valid @RequestBody AiChatRequest request,
                                      @Owner String owner,
                                      @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request for agent chat (stream)");
        return agentRunner.stream(request, owner, sessionId);
    }
}
