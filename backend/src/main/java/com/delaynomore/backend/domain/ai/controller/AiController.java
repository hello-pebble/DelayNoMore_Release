package com.delaynomore.backend.domain.ai.controller;

import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.ai.dto.AiChatResponse;
import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.ai.dto.AiHealthResponse;
import com.delaynomore.backend.domain.ai.service.AiService;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
