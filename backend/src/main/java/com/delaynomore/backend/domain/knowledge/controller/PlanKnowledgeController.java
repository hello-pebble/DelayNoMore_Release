package com.delaynomore.backend.domain.knowledge.controller;

import com.delaynomore.backend.domain.knowledge.dto.KnowledgeDtos.KnowledgeDocResponse;
import com.delaynomore.backend.domain.knowledge.dto.KnowledgeDtos.KnowledgeSaveRequest;
import com.delaynomore.backend.domain.knowledge.service.PlanKnowledgeService;
import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 계획별 참고 자료 API(v0.29.0). 소유 격리는 다른 계획 API와 동일 — @Owner가 해석한 소유자로
 * plan 경유 판정하고 불일치는 404. 검색은 API로 노출하지 않는다 — 검색의 소비자는 에이전트
 * 도구(search_domain_knowledge)뿐이다.
 */
@Tag(name = "knowledge")
@RestController
@RequestMapping("/api/v1/plans/{planId}/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlanKnowledgeController {

    private final PlanKnowledgeService knowledgeService;

    @Operation(summary = "참고 자료 추가 — 텍스트 붙여넣기, 청크 분할은 서버가 수행")
    @PostMapping
    public ApiResponse<KnowledgeDocResponse> add(@PathVariable long planId,
                                                 @Valid @RequestBody KnowledgeSaveRequest request,
                                                 @Owner String owner) {
        return ApiResponse.ok(KnowledgeDocResponse.from(
                knowledgeService.add(planId, owner, request.title(), request.content())));
    }

    @Operation(summary = "참고 자료 목록 — 요약만(원문 미포함), 최신순")
    @GetMapping
    public ApiResponse<List<KnowledgeDocResponse>> list(@PathVariable long planId, @Owner String owner) {
        return ApiResponse.ok(knowledgeService.list(planId, owner).stream()
                .map(KnowledgeDocResponse::from)
                .toList());
    }

    @Operation(summary = "참고 자료 삭제")
    @DeleteMapping("/{docId}")
    public ApiResponse<Void> delete(@PathVariable long planId, @PathVariable long docId,
                                    @Owner String owner) {
        knowledgeService.delete(planId, docId, owner);
        return ApiResponse.ok(null);
    }
}
