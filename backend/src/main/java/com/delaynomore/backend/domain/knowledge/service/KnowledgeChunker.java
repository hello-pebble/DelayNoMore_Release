package com.delaynomore.backend.domain.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서 → 검색용 청크 분할(순수 함수). 문단(\n) 경계를 우선 존중하고, 한 문단이 청크 크기를
 * 넘으면 오버랩을 두고 강제 분할한다 — 오버랩은 경계에 걸친 문장이 두 청크 어느 쪽에서도
 * 온전히 검색되게 하는 장치다.
 */
public final class KnowledgeChunker {

    public static final int CHUNK_CHARS = 500;
    public static final int OVERLAP_CHARS = 100;

    private KnowledgeChunker() {
    }

    public static List<String> split(String content) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > CHUNK_CHARS) {
                // 긴 문단 — 지금까지 모은 것을 먼저 닫고, 문단을 오버랩 슬라이딩으로 강제 분할.
                flush(chunks, current);
                for (int start = 0; start < p.length(); start += CHUNK_CHARS - OVERLAP_CHARS) {
                    chunks.add(p.substring(start, Math.min(p.length(), start + CHUNK_CHARS)));
                    if (start + CHUNK_CHARS >= p.length()) {
                        break;
                    }
                }
                continue;
            }
            if (current.length() + p.length() + 1 > CHUNK_CHARS) {
                flush(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(p);
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
