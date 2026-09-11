package com.delaynomore.backend.domain.knowledge.search;

import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository.ChunkWithSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 문자 bigram Dice 유사도 렉시컬 검색기. 임베딩 없이 한국어에서 동작하는 근거:
 * 한국어는 교착어라 같은 단어도 조사·어미가 붙어 어절이 달라진다("정규화를"/"정규화가"/"정규화란")
 * — 어절 단위 매칭은 이 변형에 전부 실패하지만, 문자 2-gram의 중첩은 어간("정규", "규화")을
 * 그대로 잡는다. 형태소 분석기 의존성 없이 이 성질만으로 자료 검색 수준의 순위를 낸다.
 *
 * <p>정규화: 공백 제거 + 소문자화 후 bigram 멀티셋의 Dice 계수(2·교집합 / 크기 합).
 * 동점은 (docId, seq) 오름차순 고정 — 인메모리/JDBC 어느 프로필에서도 같은 순위를 내
 * 평가·QA가 재현된다.
 */
@Component
public class BigramLexicalSearcher implements DomainKnowledgeSearcher {

    @Override
    public List<SearchHit> search(String query, List<ChunkWithSource> chunks, int topK) {
        Map<String, Integer> queryGrams = bigrams(query);
        if (queryGrams.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .map(chunk -> new SearchHit(chunk, dice(queryGrams, bigrams(chunk.content()))))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed()
                        .thenComparingLong(hit -> hit.chunk().docId())
                        .thenComparingInt(hit -> hit.chunk().seq()))
                .limit(topK)
                .toList();
    }

    // 공백·개행을 지우고 소문자화한 문자열의 2-gram 멀티셋. 글자 1개 이하면 빈 맵.
    static Map<String, Integer> bigrams(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.merge(normalized.substring(i, i + 2), 1, Integer::sum);
        }
        return grams;
    }

    static double dice(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        int sizeA = 0;
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            sizeA += entry.getValue();
            overlap += Math.min(entry.getValue(), b.getOrDefault(entry.getKey(), 0));
        }
        int sizeB = b.values().stream().mapToInt(Integer::intValue).sum();
        return (2.0 * overlap) / (sizeA + sizeB);
    }
}
