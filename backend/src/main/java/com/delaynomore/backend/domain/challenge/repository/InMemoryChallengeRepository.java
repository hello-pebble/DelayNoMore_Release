package com.delaynomore.backend.domain.challenge.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.points.repository.PointWalletRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// 챌린지 인메모리 구현 — DB 없이 휘발성으로 보관한다(서버 재시작 시 초기화). postgres 프로필이
// 아닐 때만 활성화되며, JDBC의 롤백 경로이자 단위/동시성 테스트의 실측 저장소다.
// 원자성은 challenges 맵의 키 단위 원자 구간(computeIfPresent)으로 얻는다 — 같은 챌린지에 대한
// 참가·정산 요청은 이 구간에서 직렬화되므로 검사와 변경 사이에 다른 요청이 끼어들 수 없다.
@Repository
@Profile("!postgres")
public class InMemoryChallengeRepository implements ChallengeRepository {

    // 지갑 — v0.32.0에서 별도 저장소로 나갔다. 호출은 여전히 challenges 맵의 키 단위 원자 구간
    // **안에서** 일어나므로 참가의 원자성 계약은 그대로다(다른 맵을 만지는 것은 재진입 문제가
    // 없다 — 원장이 v0.31.0부터 같은 방식으로 동작해 온 그대로다). 기표는 지갑 저장소가
    // 시그니처로 강제한다.
    private final PointWalletRepository wallets;

    private final ConcurrentHashMap<Long, Challenge> challenges = new ConcurrentHashMap<>();
    // 챌린지 id → (소유자 → 참가 레코드). 정산 결과(payout)까지 담아야 해서 Set이 아니라 맵이다.
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ChallengeParticipant>> participants =
            new ConcurrentHashMap<>();
    // 조건 키 → 그 조건의 계획을 고정한 소유자 집합. JDBC의 challenge_seeds 테이블에 대응한다.
    private final ConcurrentHashMap<String, Set<String>> seeds = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    public InMemoryChallengeRepository(PointWalletRepository wallets) {
        this.wallets = wallets;
    }

    @Override
    public Challenge save(Challenge challenge) {
        Challenge saved = challenge.withId(idSequence.incrementAndGet());
        challenges.put(saved.id(), saved);
        return saved;
    }

    @Override
    public List<Challenge> findAll() {
        return challenges.values().stream()
                .sorted(Comparator.comparing(Challenge::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(Challenge::id).reversed()))
                .toList();
    }

    @Override
    public Optional<Challenge> findById(long id) {
        return Optional.ofNullable(challenges.get(id));
    }

    @Override
    public Map<Long, ChallengeParticipant> findMyParticipations(String owner) {
        Map<Long, ChallengeParticipant> mine = new HashMap<>();
        participants.forEach((challengeId, byOwner) -> {
            ChallengeParticipant participant = byOwner.get(owner);
            if (participant != null) {
                mine.put(challengeId, participant);
            }
        });
        return mine;
    }

    @Override
    public List<ChallengeParticipant> findParticipants(long challengeId) {
        return participants.getOrDefault(challengeId, new ConcurrentHashMap<>()).values().stream()
                .sorted(Comparator.comparing(ChallengeParticipant::owner)) // 정산 지급 순서 고정(owner 오름차순)
                .toList();
    }

    @Override
    public int balanceOf(String owner) {
        return wallets.balanceOf(owner);
    }

