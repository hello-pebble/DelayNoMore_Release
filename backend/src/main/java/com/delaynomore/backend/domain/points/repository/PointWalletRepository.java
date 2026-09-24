package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PointTransfer;

/**
 * 포인트 지갑 저장소 계약(v0.32.0) — 잔액(캐시)의 소유자.
 *
 * <p><b>왜 v0.32.0에 분리했나.</b> v0.31.0까지 지갑은 {@code ChallengeRepository} 안에 있었다.
 * 참가 1회가 "중복 검사 + 참가비 차감 + 자리 예약"을 한 원자 구간에서 해야 했기 때문인데,
 * 이제 챌린지가 아닌 곳(목표 예치)에서도 잔액을 움직여야 한다. 지갑을 챌린지 저장소에 남겨 두면
 * 계획 도메인이 챌린지 저장소를 물어야 하고, 그건 의존 방향이 틀렸다.
 *
 * <p><b>분리해도 원자성은 그대로다.</b> JDBC는 호출자의 트랜잭션이 두 저장소를 함께 덮고,
 * 인메모리는 챌린지 맵의 키 단위 원자 구간 <b>안에서</b> 이 저장소를 호출한다(원장이 v0.31.0부터
 * 같은 방식으로 동작해 온 그대로다 — 다른 맵을 만지는 것은 재진입 문제가 없다).
 *
 * <p><b>기표가 여기로 들어왔다.</b> 잔액을 바꾸는 모든 메서드가 {@link PointTransfer}를 함께
 * 받는다 — 원장 기표 없이 잔액만 바꾸는 길을 아예 만들지 않기 위해서다. v0.31.0에서는 "같은
 * 원자 구간 안에서 기표한다"가 규율이었다면, 이제는 <b>같은 메서드 시그니처</b>가 그것을 강제한다.
 */
public interface PointWalletRepository {

    /**
     * 잔액. 지갑이 없으면 초기 잔액으로 만들고 <b>그때만</b> 발행을 기표한다(목록 조회마다 불리는
     * 경로라, 만든 호출과 이미 있던 호출을 구분하지 못하면 조회 횟수만큼 발행된다).
     */
    int balanceOf(String owner);

    /**
     * 차감 — 잔액 검사가 쓰기와 같은 구간에 있다. 잔액이 모자라면 {@code POINTS_INSUFFICIENT}를
     * 던지고 저장소는 변하지 않는다.
     *
     * <p><b>같은 {@code txKey}로 다시 부르면 아무 일도 일어나지 않는다.</b> 원장이 중복 기표를
     * 흡수하는 것만으로는 부족하다 — 그때 잔액만 또 줄면 불변식 2(계정별 원장 합계 = 잔액)가
     * 깨진다. 그래서 <b>기표가 실제로 일어난 경우에만</b> 잔액을 바꾼다. 멱등의 단위는 원장 줄이
     * 아니라 "잔액 + 기표" 한 쌍이다.
     *
     * @param transfer 소유자 → 도착 계정. 금액은 {@code amount}와 같아야 한다(호출부 책임).
     */
    void debit(String owner, int amount, PointTransfer transfer);

    /** 증가 — 상한이 없어 실패하지 않는다. 멱등 규칙은 {@link #debit}과 같다. */
    void credit(String owner, int amount, PointTransfer transfer);
}
