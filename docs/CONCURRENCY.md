# 동시성 — 정원 경쟁에서 데이터 정합성 지키기

**대상**: Goal Challenge 참가(v0.21.0) · `POST /api/v1/challenges/{id}/join`
**추가 대상**: 챌린지 자동 생성(v0.23.0) · `POST /api/v1/plans/{id}/confirm` → 7절

이 문서는 하나의 질문에 답한다: **거의 동시에 도착한 여러 참가 요청에서, 정원 5명을 어떻게
정확히 지키는가.**

```
Challenge  자격증 공부 14일
정원 5명 · 현재 4명 · 참가비 100P

A ─ 참가 요청 ─┐
B ─ 참가 요청 ─┤
C ─ 참가 요청 ─┼──→ 거의 동시에 도착
D ─ 참가 요청 ─┤
E ─ 참가 요청 ─┘
               ↓
        남은 자리 = 1
               ↓
   " 정확히 1명만 참가 성공 "
   " 실패한 4명은 포인트도 잃지 않는다 "
```

---

## 1. 틀린 구현은 실제로 깨진다

가장 자연스럽게 떠오르는 코드는 "세어 보고, 자리가 있으면 늘린다"이다.

```java
int count = jdbc.queryForObject("SELECT participant_count FROM challenges WHERE id = ?", ...);
if (count < capacity) {                                    // ← 판정
    jdbc.update("UPDATE challenges SET participant_count = participant_count + 1 WHERE id = ?", ...);
}
```

읽기와 쓰기가 **두 문장으로 갈라져 있다.** 그 사이에 다른 트랜잭션이 값을 바꿔도 이 코드는
알아채지 못한다. 판정의 근거였던 `count`는 UPDATE를 실행하는 시점에 이미 낡은 값이다
(TOCTOU — Time Of Check to Time Of Use).

| 시각 | A | B | C | D | E | DB `participant_count` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| t1 | SELECT → 4 | SELECT → 4 | SELECT → 4 | SELECT → 4 | SELECT → 4 | 4 |
| t2 | `4 < 5` ✔ | `4 < 5` ✔ | `4 < 5` ✔ | `4 < 5` ✔ | `4 < 5` ✔ | 4 |
| t3 | UPDATE +1 | UPDATE +1 | UPDATE +1 | UPDATE +1 | UPDATE +1 | **9** |

다섯 명 전원이 통과해 정원 5명짜리 챌린지에 **9명**이 들어간다. 각자의 UPDATE는 `+1`이라
행 락으로 직렬화되지만 — 락은 **증가를 잃지 않게** 해 줄 뿐, **판정이 틀렸다는 사실**은
고쳐 주지 않는다.

> 이 인터리빙은 추측이 아니다. `ChallengeJoinConcurrencyIT.naive_검사후쓰기는_동시요청에서_정원을_초과한다`가
> 실제 PostgreSQL에서 이 코드를 실행하고 `participant_count > capacity`를 assert한다.
> naive 코드는 그 테스트 파일 안에만 있다 — 프로덕션에 시연용 분기를 남기지 않았다.

---

## 2. 해법: 판정을 쓰기 안으로 넣는다

읽기와 쓰기 사이에 틈이 있는 게 문제라면, **틈을 없애면 된다.** 판정 조건을 UPDATE의
WHERE 절로 옮긴다.

```sql
UPDATE challenges
   SET participant_count = participant_count + 1
 WHERE id = :id AND participant_count < capacity
```

`JdbcChallengeRepository.join`의 핵심 한 문장이다. 갱신된 행 수가 곧 결과다:

- `1` → 자리를 얻었다
- `0` → 그 사이 정원이 찼다 → `CHALLENGE_FULL` (409)

**왜 이게 안전한가.** PostgreSQL 기본 격리 수준 READ COMMITTED에서, 같은 행을 UPDATE하려는
두 번째 트랜잭션은 첫 번째가 잡은 행 쓰기 락에 블로킹된다. 첫 번째가 커밋하면 두 번째는
그냥 진행하는 게 아니라 **갱신된 최신 행 버전으로 WHERE 조건을 다시 평가한다**(EvalPlanQual
재검사). 자리가 찼으면 조건이 거짓이 되어 0행이 갱신된다. 판정과 증가 사이에 다른
트랜잭션이 낄 물리적 틈이 없다.

애플리케이션은 정원 판정을 **하지 않는다**. `ChallengeService.join`에 `if (full)` 같은 검사가
없는 것은 실수가 아니라 규칙이다 — 서비스가 미리 세어 보고 던지면 그 순간 1번의 문제로 돌아간다.

