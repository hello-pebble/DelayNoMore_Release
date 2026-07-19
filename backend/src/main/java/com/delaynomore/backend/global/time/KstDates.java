package com.delaynomore.backend.global.time;

import java.time.LocalDate;
import java.time.ZoneId;

// 서비스의 "오늘" 판정 기준 시간대 — 한국(Asia/Seoul). 배포 컨테이너의 JVM 기본 시간대는
// UTC라 LocalDate.now()를 그대로 쓰면 KST 자정~오전 9시 사이에 날짜가 하루 어긋난다.
// AI 초안의 시작 날짜와 회고의 "오늘" 판정이 모두 이 기준 하나를 공유해야 한다.
public final class KstDates {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KstDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(KST);
    }
}
