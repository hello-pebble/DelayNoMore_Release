package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.domain.slack.client.SlackApiClient;
import com.delaynomore.backend.domain.slack.repository.SlackRepository;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import com.delaynomore.backend.global.config.SlackProperties;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 일일 발송 루프 — 이 저장소 최초의 스케줄러 사용처(근거는 SchedulingConfig 주석).
 *
 * <p>매분 전체 연결을 훑어 "활동 시작 시각이 지났고 오늘 아직 안 보낸" 사용자에게 체크리스트를
 * 보낸다. "이미 보냈는가"의 판정은 이 클래스의 if가 아니라 slack_daily_sends 클레임
 * (조건부 INSERT/UPDATE)이다 — 루프가 겹치거나 재기동해도 하루 한 번이 구조로 보장된다.
 * 전송 실패는 sent_at을 비워 두면 다음 턴의 재클레임(5분 간격, 최대 3회)이 이어받는다.
 */
// ponytail: 전체 스캔 — 데모 규모(수십 연결)라 시각 인덱스를 두지 않는다(settleDue와 같은 선택).
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackDispatchService {

    static final String KIND_CHECKLIST = "CHECKLIST";
    static final String KIND_REFLECTION_PROMPT = "REFLECTION_PROMPT";
    private static final int RETRY_AFTER_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 3;

    private final SlackProperties properties;
    private final SlackRepository slackRepository;
    private final TodayDashboardService todayDashboardService;
    private final SlackMessageComposer composer;
    private final SlackReflectionFlowService reflectionFlow;
    private final SlackApiClient apiClient;

    @Scheduled(fixedDelay = 60_000)
    public void dispatch() {
        if (!properties.isEnabled()) {
            return;
        }
        slackRepository.deleteOldEvents(); // dedup 행 lazy 청소를 발송 루프에 얹는다(비용 미미)
        ZonedDateTime now = ZonedDateTime.now(KstDates.KST);
        LocalDate today = now.toLocalDate();
        int nowMin = now.getHour() * 60 + now.getMinute();

        for (SlackLink link : slackRepository.findAllLinks()) {
            if (nowMin >= link.activeStartMin()
                    && claim(link.owner(), today, KIND_CHECKLIST)) {
                sendChecklist(link, today);
            }
            // 활동 종료 시각 — 회고 문답 시작(v0.28.0). 같은 클레임 테이블의 kind 하나가 늘었을 뿐
            // 멱등 계약은 체크리스트와 동일하다(CONCURRENCY.md 9절).
            if (nowMin >= link.activeEndMin()
                    && claim(link.owner(), today, KIND_REFLECTION_PROMPT)) {
                sendReflectionPrompt(link, today);
            }
        }
    }

    private boolean claim(String owner, LocalDate today, String kind) {
        return slackRepository.claimDailySend(owner, today, kind)
                || slackRepository.reclaimUnsent(owner, today, kind, RETRY_AFTER_MINUTES, MAX_ATTEMPTS);
    }

    private void sendReflectionPrompt(SlackLink link, LocalDate today) {
        try {
            var prompt = reflectionFlow.startFlow(link.owner(), today);
            if (prompt.isEmpty()) {
                // 회고할 계획이 없다(오늘 작업 없음 또는 이미 웹에서 회고 완료) — 클레임만 닫는다.
                slackRepository.markSent(link.owner(), today, KIND_REFLECTION_PROMPT);
                return;
            }
            String channel = channelOf(link);
            if (channel != null && apiClient.postMessage(channel, prompt.get())) {
                slackRepository.markSent(link.owner(), today, KIND_REFLECTION_PROMPT);
            }
        } catch (Exception e) {
            log.warn("slack reflection prompt failed owner={}", link.owner(), e);
        }
    }

    private void sendChecklist(SlackLink link, LocalDate today) {
        try {
            TodayDashboardResponse dashboard = todayDashboardService.get(link.owner());
            if (dashboard.total() == 0) {
                // 오늘 작업이 없으면 보낼 것이 없다 — 클레임은 sent 처리해 재시도로 새지 않게 한다.
                slackRepository.markSent(link.owner(), today, KIND_CHECKLIST);
                return;
            }
            String channel = channelOf(link);
            if (channel == null) {
                return; // sent_at을 비워 둔 채 반환 → 재클레임이 이어받는다
            }
            if (apiClient.postMessage(channel, composer.composeChecklist(dashboard))) {
                slackRepository.markSent(link.owner(), today, KIND_CHECKLIST);
            }
        } catch (Exception e) {
            // 한 사용자 실패가 루프 전체(다른 사용자 발송)를 끊지 않게 한다. sent_at이 비어 있어
            // 재클레임 재시도가 상한(3회)까지 이어받는다.
            log.warn("slack dispatch failed owner={}", link.owner(), e);
        }
    }

    private String channelOf(SlackLink link) {
        String channel = link.channelId() != null ? link.channelId()
                : apiClient.openDm(link.slackUserId()).orElse(null);
        if (channel == null) {
            log.warn("slack dispatch: no DM channel owner={} — 재시도 대기", link.owner());
        }
        return channel;
    }
}