---

## 3. 자리만 지켜서는 부족하다 — 실패의 원상복구

정원을 지켜도, 자리를 못 얻은 4명의 포인트가 사라지면 정합성이 깨진 것이다. 참가 1회는 네 가지가
**함께** 성립하거나 **함께** 무효여야 한다:

1. 중복 참가가 아니다
2. 참가비가 차감된다
3. 자리가 예약된다
4. 참가자로 등록된다

`ChallengeService.join`은 `@Transactional` 하나로 이 넷을 묶는다. `JdbcChallengeRepository.join`의
실행 순서와, 각 단계가 실패를 판정하는 방식:

| 순서 | SQL | 실패 판정 | 실패 시 ErrorCode |
| :--- | :--- | :--- | :--- |
| 1 | `INSERT INTO challenge_participants ... ON CONFLICT DO NOTHING` | 0행 | `CHALLENGE_ALREADY_JOINED` (409) |
| 2 | `UPDATE point_wallets SET balance = balance - :fee WHERE owner = :o AND balance >= :fee` | 0행 | `POINTS_INSUFFICIENT` (400) |
| 3 | `UPDATE challenges SET participant_count = participant_count + 1 WHERE id = :id AND participant_count < capacity` | 0행 | `CHALLENGE_FULL` (409) |

3번에서 예외를 던지면 트랜잭션이 롤백되어 **1번의 참가자 등록과 2번의 포인트 차감이 함께
사라진다.** 자리를 못 얻은 사람은 포인트도 잃지 않는다. 세 단계 모두 "검사가 WHERE 안에 있고,
갱신 행 수로 판정한다"는 같은 형태다.

**중복 참가와 잔액 부족도 애플리케이션이 판정하지 않는다.** 중복은 `challenge_participants`의
복합 PK가, 잔액은 `WHERE balance >= :fee`가 판정한다. 사전 `SELECT`로 확인한 값은 언제나
낡을 수 있으므로, 판정 주체는 항상 쓰기 그 자체다.

### 교착(deadlock)을 피하는 순서

모든 스레드가 **자기 행(참가자 · 지갑) → 경합 행(챌린지)** 순으로 동일하게 진행한다.
잠금 순서가 모든 트랜잭션에서 같으므로 순환 대기가 생기지 않는다. 여러 스레드가 다투는
챌린지 행 락은 마지막에, 가장 짧게 잡힌다.

---

## 4. 왜 다른 방법을 쓰지 않았나

| 방법 | 정원을 지키는가 | 채택하지 않은 이유 |
| :--- | :--- | :--- |
| **조건부 UPDATE** (채택) | ✅ | 왕복 1회, 락 보유 시간 최소, 인스턴스 수와 무관 |
| `synchronized` 애플리케이션 락 | ⚠️ 단일 인스턴스에서만 | 서버를 2대로 늘리는 순간 무력화된다. 이 저장소에도 선례(`PlanService.create`)가 있지만, 그 주석이 스스로 한계를 적어 두고 있다 — 트랜잭션 커밋이 모니터 해제보다 늦어 한도를 1건 넘길 수 있다 |
| `SELECT ... FOR UPDATE` 후 자바에서 검사 | ✅ | 정확하지만 왕복이 2회이고 락을 더 오래 잡는다. 여기서 필요한 건 카운터 하나의 비교-후-증가뿐이라 과하다. (행 전체를 읽어 자바에서 가공해야 하는 `JdbcPlanRepository.mutate`는 이쪽이 맞다 — 도구가 다른 게 아니라 문제가 다르다) |
| 낙관적 락(`@Version`) | ✅ | 충돌 시 재시도 루프가 필요하다. 정원 경쟁은 **충돌이 정상이고 흔한** 상황이라, 재시도해도 어차피 마감이면 실패한다 — 낙관적 가정이 맞지 않는다 |
| `SERIALIZABLE` 격리 수준 | ✅ | 직렬화 실패 재시도를 애플리케이션이 떠안아야 하고, 이 한 기능 때문에 전역 격리 수준을 올리는 비용이 크다 |
| `CHECK (participant_count <= capacity)` | ✅ (최후 방어) | **일부러 넣지 않았다** — 아래 참고 |

### CHECK 제약을 일부러 넣지 않은 이유

