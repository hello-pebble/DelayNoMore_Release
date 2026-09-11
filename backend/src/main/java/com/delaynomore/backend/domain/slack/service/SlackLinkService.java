package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.slack.repository.SlackRepository;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * 슬랙 계정 연결 — 웹(로그인 상태)에서 코드를 발급하고, 사용자가 봇 DM으로 코드를 입력하면
 * owner ↔ 슬랙 사용자 매핑이 만들어진다. owner를 슬랙 페이로드에서 받지 않는 이유:
 * 소유자 해석은 항상 서버가 한다(@Owner 관례) — 슬랙 쪽 입력은 "코드"라는 1회용 비밀만이고,
 * 그 코드가 어느 owner의 것인지는 서버 저장소가 답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackLinkService {

    public static final int CODE_TTL_MINUTES = 10;
    static final int CODE_LENGTH = 8;
    // 0/O, 1/I 처럼 눈으로 헷갈리는 글자를 뺀 32자 알파벳 — 사용자가 손으로 옮겨 적는 값이다.
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SlackRepository slackRepository;

    /** 연결 코드 발급 — 재발급하면 이전 코드는 무효. 만료 코드는 이때 lazy 청소한다. */
    public String issueCode(String owner) {
        slackRepository.deleteExpiredLinkCodes();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        slackRepository.saveLinkCode(code.toString(), owner, CODE_TTL_MINUTES);
        return code.toString();
    }

    /**
     * DM으로 온 코드를 소비해 연결을 만든다. 성공하면 연결된 링크를 반환한다.
     * 기존 연결(같은 owner의 재연결)은 활동시간을 보존하고 슬랙 계정·채널만 갱신한다.
     */
    @Transactional
    public Optional<SlackLink> linkByCode(String code, String teamId, String slackUserId, String channelId) {
        return slackRepository.consumeLinkCode(code).map(owner -> {
            SlackLink existing = slackRepository.findLinkByOwner(owner).orElse(null);
            SlackLink link = new SlackLink(owner, teamId, slackUserId, channelId,
                    existing != null ? existing.activeStartMin() : 540,
                    existing != null ? existing.activeEndMin() : 1260);
            slackRepository.upsertLink(link);
            log.info("slack linked owner={} team={} user={}", owner, teamId, slackUserId);
            return link;
        });
    }

    public Optional<SlackLink> findByOwner(String owner) {
        return slackRepository.findLinkByOwner(owner);
    }

    public Optional<SlackLink> findBySlackUser(String teamId, String slackUserId) {
        return slackRepository.findLinkBySlackUser(teamId, slackUserId);
    }

    public void unlink(String owner) {
        slackRepository.deleteLinkByOwner(owner);
    }
}
