package com.delaynomore.backend.domain.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkerTest {

    @Test
    void 짧은_문서는_청크_하나다() {
        assertThat(KnowledgeChunker.split("정규화 요약 노트")).containsExactly("정규화 요약 노트");
    }

    @Test
    void 문단_경계를_존중해_묶는다() {
        String content = "첫 문단.\n\n둘째 문단.\n셋째 문단.";
        assertThat(KnowledgeChunker.split(content))
                .containsExactly("첫 문단.\n둘째 문단.\n셋째 문단."); // 500자 안이면 한 청크로 합침
    }

    @Test
    void 청크_크기를_넘으면_문단_단위로_나뉜다() {
        String p1 = "가".repeat(300);
        String p2 = "나".repeat(300);
        List<String> chunks = KnowledgeChunker.split(p1 + "\n" + p2);
        assertThat(chunks).containsExactly(p1, p2); // 합치면 600자 > 500 — 문단 경계에서 분할
    }

    @Test
    void 긴_문단은_오버랩을_두고_강제_분할된다() {
        String longParagraph = "다".repeat(1100);
        List<String> chunks = KnowledgeChunker.split(longParagraph);

        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(500));
        // 오버랩 검증: 앞 청크의 꼬리 100자가 다음 청크의 머리에 다시 나온다.
        for (int i = 0; i + 1 < chunks.size(); i++) {
            String tail = chunks.get(i).substring(chunks.get(i).length() - KnowledgeChunker.OVERLAP_CHARS);
            assertThat(chunks.get(i + 1)).startsWith(tail);
        }
        // 원문이 손실 없이 커버된다(오버랩 슬라이딩 보폭 400자).
        assertThat(chunks.size()).isEqualTo(3); // 0-500, 400-900, 800-1100
    }

    @Test
    void 빈_줄만_있으면_빈_목록이다() {
        assertThat(KnowledgeChunker.split("\n\n  \n")).isEmpty();
    }
}