    // 참가 — 키 단위 원자 구간에서 검사와 변경을 함께 수행한다.
    // [순서가 규칙이다] 네 검사를 세 변경보다 반드시 앞에 둔다. 인메모리에는 트랜잭션이 없어
    // 중간에 예외를 던지면 앞선 변경을 되돌릴 수단이 없기 때문이다(JDBC는 롤백이 그 역할을 한다).
    // 검사가 모두 끝난 뒤에는 어떤 변경도 실패하지 않으므로 부분 적용 상태가 생기지 않는다.
    // wallets/participants는 challenges와 다른 맵이라 이 구간 안에서 갱신해도 재진입 문제가 없다.
    @Override
    public Challenge join(long challengeId, String owner, String joinedAt, long planId) {
        Challenge updated = challenges.computeIfPresent(challengeId, (id, current) -> {
            // 정산 마감된 챌린지는 미달이어도 닫혀 있다 — 환불 마감 후 참가가 뚫리면 그 참가비는
            // 영영 정산되지 않는다.
            if (current.settled()) {
                throw new BusinessException(ErrorCode.CHALLENGE_CLOSED);
            }
            ConcurrentHashMap<String, ChallengeParticipant> joined =
                    participants.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
            if (joined.containsKey(owner)) {
                throw new BusinessException(ErrorCode.CHALLENGE_ALREADY_JOINED);
            }
            int balance = wallets.balanceOf(owner);
            if (balance < current.entryFee()) {
                throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
            }
            if (current.full()) {
                throw new BusinessException(ErrorCode.CHALLENGE_FULL);
            }
            // 검사가 모두 끝난 뒤라 이 차감은 실패할 수 없다(잔액은 위에서 확인했고, 이 구간에
            // 다른 참가 요청이 끼어들 수 없다). 기표는 차감과 같은 호출에서 함께 일어난다.
            wallets.debit(owner, current.entryFee(), new PointTransfer("join:" + id + ":" + owner,
                    owner, PointAccounts.escrowOfChallenge(id), current.entryFee(),
                    PointTxKind.CHALLENGE_ENTRY, "challenge", String.valueOf(id), joinedAt));
            joined.put(owner, new ChallengeParticipant(owner, planId, null, null));
            Challenge next = current.withParticipantCount(current.participantCount() + 1);
            // 마지막 자리를 채운 참가가 시작 시각을 기록한다 — "정원이 찬 순간 = 시작"의 판정이
            // 이 원자 구간 안에 있어, 환불 정산(claimSettlement의 시작 전 조건)과 어긋나지 않는다.
            return next.full() ? next.withStartedAt(joinedAt) : next;
        });
        if (updated == null) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        return updated;
    }

    // 정산권 청구 — challenges 키 단위 원자 구간에서 "미정산 + 경로 조건"을 확인하고 settledAt을
    // 기록한다. 같은 챌린지에 대한 동시 청구는 이 구간에서 직렬화되므로 정확히 한 호출만 true를
    // 받는다(JDBC의 WHERE settled_at IS NULL 조건부 UPDATE와 같은 계약).
    @Override
    public boolean claimSettlement(long challengeId, String settledAt, boolean requireStarted) {
        AtomicBoolean claimed = new AtomicBoolean(false);
        challenges.computeIfPresent(challengeId, (id, current) -> {
            if (current.settled() || current.started() != requireStarted) {
                return current;
            }
            claimed.set(true);
            return current.withSettledAt(settledAt);
        });
        return claimed.get();
    }

    // 완주 기록 — 챌린지 키별 원자 구간(정산 claim·참가와 같은 구간) 안에서 판정·갱신한다. 시각은 구간에
    // 들어온 뒤 확정하므로 구간 밖 대기가 마감 전/후 판정을 오염시키지 않는다. 구간이 곧 JDBC의
    // "챌린지 행 FOR UPDATE"에 대응한다. 챌린지 id 오름차순으로 순회해 잠금 순서를 JDBC와 맞춘다.
    @Override
    public void markCompletion(long planId, boolean allDone) {
        participants.entrySet().stream()
                .filter(e -> e.getValue().values().stream().anyMatch(p -> Long.valueOf(planId).equals(p.planId())))
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(challengeId -> challenges.computeIfPresent(challengeId, (id, current) -> {
                    if (isOpenForRecording(current)) {
                        participants.getOrDefault(id, new ConcurrentHashMap<>()).replaceAll((owner, p) ->
                                Long.valueOf(planId).equals(p.planId())
                                        ? p.withCompletedAt(completedAtFor(p, allDone)) : p);
                    }
                    return current;
                }));
    }

    @Override
    public void seedCompletion(long challengeId, String owner, boolean allDone) {
        challenges.computeIfPresent(challengeId, (id, current) -> {
            // 참가 직후의 시드는 마감 시각을 다시 보지 않는다 — 참가 시각은 정의상 마감(참가 시각 + 기간)
            // 이전이다. 미정산이기만 하면 된다(JDBC 구현도 같다).
            if (!current.settled()) {
                participants.getOrDefault(id, new ConcurrentHashMap<>())
                        .computeIfPresent(owner, (key, p) -> p.withCompletedAt(completedAtFor(p, allDone)));
            }
            return current;
        });
    }

