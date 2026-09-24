package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PointLedgerEntry;
import com.delaynomore.backend.domain.points.entity.PointTransfer;

import java.util.List;

/**
 * 포인트 원장 저장소 계약(v0.31.0) — 구현은 프로필로 선택된다(다른 저장소와 같은 관례):
 * 기본(!postgres) = 인메모리, postgres = JDBC.
 *
 * <p><b>왜 지갑과 따로인가.</b> 지갑 잔액은 챌린지 참가의 원자 구간 안에서 조건부로 차감돼야 해서
 * {@code ChallengeRepository}가 소유한다. 반면 원장은 챌린지 말고도 여러 도메인이 쓴다(가입 지급,
 * 게스트 이관, 앞으로의 적립 규칙). 그래서 원장을 별도 저장소로 두되, <b>기표는 잔액을 바꾸는
 * 그 원자 구간 안에서</b> 일어나게 했다 — 두 저장소를 쓰지만 트랜잭션(JDBC)·키 단위 원자 구간
 * (인메모리)은 하나다. 잔액만 바뀌고 원장이 비는 상태는 만들어지지 않는다.
 *
 * <p><b>잔액과의 관계.</b> 원장이 진실이고 잔액은 파생 캐시다. 기존 조건부 UPDATE
 * ({@code WHERE balance >= :fee})를 그대로 쓰기 위해 캐시를 유지하되, 둘이 어긋나지 않는다는
 * 것은 불변식으로 증명한다: <b>계정별 원장 합계 = 지갑 잔액</b>, <b>전체 원장 합계 = 0</b>.
 */
public interface PointLedgerRepository {

    /**
     * 기표 — 출발 계정에 음수, 도착 계정에 양수 두 줄을 <b>함께</b> 남긴다.
     *
     * @return 실제로 기록했으면 true. 같은 {@code txKey}가 이미 있으면 아무것도 쓰지 않고 false
     *         (재시도·중복 호출을 예외 없이 흡수한다 — 판정 주체는 UNIQUE (tx_key, account)).
     */
    boolean post(PointTransfer transfer);

    /** 한 계정의 거래 내역, 최신순. 거래 내역 화면과 검증이 읽는다. */
    List<PointLedgerEntry> findByAccount(String account, int limit);

    /** 한 계정의 원장 합계 — 지갑 잔액과 같아야 한다(불변식 1). */
    int sumOf(String account);

    /** 전체 합계 — 언제나 0이어야 한다(불변식 2: 포인트는 생기지도 사라지지도 않는다). */
    int totalSum();

    /** 소유자 키를 갈아끼운다(게스트 흡수) — 이관 기표와 함께 과거 내역도 계정을 따라간다. */
    void reassignAccount(String fromAccount, String toAccount);
}
