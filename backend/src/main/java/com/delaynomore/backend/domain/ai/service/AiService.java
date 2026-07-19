package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.ai.dto.AiChatResponse;
import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.ai.dto.AiHealthResponse;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * "대화 → 투두리스트 생성" 비즈니스 로직. 프롬프트 조립(AiPromptBuilder)과 응답 해석(AiResponseParser)을
 * 오케스트레이션하고, 스트리밍 경로에서는 업스트림 델타를 프론트 SSE 이벤트로 변환해 흘려보낸다.
 * (SseEmitter는 웹 객체지만, SSE 릴레이 특성상 이벤트 변환 규칙이 곧 비즈니스 로직이라 여기서 다룬다.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final OpenRouterClient openRouterClient;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final OpenRouterProperties properties;
    private final ExecutorService sseExecutor;
    private final JsonMapper jsonMapper;

    // 자유 대화 응답 출력 상한 — reply(짧게) + patch(변경분만)라 넉넉하다. 추론이 꺼져 있어
    // 상한을 둬도 정상 응답이 잘리지 않고, 폭주 생성 비용만 방어한다.
    private static final int MAX_CHAT_TOKENS = 1200;
    // 초안은 계획 전체를 생성하므로 상한을 두지 않는다(길이가 곧 내용).
    private static final int NO_TOKEN_LIMIT = 0;
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;

    // 프론트 헤더의 AI 연결 상태 LED용 점검. 키는 서버에만 두고 OpenRouter로의 Bearer 호출을 대행한다.
    public AiHealthResponse getHealth() {
        if (!properties.isKeyConfigured()) {
            return AiHealthResponse.down("API Key 미설정");
        }
        OpenRouterClient.KeyCheck check = openRouterClient.checkKey();
        return check.connected() ? AiHealthResponse.up() : AiHealthResponse.down(check.failureReason());
    }

    // 계획 초안 생성(비스트리밍) — 날짜맵({날짜: [할 일]}) 형태의 계획을 돌려준다.
    // 정규화(normalizeDraftPlan)로 응답의 날짜 키를 서버가 보장한다 — LLM이 계약을 어긴 출력
    // (배열·"Day N" 키)을 줘도 프롬프트의 targetDates와 같은 기준(오늘부터)으로 날짜를 합성한다.
    public Object createDraft(AiDraftRequest request) {
        List<Map<String, Object>> messages = promptBuilder.draftMessages(request);
        String raw = openRouterClient.complete(messages, NO_TOKEN_LIMIT);
        return responseParser.normalizeDraftPlan(responseParser.parsePlan(raw), LocalDate.now());
    }

    /**
     * 초안 생성 스트리밍(SSE). 계획을 "하루 = 한 줄(NDJSON)"로 생성하게 하고, 한 줄(=하루)이
     * 완성될 때마다 day 이벤트로 흘려보내 프론트가 Day1부터 하나씩 그리게 한다.
     * 이벤트: {"type":"day","date":"YYYY-MM-DD","tasks":["..."]} / {"type":"done"} / {"type":"error","m":"..."}
     */
    public SseEmitter streamDraft(AiDraftRequest request) {
        List<Map<String, Object>> messages = promptBuilder.draftStreamMessages(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        sseExecutor.submit(() -> relayDraftStream(messages, emitter));
        return emitter;
    }

    // 자유 대화(비스트리밍) — LLM이 의도(수정/질문/불명확)를 판단해 reply(+선택적 patch)를 돌려준다.
    public AiChatResponse chat(AiChatRequest request) {
        List<Map<String, Object>> messages = promptBuilder.chatMessages(request);
        String raw = openRouterClient.complete(messages, MAX_CHAT_TOKENS);
        return responseParser.toChatResponse(raw);
    }

    /**
     * 자유 대화 스트리밍(SSE). 산문 reply는 토큰이 도착하는 대로 흘려보내고,
     * 계획 변경분(patch)은 스트림 끝에서 한 번에 파싱해 별도 이벤트로 보낸다.
     * 이벤트: {"type":"token","t":"..."} / {"type":"plan","patch":{...}} / {"type":"done"} / {"type":"error","m":"..."}
     */
    public SseEmitter streamChat(AiChatRequest request) {
        List<Map<String, Object>> messages = promptBuilder.chatMessages(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        sseExecutor.submit(() -> relayChatStream(messages, emitter));
        return emitter;
    }

    // ── 초안 스트리밍 릴레이 ─────────────────────────────────────────────

    // 업스트림 델타를 줄 버퍼에 모으고, 개행이 나올 때마다 완성된 줄(=하루)을 day 이벤트로 보낸다.
    private void relayDraftStream(List<Map<String, Object>> messages, SseEmitter emitter) {
        try {
            StringBuilder lineBuf = new StringBuilder();
            openRouterClient.streamCompletion(messages, NO_TOKEN_LIMIT, delta -> {
                lineBuf.append(responseParser.stripCjk(delta)); // 비한국어 CJK는 스트림 단계에서 제거
                emitCompleteDays(lineBuf, emitter);
            });
            // 스트림 종료: 마지막 줄이 개행 없이 끝났으면 남은 버퍼를 마저 파싱한다.
            emitDay(lineBuf.toString(), emitter);
            sseSend(emitter, Map.of("type", "done"));
            emitter.complete();
        } catch (Exception e) {
            log.error("Error streaming draft from OpenRouter", e);
            trySend(emitter, Map.of("type", "error", "m", "계획 생성 스트리밍 중 오류가 발생했습니다."));
            emitter.complete();
        }
    }

    // 버퍼에서 개행으로 끝난 완성된 줄들을 떼어내 각각 day 이벤트로 방출한다(꼬리는 버퍼에 남긴다).
    private void emitCompleteDays(StringBuilder buf, SseEmitter emitter) throws IOException {
        int nl;
        while ((nl = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, nl);
            buf.delete(0, nl + 1);
            emitDay(line, emitter);
        }
    }

    private void emitDay(String line, SseEmitter emitter) throws IOException {
        AiResponseParser.DayPlan day = responseParser.parseDayLine(line);
        if (day == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "day");
        event.put("date", day.date());
        event.put("tasks", day.tasks());
        sseSend(emitter, event);
    }

    // ── 자유 대화 스트리밍 릴레이 ────────────────────────────────────────

    // ===PLAN=== 구분자 전후로 산문(token 이벤트)과 patch JSON을 가르기 위한 스트림 상태.
    private static class ChatStreamBuffer {
        final StringBuilder replyPending = new StringBuilder(); // 구분자에 걸릴 수 있어 홀드 중인 산문 꼬리
        final StringBuilder jsonBuf = new StringBuilder();      // 구분자 이후 patch JSON
        boolean inJson = false;
    }

    private void relayChatStream(List<Map<String, Object>> messages, SseEmitter emitter) {
        ChatStreamBuffer buffer = new ChatStreamBuffer();
        try {
            openRouterClient.streamCompletion(messages, MAX_CHAT_TOKENS, delta -> {
                // 모델이 흘리는 한자/가나 등 비한국어 CJK 문자를 스트림 단계에서 제거한다
                // (산문 토큰·patch JSON 문자열 값 모두 커버, 구분자엔 CJK가 없어 안전).
                String cleaned = responseParser.stripCjk(delta);
                if (!cleaned.isEmpty()) {
                    feedDelta(cleaned, buffer, emitter);
                }
            });
            finishChatStream(buffer, emitter);
            emitter.complete();
        } catch (Exception e) {
            log.error("Error streaming from OpenRouter", e);
            // 토큰을 한 개도 못 보낸 경우 프론트가 폴백하도록 error 이벤트를 보낸다.
            trySend(emitter, Map.of("type", "error", "m", "AI 응답 스트리밍 중 오류가 발생했습니다."));
            emitter.complete();
        }
    }

    // 토큰 조각 하나를 상태머신에 먹인다. 구분자(===PLAN===)가 나오기 전까지는 산문으로 흘려보내되,
    // 조각 경계에서 구분자가 잘릴 수 있어 마지막 (구분자 길이-1)글자는 홀드했다가 다음 조각과 합쳐 판단한다.
    private void feedDelta(String piece, ChatStreamBuffer buf, SseEmitter emitter) throws IOException {
        if (buf.inJson) {
            buf.jsonBuf.append(piece);
            return;
        }
        buf.replyPending.append(piece);
        int idx = buf.replyPending.indexOf(AiResponseParser.PLAN_SENTINEL);
        if (idx >= 0) {
            String before = buf.replyPending.substring(0, idx);
            if (!before.isEmpty()) {
                sseSend(emitter, Map.of("type", "token", "t", before));
            }
            buf.jsonBuf.append(buf.replyPending.substring(idx + AiResponseParser.PLAN_SENTINEL.length()));
            buf.replyPending.setLength(0);
            buf.inJson = true;
            return;
        }
        int hold = AiResponseParser.PLAN_SENTINEL.length() - 1;
        int safe = buf.replyPending.length() - hold;
        if (safe > 0) {
            sseSend(emitter, Map.of("type", "token", "t", buf.replyPending.substring(0, safe)));
            buf.replyPending.delete(0, safe);
        }
    }

    // 스트림 종료: 남은 산문을 flush 하거나, 모은 patch를 파싱해 plan 이벤트로 보낸다.
    private void finishChatStream(ChatStreamBuffer buf, SseEmitter emitter) throws IOException {
        if (!buf.inJson) {
            if (buf.replyPending.length() > 0) {
                sseSend(emitter, Map.of("type", "token", "t", buf.replyPending.toString()));
            }
        } else {
            Map<String, Object> patch = responseParser.parsePatch(buf.jsonBuf.toString());
            if (patch != null && !patch.isEmpty()) {
                sseSend(emitter, Map.of("type", "plan", "patch", patch));
            }
        }
        sseSend(emitter, Map.of("type", "done"));
    }

    // ── SSE 공통 ────────────────────────────────────────────────────────

    // 이벤트 하나를 data: <compact JSON>\n\n 형태로 내보낸다.
    private void sseSend(SseEmitter emitter, Map<String, Object> event) throws IOException {
        emitter.send(jsonMapper.writeValueAsString(event));
    }

    // 실패 경로에서 예외를 삼키고 이벤트 전송을 시도한다(이미 닫혔으면 무시).
    private void trySend(SseEmitter emitter, Map<String, Object> event) {
        try {
            sseSend(emitter, event);
        } catch (Exception ignored) {
            // 연결이 이미 닫힌 경우 등 — 무시
        }
    }
}
