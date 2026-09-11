package com.delaynomore.backend.domain.knowledge.search;

import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository.ChunkWithSource;
import com.delaynomore.backend.domain.knowledge.search.DomainKnowledgeSearcher.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BigramLexicalSearcherTest {

    private final BigramLexicalSearcher searcher = new BigramLexicalSearcher();

    private static final List<ChunkWithSource> CHUNKS = List.of(
            new ChunkWithSource(1L, "DB 노트", 0, "제1정규형은 모든 속성이 원자값을 가진다. 정규화의 출발점이다."),
            new ChunkWithSource(1L, "DB 노트", 1, "SQL 조인에는 INNER JOIN과 OUTER JOIN이 있다."),
            new ChunkWithSource(2L, "영어 노트", 0, "관계대명사 which와 that의 차이를 정리했다."));

    @Test
    void 조사가_달라도_어간_bigram으로_매칭된다() {
        // 질의는 "정규화를"(목적격), 문서는 "정규형은"/"정규화의" — 어절 일치는 0이지만
        // 문자 bigram("정규", "규화")이 겹쳐 올바른 청크가 1위로 온다.
        List<SearchHit> hits = searcher.search("정규화를 설명해줘", CHUNKS, 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunk().seq()).isZero();
        assertThat(hits.getFirst().chunk().docTitle()).isEqualTo("DB 노트");
    }

    @Test
    void 무관한_청크는_하위이거나_제외된다() {
        List<SearchHit> hits = searcher.search("SQL 조인 종류", CHUNKS, 3);

        assertThat(hits.getFirst().chunk().content()).contains("JOIN");
        // 영어 노트 청크가 1위가 아니어야 한다.
        assertThat(hits.getFirst().chunk().docId()).isEqualTo(1L);
    }

    @Test
    void topK를_넘지_않는다() {
        assertThat(searcher.search("노트", CHUNKS, 2)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void 동점은_docId_seq_오름차순으로_고정된다() {
        List<ChunkWithSource> same = List.of(
                new ChunkWithSource(2L, "b", 0, "같은 내용"),
                new ChunkWithSource(1L, "a", 1, "같은 내용"),
                new ChunkWithSource(1L, "a", 0, "같은 내용"));

        List<SearchHit> hits = searcher.search("같은 내용", same, 3);

        assertThat(hits).extracting(h -> h.chunk().docId() + ":" + h.chunk().seq())
                .containsExactly("1:0", "1:1", "2:0");
    }

    @Test
    void 빈_질의나_한_글자는_빈_결과다() {
        assertThat(searcher.search("", CHUNKS, 3)).isEmpty();
        assertThat(searcher.search("가", CHUNKS, 3)).isEmpty();
    }

    @Test
    void 점수는_완전_일치가_부분_일치보다_높다() {
        var full = BigramLexicalSearcher.dice(
                BigramLexicalSearcher.bigrams("정규화"), BigramLexicalSearcher.bigrams("정규화"));
        var partial = BigramLexicalSearcher.dice(
                BigramLexicalSearcher.bigrams("정규화"), BigramLexicalSearcher.bigrams("정규화와 조인"));
        assertThat(full).isEqualTo(1.0).isGreaterThan(partial);
    }
}