`V3__goal_challenge.sql`에는 `CHECK (participant_count <= capacity)`가 없다. 제약을 걸면
1번의 naive 구현이 정원을 넘길 때 오버부킹 대신 제약 위반 예외로 끝나고, **"틀린 구현은 실제로
정원을 넘는다"는 증거가 사라진다.** 이 저장소에서 정원 불변식을 지키는 것은 조건부 UPDATE
하나이며, 그 사실이 테스트로 드러나는 편이 문서로 주장하는 것보다 낫다고 판단했다.

운영에서 방어를 한 겹 더 원한다면 제약을 추가하면 된다 — 그때 naive 테스트는 오버부킹 대신
`DataIntegrityViolationException`을 기대하도록 바꾸면 된다. (`point_wallets.balance >= 0`
제약은 같은 이유가 없어 그대로 두었다.)

---

## 5. 인메모리 프로필은 어떻게 같은 계약을 지키나

기본(`!postgres`) 프로필은 DB 없이 도는 데모/테스트 경로라 트랜잭션이 없다.
`InMemoryChallengeRepository.join`은 `ConcurrentHashMap.computeIfPresent`의 **키 단위 원자
구간**으로 같은 보장을 만든다 — 같은 챌린지에 대한 참가 요청은 이 구간에서 직렬화된다.

트랜잭션이 없으므로 **롤백도 없다.** 그래서 이 구현은 규칙이 하나 더 있다: **세 검사를 세 변경보다
반드시 앞에 둔다.** 검사가 모두 끝난 뒤에는 어떤 변경도 실패하지 않으므로 부분 적용 상태가
생기지 않는다. JDBC 구현에서 롤백이 하는 일을 인메모리에서는 실행 순서가 한다.

두 구현이 같은 계약을 지킨다는 것은 `ChallengeRepository` 인터페이스 주석에 명시돼 있고,
`PlanRepository`의 가드 람다 계약과 같은 규칙이다.

---

## 6. 검증

| 테스트 | 저장소 | 확인하는 것 |
| :--- | :--- | :--- |
| `ChallengeJoinConcurrencyIT.naive_검사후쓰기는_동시요청에서_정원을_초과한다` | 실제 PostgreSQL | 검사-후-쓰기는 `participant_count > capacity`가 된다 (**대조군**) |
| `ChallengeJoinConcurrencyIT.safe_조건부UPDATE는_동시요청에서도_정확히_1명만_받는다` | 실제 PostgreSQL | 성공 1건 · 나머지 409 · 카운터와 참가자 행 수 일치 · **탈락자 잔액 원복** |
| `ChallengeServiceConcurrencyTest.join_동시5건_잔여1자리_정확히1명만성공` | 인메모리 | 같은 시나리오, Docker 없이도 도는 판본 |
| `ChallengeServiceConcurrencyTest.join_같은게스트가_동시중복참가_...` | 인메모리 | 버튼 연타 8회 → 자리 1개·참가비 1회만 소모 |
| `ChallengeServiceConcurrencyTest.join_정원20_동시100건_...` | 인메모리 | 경합 폭을 키워도 성공 수 == 정원, 탈락자 80명 잔액 무변화 |
| `ChallengeAutoGenerationIT.임계치를_동시에_넘겨도_같은_조건의_챌린지는_하나만_열린다` | 실제 PostgreSQL | 동시 고정 8건 → 같은 조건의 모집 중 챌린지 1건 (7절) |
| `ChallengeAutoGenerationTest` (8건) | 인메모리 | 임계치·소유자 단위 계수·중복 생성·마감 후 재개설 |

두 IT는 Testcontainers로 진짜 PostgreSQL 17을 띄운다. Docker가 없는 환경에서는
`@Testcontainers(disabledWithoutDocker = true)`로 통째로 스킵되고 인메모리 테스트만 돈다.

```bash
cd backend && ./gradlew test --tests '*Challenge*'
```

실서버에서 눈으로 확인하는 절차(정원을 채운 뒤 `curl`을 `&`로 동시에 던져 200 1건 / 409 4건)는
[QA_CHECKLIST.md](QA_CHECKLIST.md)의 `F-30` 절에, 자동 생성 확인 절차는 `F-32` 절에 있다.

---

## 7. 같은 문제의 두 번째 판본 — 챌린지 자동 생성 (v0.23.0)

v0.23.0부터 챌린지는 사용자가 개설하지 않는다. 비슷한 조건(기간 버킷 + 목적 카테고리)의 계획을
고정한 소유자가 3명 모이면 **계획 고정 시점에** 서버가 연다. 여기서 지켜야 하는 불변식은 정원과
성격이 같다:

