package com.delaynomore.backend.domain.slack.dto;

import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import com.delaynomore.backend.domain.slack.service.SlackLinkService;

public final class SlackDtos {

    private SlackDtos() {
    }

    /** 연결 코드 발급 응답 — 사용자가 봇 DM에 붙여넣을 값과 유효 시간. */
    public record LinkCodeResponse(String code, int expiresInMinutes) {
        public static LinkCodeResponse of(String code) {
            return new LinkCodeResponse(code, SlackLinkService.CODE_TTL_MINUTES);
        }
    }

    /**
     * 연결 상태 응답. 미연결이면 linked=false에 나머지 null — 프론트 카드가 분기만 하면 되게
     * 404 대신 상태 플래그로 내린다. 활동시간은 "HH:mm" 문자열(분 단위 원값은 서버 전용).
     */
    public record LinkStatusResponse(boolean linked, String slackUserId,
                                     String activeStart, String activeEnd) {

        public static LinkStatusResponse notLinked() {
            return new LinkStatusResponse(false, null, null, null);
        }

        public static LinkStatusResponse from(SlackLink link) {
            return new LinkStatusResponse(true, link.slackUserId(),
                    formatMin(link.activeStartMin()), formatMin(link.activeEndMin()));
        }

        private static String formatMin(int minutesOfDay) {
            return "%02d:%02d".formatted(minutesOfDay / 60, minutesOfDay % 60);
        }
    }
}
