package com.delaynomore.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 이 저장소 최초의 스케줄러 활성화(v0.26.0). 지금까지 주기 작업은 전부 lazy 트리거로 해결했지만
// (세션 청소 = 로그인 시 — docs/CONCURRENCY.md 7·8절), 슬랙 발송은 "사용자의 요청이 없는 시각에
// 서버가 먼저 보내는" 작업이라 요청 유발 트리거가 존재하지 않는다. v0.34.0부터 챌린지 만기 정산도
// 같은 이유로 주기 실행을 더했다 — 접속자가 없어도 만기가 지나면 정산돼야 한다(lazy 호출은 즉시 반영용으로 유지).
// 관례의 본질은 "@Scheduled 금지"가 아니라 "최대 1회 실행의 판정자는 애플리케이션 if가 아니라
// DB"다 — 발송 멱등은 slack_daily_sends PK의 조건부 INSERT/UPDATE가 지키므로(V9 주석),
// 스케줄러가 겹쳐 돌거나 재기동해도 중복 발송은 구조적으로 없다. 단일 컨테이너 배포라
// 인스턴스도 항상 1개다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