> 같은 조건의 **모집 중** 챌린지는 언제나 최대 하나다.

틀린 구현은 1절과 판박이다. "이미 있나?"를 자바에서 확인하고 없으면 INSERT하면, 확인과 삽입
사이가 열려 동시에 임계치를 넘긴 두 고정이 같은 챌린지를 둘 만든다.

```java
// 틀림 — 확인과 삽입 사이가 열려 있다
if (challengeRepository.findOpenByCondition(key).isEmpty()) {   // ← 둘 다 "없음"을 본다
    challengeRepository.save(newChallenge(key));                // ← 둘 다 만든다
}
```

**정답도 같다: 판정을 쓰기 안으로 넣는다.** 다만 이번에는 조건부 UPDATE가 아니라 조건부 인덱스다.

```sql
CREATE UNIQUE INDEX uq_challenges_open_condition
    ON challenges (condition_key) WHERE participant_count < capacity;
```

```sql
INSERT INTO challenges (..., condition_key) VALUES (..., :conditionKey)
ON CONFLICT DO NOTHING;   -- 0행 = 이미 모집 중인 챌린지가 있다
```

`WHERE participant_count < capacity`가 부분 인덱스의 조건절이라는 점이 이 설계의 핵심이다.
정원이 찬 챌린지는 **인덱스에서 스스로 빠지므로**, "마감된 조건은 다시 열릴 수 있다"는 규칙을
애플리케이션 코드 한 줄 없이 얻는다. 인덱스에 걸린 행은 언제나 "지금 모집 중인 것"뿐이다.

`condition_key`가 NULL인 행(v0.22.0 이전의 사용자 개설분)은 UNIQUE 인덱스에서 서로 충돌하지
않는다(SQL의 NULL은 서로 같지 않다) — 레거시 행이 자동 생성을 막지 않는다.

**"몇 명이 모였는가"도 같은 방식으로 센다.** 한 사람이 비슷한 계획을 셋 고정한 것은 셋이 아니라
하나여야 하는데, 이 중복 제거 역시 애플리케이션이 아니라 복합 PK가 한다:

```sql
CREATE TABLE challenge_seeds (
    condition_key TEXT NOT NULL,
    owner         TEXT NOT NULL,
    seeded_at     TEXT NOT NULL,
    PRIMARY KEY (condition_key, owner)   -- ← 같은 사람의 재고정은 여기서 흡수된다
);
```

**실패해서는 안 된다는 제약이 하나 더 있다.** 이 경로는 계획 고정 트랜잭션 안에서 실행되므로,
자동 생성이 던진 예외는 계획 고정 자체를 롤백시킨다. "챌린지가 안 열렸다"는 계획을 못 고정할
이유가 아니다. 그래서 두 문장 모두 `ON CONFLICT DO NOTHING`으로 충돌을 예외가 아닌 0행으로
흡수하고, `ChallengeService.onPlanConfirmed`에는 try/catch가 없다 — 삼킬 예외 자체를 만들지
않았기 때문에, 거기서 나는 예외는 전부 진짜 버그다.

인메모리 구현은 같은 계약을 `seeds` 맵의 키 단위 원자 구간(`compute`)으로 제공한다. 조건 키 하나에
대한 생성이 그 구간에서 직렬화되므로 "없나 확인 → 만든다" 사이가 열리지 않는다.

**분류는 이 경로에 없다.** 조건 키는 계획을 만들 때 이미 정해져 계획 행에 저장돼 있고(초안 생성
LLM 호출이 목적을 함께 판정한다, `plans.category` → `Plan.conditionKey()`), 고정 시점에는 그 값을
그대로 넘길 뿐이다 — `ChallengeService.onPlanConfirmed(owner, conditionKey)`. 분류를 두 군데서 하지
않는 것은 동시성 문제라기보다 정합성 문제다: 같은 계획이 시점에 따라 다른 조건으로 읽히면
"같은 조건"이라는 말 자체가 흔들린다.

스케줄러(또는 관리자 화면)를 붙일 때 필요한 것도 이 값 하나다. `plans.condition_key`를
`GROUP BY`로 집계하면 "지금 어떤 조건에 몇 명이 모여 있나"가 나오고, 나온 키는
`ChallengeCondition.parse`로 그대로 챌린지가 된다. 지금 트리거가 고정 시점인 이유는 단순하다 —
조건이 채워지는 순간이 바로 그때이고, 즉시 반응이 주기적 반응보다 낫기 때문이다.
