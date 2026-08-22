package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 동시성 검증 — 남은 자리 1개에 여러 명이 동시에 참가 요청을 보내도 정확히 1명만 성공하는지.
// 인메모리라 Mock 없이 실제 저장소를 주입한다(기존 PlanServiceConcurrencyTest와 같은 방식).
// 같은 시나리오의 실제 PostgreSQL 판본과 "틀린 구현은 정원을 넘긴다"는 대조군은
// ChallengeJoinConcurrencyIT에 있다.
class ChallengeServiceConcurrencyTest {

    private static final int CAPACITY = 5;
    private static final int ENTRY_FEE = 100;
    private static final int INITIAL_BALANCE = 1000;
    private static final String HOST = "guest-host-0001";

    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository();
    private final ChallengeService challengeService = new ChallengeService(challengeRepository);

    // 개설 API가 없어졌으므로(v0.23.0) 픽스처는 저장소에 직접 넣는다 — 이 테스트가 검증하는 것은
    // 참가의 동시성이지 챌린지가 어떻게 생기는지가 아니다.
    private long open(String title, int capacity) {
        return challengeRepository.save(new Challenge(null, HOST, title, 14, capacity, ENTRY_FEE, 0,
                java.time.Instant.now().toString(), null)).id();
    }

    private long openChallengeWithOneSeatLeft() {
        long id = open("자격증 공부 14일", CAPACITY);
        for (int i = 0; i < CAPACITY - 1; i++) {
            challengeService.join(id, "guest-early-000" + i);
        }
        return id;
    }

    @Test
    void join_동시5건_잔여1자리_정확히1명만성공() throws Exception {
        long challengeId = openChallengeWithOneSeatLeft();
        int racers = 5;
        List<String> contenders = List.of("guest-a-0001", "guest-b-0001", "guest-c-0001",
                "guest-d-0001", "guest-e-0001");

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();

        for (String contender : contenders) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    challengeService.join(challengeId, contender);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.CHALLENGE_FULL) {
                        full.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 정확히 1명 — 검사와 증가가 같은 원자 구간 안에 있어 TOCTOU가 없다.
        assertThat(success.get()).isEqualTo(1);
        assertThat(full.get()).isEqualTo(racers - 1);
        assertThat(challengeRepository.findById(challengeId).orElseThrow().participantCount())
                .isEqualTo(CAPACITY);

        // 정합성의 나머지 절반 — 자리를 못 얻은 4명은 포인트도 잃지 않았다(부분 적용 없음).
        long charged = contenders.stream()
                .filter(g -> challengeRepository.balanceOf(g) == INITIAL_BALANCE - ENTRY_FEE)
                .count();
        long untouched = contenders.stream()
                .filter(g -> challengeRepository.balanceOf(g) == INITIAL_BALANCE)
                .count();
        assertThat(charged).isEqualTo(1);
        assertThat(untouched).isEqualTo(racers - 1);
    }

    @Test
    void join_같은게스트가_동시중복참가_1회만성공하고_포인트도1회만차감() throws Exception {
        // 같은 사람이 참가 버튼을 연타(더블 클릭·재시도)해도 자리 1개와 참가비 1회만 소모돼야 한다.
        long challengeId = open("중복 참가 테스트", CAPACITY);
        String clicker = "guest-click-001";
        int clicks = 8;

        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        CountDownLatch ready = new CountDownLatch(clicks);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < clicks; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    challengeService.join(challengeId, clicker);
                    success.incrementAndGet();
                } catch (BusinessException ignored) {
                    // CHALLENGE_ALREADY_JOINED — 정상 경로
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(1);
        assertThat(challengeRepository.findById(challengeId).orElseThrow().participantCount()).isEqualTo(1);
        assertThat(challengeRepository.balanceOf(clicker)).isEqualTo(INITIAL_BALANCE - ENTRY_FEE);
    }

    @Test
    void join_정원20_동시100건_초과없이_정확히정원만큼만성공() throws Exception {
        // 경합 폭을 키운 판본 — 성공 수와 최종 인원이 정확히 정원과 같아야 한다.
        int capacity = 20;
        int racers = 100;
        long challengeId = open("대규모 경합", capacity);

        // 가상 스레드 — ready 배리어는 모든 참가자가 "동시에 대기 중"이어야 열리므로, 풀 크기가
        // 참가자 수보다 작으면 뒤쪽 작업이 시작조차 못 해 영영 열리지 않는다(교착).
        // 플랫폼 스레드 100개 대신 가상 스레드로 이 제약을 없앤다.
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        var winners = ConcurrentHashMap.<String>newKeySet();

        for (int i = 0; i < racers; i++) {
            String contender = String.format("guest-mass-%04d", i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    challengeService.join(challengeId, contender);
                    success.incrementAndGet();
                    winners.add(contender);
                } catch (BusinessException ignored) {
                    // CHALLENGE_FULL — 정상 경로
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(capacity);
        assertThat(winners).hasSize(capacity);
        assertThat(challengeRepository.findById(challengeId).orElseThrow().participantCount())
                .isEqualTo(capacity);

        // 차감된 지갑은 성공한 사람들뿐 — 탈락자 80명의 잔액은 그대로다.
        assertThat(winners).allSatisfy(g ->
                assertThat(challengeRepository.balanceOf(g)).isEqualTo(INITIAL_BALANCE - ENTRY_FEE));
        long untouched = java.util.stream.IntStream.range(0, racers)
                .mapToObj(i -> String.format("guest-mass-%04d", i))
                .filter(g -> !winners.contains(g))
                .filter(g -> challengeRepository.balanceOf(g) == INITIAL_BALANCE)
                .count();
        assertThat(untouched).isEqualTo(racers - capacity);
    }

    @Test
    void 목록응답의_참가여부와_잔액이_참가결과와_일치한다() {
        long challengeId = open("목록 확인", CAPACITY);
        String guest = "guest-view-001";

        challengeService.join(challengeId, guest);

        var listed = challengeService.list(guest);
        assertThat(listed.balance()).isEqualTo(INITIAL_BALANCE - ENTRY_FEE);
        ChallengeResponse view = listed.challenges().getFirst();
        assertThat(view.joined()).isTrue();
        assertThat(view.participantCount()).isEqualTo(1);
        assertThat(view.remainingSeats()).isEqualTo(CAPACITY - 1);
    }
}
