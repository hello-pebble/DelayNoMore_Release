package com.delaynomore.backend.domain.challenge.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// 챌린지 인메모리 구현 — DB 없이 휘발성으로 보관한다(서버 재시작 시 초기화). postgres 프로필이
// 아닐 때만 활성화되며, JDBC의 롤백 경로이자 단위/동시성 테스트의 실측 저장소다.
// 원자성은 challenges 맵의 키 단위 원자 구간(computeIfPresent)으로 얻는다 — 같은 챌린지에 대한
// 참가 요청은 이 구간에서 직렬화되므로 정원 검사와 증가 사이에 다른 참가가 끼어들 수 없다.
@Repository
@Profile("!postgres")
public class InMemoryChallengeRepository implements ChallengeRepository {

    // 데모 초기 잔액 — JDBC 구현과 같은 값이어야 한다(프로필이 바뀌어도 화면 숫자가 같도록).
    static final int INITIAL_BALANCE = 1000;

    private final ConcurrentHashMap<Long, Challenge> challenges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> participants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> wallets = new ConcurrentHashMap<>();
    // 조건 키 → 그 조건의 계획을 고정한 소유자 집합. JDBC의 challenge_seeds 테이블에 대응한다.
    private final ConcurrentHashMap<String, Set<String>> seeds = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

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
    public Set<Long> findJoinedChallengeIds(String owner) {
        return participants.entrySet().stream()
                .filter(e -> e.getValue().contains(owner))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Override
    public int balanceOf(String owner) {
        return wallets.computeIfAbsent(owner, key -> INITIAL_BALANCE);
    }

    // 참가 — 키 단위 원자 구간에서 검사와 변경을 함께 수행한다.
    // [순서가 규칙이다] 세 검사를 세 변경보다 반드시 앞에 둔다. 인메모리에는 트랜잭션이 없어
    // 중간에 예외를 던지면 앞선 변경을 되돌릴 수단이 없기 때문이다(JDBC는 롤백이 그 역할을 한다).
    // 검사가 모두 끝난 뒤에는 어떤 변경도 실패하지 않으므로 부분 적용 상태가 생기지 않는다.
    // wallets/participants는 challenges와 다른 맵이라 이 구간 안에서 갱신해도 재진입 문제가 없다.
    @Override
    public Challenge join(long challengeId, String owner, String joinedAt) {
        Challenge updated = challenges.computeIfPresent(challengeId, (id, current) -> {
            Set<String> joined = participants.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet());
            if (joined.contains(owner)) {
                throw new BusinessException(ErrorCode.CHALLENGE_ALREADY_JOINED);
            }
            int balance = wallets.computeIfAbsent(owner, key -> INITIAL_BALANCE);
            if (balance < current.entryFee()) {
                throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
            }
            if (current.full()) {
                throw new BusinessException(ErrorCode.CHALLENGE_FULL);
            }
            wallets.put(owner, balance - current.entryFee());
            joined.add(owner);
            return current.withParticipantCount(current.participantCount() + 1);
        });
        if (updated == null) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        return updated;
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
    private boolean hasOpenChallenge(String conditionKey) {
        return challenges.values().stream()
                .anyMatch(c -> conditionKey.equals(c.conditionKey()) && !c.full());
    }
}
