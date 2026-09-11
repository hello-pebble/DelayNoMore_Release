package com.delaynomore.backend.domain.knowledge.entity;

/** 계획별 참고 자료 한 건 — 소유는 plan 경유(자체 owner 없음, reflections 선례). */
public record KnowledgeDoc(long id, long planId, String title, String content, String createdAt) {
}
