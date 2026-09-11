package com.delaynomore.backend.domain.knowledge.dto;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;
import com.delaynomore.backend.domain.knowledge.service.KnowledgeChunker;
import jakarta.validation.constraints.NotBlank;

public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record KnowledgeSaveRequest(
            @NotBlank(message = "자료 제목을 입력해주세요.") String title,
            @NotBlank(message = "자료 내용을 입력해주세요.") String content) {
    }

    /** 목록·저장 응답 — 원문(content)은 싣지 않는다(페이로드 절약, 최소 UI는 목록+삭제만). */
    public record KnowledgeDocResponse(long id, String title, int chars, int chunkCount, String createdAt) {

        public static KnowledgeDocResponse from(KnowledgeDoc doc) {
            return new KnowledgeDocResponse(doc.id(), doc.title(), doc.content().length(),
                    KnowledgeChunker.split(doc.content()).size(), doc.createdAt());
        }
    }
}