    // 기록 가능 = 미정산 + (미시작 또는 지금 < endsAt). 마감 이후·정산 이후의 변경은 무시된다.
    private static boolean isOpenForRecording(Challenge challenge) {
        if (challenge.settled()) {
            return false;
        }
        return !challenge.started() || Instant.now().isBefore(Instant.parse(challenge.endsAt()));
    }

    // 이미 완주 기록이 있으면 유지(처음 도달한 시각), 없으면 지금. 해제되면 비운다.
    private static String completedAtFor(ChallengeParticipant participant, boolean allDone) {
        if (!allDone) {
            return null;
        }
        return participant.completedAt() != null ? participant.completedAt() : Instant.now().toString();
    }

    // 인메모리에는 v0.34.0 이전 데이터가 없으므로 보류 건이 없다.
    @Override
    public Set<Long> findHeldChallengeIds() {
        return Set.of();
    }

    @Override
    public void recordForfeit(long challengeId, int amount) {
        if (amount <= 0) {
            return;
        }
        wallets.postSystemTransfer(new PointTransfer("forfeit:" + challengeId,
                PointAccounts.escrowOfChallenge(challengeId), PointAccounts.BURN, amount,
                PointTxKind.CHALLENGE_FORFEIT, "challenge", String.valueOf(challengeId), Instant.now().toString()));
    }

    // 정산 지급 — claim을 딴 호출자만 부른다. 인메모리에는 롤백이 없으므로 이 메서드는 실패할 수
    // 없는 연산만으로 구성한다(merge/compute — "검사를 변경 앞에" 규칙의 정산판).
    @Override
    public void recordPayout(long challengeId, String owner, int amount, PointTxKind kind) {
        if (amount > 0) {
            wallets.credit(owner, amount, new PointTransfer("payout:" + challengeId + ":" + owner,
                    PointAccounts.escrowOfChallenge(challengeId), owner, amount,
                    kind, "challenge", String.valueOf(challengeId), Instant.now().toString()));
        } else {
            wallets.balanceOf(owner); // 지갑이 없던 참가자도 지연 생성해 둔다(기존 동작 보존)
        }
        participants.getOrDefault(challengeId, new ConcurrentHashMap<>())
                .computeIfPresent(owner, (key, participant) -> participant.withPayout(amount));
    }

    // 씨앗 등록 — Set은 중복을 흡수하므로 같은 소유자가 몇 번 고정하든 크기는 늘지 않는다.
    // computeIfAbsent + 동시성 Set이라 등록과 계수 사이에 다른 등록이 끼어들어도 손실이 없다
    // (그 사이 값이 늘면 더 큰 값을 볼 뿐, 작아지지는 않는다).
    @Override
    public int recordSeed(String conditionKey, String owner, String seededAt) {
        Set<String> owners = seeds.computeIfAbsent(conditionKey, key -> ConcurrentHashMap.newKeySet());
        owners.add(owner);
        return owners.size();
    }

    // 조건별 생성 — seeds 맵의 키 단위 원자 구간을 자물쇠로 빌려 쓴다. 같은 조건에 대한 동시 생성이
    // 이 구간에서 직렬화되므로 "없나 확인 → 만든다" 사이가 열리지 않는다(JDBC의 부분 UNIQUE
    // 인덱스와 같은 계약). 다른 조건끼리는 키가 달라 서로 막지 않는다.
    @Override
    public void createIfNoOpenCondition(Challenge challenge) {
        seeds.compute(challenge.conditionKey(), (key, owners) -> {
            if (!hasOpenChallenge(key)) {
                save(challenge);
            }
            return owners;
        });
    }

    // ponytail: 전체 스캔. 조건 인덱스를 따로 두지 않는다 — 인메모리 저장소의 챌린지 수는 데모
    // 규모(수십 건)이고, 이 경로는 계획 고정 시에만 탄다. 느려지면 conditionKey 인덱스를 추가한다.
    // "모집 중" = 미달 + 미정산 — 미달인 채 환불 마감된 챌린지는 다음 개설을 막지 않는다
    // (JDBC의 재정의된 부분 UNIQUE 인덱스 WHERE절과 같은 판정).
    private boolean hasOpenChallenge(String conditionKey) {
        return challenges.values().stream()
                .anyMatch(c -> conditionKey.equals(c.conditionKey()) && !c.full() && !c.settled());
    }
}
