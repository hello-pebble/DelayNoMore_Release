package com.delaynomore.backend.domain.ai.dto;

import com.delaynomore.backend.domain.ai.agent.AgentProfile;

import java.util.List;

/**
 * 도구 카탈로그 응답(v0.17.0) — 도구 목록에 <b>프로필</b>이 함께 실린다. "이 상태에서 무엇을 할 수
 * 있는가"(tools)와 "누가 응대하는가"(profile)는 같은 상태에서 파생되는 한 쌍이므로 한 응답으로
 * 내려, 화면·curl 어느 쪽에서 봐도 어긋난 조합이 보일 수 없게 한다.
 *
 * <p>기존의 맨 배열 응답을 감싸는 형태 변경이지만, 이 API의 프론트 소비처는 아직 없어
 * (fetchAgentTools는 배선 전) 실질적인 호환성 부담 없이 바꿨다 — CHANGELOG에 근거를 남겼다.
 */
public record AgentCatalogResponse(ProfileInfo profile, List<AgentToolResponse> tools) {

    public record ProfileInfo(String name, String label) {

        public static ProfileInfo from(AgentProfile profile, String goalName) {
            return new ProfileInfo(profile.name(), profile.displayLabel(goalName));
        }
    }
}
