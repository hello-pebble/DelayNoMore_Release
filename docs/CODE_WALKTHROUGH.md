# 코드 워크스루 — 필수 함수와 워크플로 (입문자용)

> 이 문서는 **처음 이 저장소를 여는 사람**을 위한 것이다. 중학생이 읽어도 "무엇을, 왜 그렇게
> 했는지"가 이해되도록 그림과 표 위주로 썼다. 코드를 옮겨 적기보다 **판단의 이유**를 남긴다.
>
> 짝이 되는 문서: [DATA_FLOW.md](DATA_FLOW.md)(전체 구조·ERD) ·
> [CONCURRENCY.md](CONCURRENCY.md)(동시성의 근거와 대안 비교) ·
> [AGENT.md](AGENT.md)(도구 카탈로그·권한 모델) · [ARCHITECTURE.md](ARCHITECTURE.md)(디렉토리·스택)

---

## 목차

| 장 | 내용 |
| :--- | :--- |
| [0](#0-전체-그림-한-장) | 전체 그림 한 장 |
| [1](#1-계획의-일생--상태status가-모든-것을-정한다) | 계획의 일생 — 상태가 모든 것을 정한다 |
| [2](#2-ai-에이전트-루프--ai가-도구를-쓰는-방법) | AI 에이전트 루프 |
| [3](#3-포인트-원장--복식부기가-뭔가요) | 포인트 원장 — 복식부기 |
| [4](#4-동시성--판정을-쓰기-안으로) | 동시성 — 판정을 쓰기 안으로 |
| [5](#5-보상과-예치--포인트-경제가-닫히는-곳) | 보상과 예치 |
| [6](#6-자료-검색--임베딩-없이-한국어를-찾는-법) | 자료 검색 |
| [7](#7-비용-방어--두-층으로-막는다) | 비용 방어 |
| [8](#8-스트리밍sse-깊이-파기) | **스트리밍(SSE) 깊이 파기** |
| [9](#9-상태-관리--백엔드) | **상태 관리 — 백엔드** |
| [10](#10-상태-관리--프론트엔드) | **상태 관리 — 프론트엔드** |
| [11](#11-필수-함수-총정리) | 필수 함수 총정리 |
| [12](#12-코드-전체를-관통하는-규칙) | 코드 전체를 관통하는 규칙 |

---

## 0. 전체 그림 한 장

```
 [사람]
   │  "정보처리기사 2주 준비하고 싶어"
   ▼
┌──────────────┐   HTTP/SSE   ┌───────────────────────────┐   HTTPS   ┌──────────┐
│  프론트엔드   │ ───────────▶ │        백엔드 (서버)       │ ────────▶ │  LLM(AI) │
│  React 19    │ ◀─────────── │      Spring Boot 4.1      │ ◀──────── │OpenRouter│
│  화면·버튼   │   글자 조각   │  ★ 규칙은 전부 여기 있다   │           └──────────┘
└──────────────┘              └─────────────┬─────────────┘
                                            │ SQL
                                            ▼
                                     ┌──────────────┐
                                     │ PostgreSQL   │  계획·회고·포인트원장
                                     └──────────────┘
```

**이 프로젝트의 한 줄 철학:** 프론트와 AI는 "말하는 역할", 서버는 "판정하는 역할".
완료율·포인트·권한은 전부 서버가 계산한다. 그래야 사용자가 브라우저를 조작해도, AI가
헛소리를 해도 숫자가 망가지지 않는다.

---

## 1. 계획의 일생 — 상태(Status)가 모든 것을 정한다

```
        [만들기]              [고정]                [완료]
   ┌─────────┐  confirm  ┌───────────┐  complete  ┌───────────┐
   │  DRAFT  │ ────────▶ │ CONFIRMED │ ─────────▶ │ COMPLETED │ ← 끝(잠김)
   │  초안   │           │   고정    │            └───────────┘
   └────┬────┘           └─────┬─────┘
        │   cancel             │  cancel         ┌───────────┐
        └──────────────────────┴───────────────▶ │ CANCELLED │ ← 끝(잠김)
                                                 │   중단    │
                                                 └───────────┘
```

### 필수 함수 ①② — `PlanStatus`의 능력 플래그

`backend/.../domain/plan/entity/PlanStatus.java`

```java
public boolean allowsStructuralEdit()  { return this == DRAFT; }      // 내용 수정 가능?
public boolean allowsCompletionToggle(){ return !isTerminal(); }      // 체크 가능?
public boolean allowsDomainResearch()  { return this != DRAFT; }      // 자료 검색 가능?
public boolean allowsDeposit()         { return this == CONFIRMED; }  // 포인트 걸기 가능?
```

게임 캐릭터가 레벨에 따라 쓸 수 있는 스킬이 다른 것과 같다. **"지금 이거 해도 돼?"라는 질문의
정답을 아는 곳이 이 파일 하나**뿐이다.

| 상태 | 내용 수정 | 완료 체크 | 자료 검색 | 포인트 걸기 | 담당 AI 성격 |
| :--- | :---: | :---: | :---: | :---: | :--- |
| DRAFT (초안) | ✅ | ✅ | ❌ | ❌ | 체크리스트 코치 |
| CONFIRMED (고정) | ❌ | ✅ | ✅ | ✅ | **목표 전문 에이전트** |
| COMPLETED (완료) | ❌ | ❌ | ✅ | ❌ | 회고 도우미 |
| CANCELLED (중단) | ❌ | ❌ | ✅ | ❌ | 회고 도우미 |

> 이 표를 다른 파일에 또 적지 않는 것이 이 저장소의 규칙이다(CLAUDE.md). 두 곳에 적으면
> 언젠가 서로 달라지고, 그때부터 "누구 말이 맞는지" 아무도 모른다.

### 필수 함수 ③ — `Plan.countAllTasks()` : 완료율의 유일한 계산기

```java
public TaskCounts countAllTasks() {        // → {완료 개수, 전체 개수}
    for (Object dayTasks : tasks.values()) // 모든 날짜를 돌면서
        ... completed++ / total++          // completed == true 인 것만 센다
}
```

진행률 표시 · 챌린지 승패 판정 · 완주 보상 · 예치 환급률 — **네 가지가 전부 이 함수 하나를
쓴다.** 각자 세면 "화면엔 100%인데 보상은 안 나오는" 사고가 난다.

---

## 2. AI 에이전트 루프 — AI가 "도구"를 쓰는 방법

```
  사용자: "이번 주 얼마나 했어?"
      │
      ▼
 ┌────────────────────────────────────────────────────────┐
 │ AgentRunner.run()                                      │
 │  1. 오늘 사용 횟수 확인 (rateLimiter)                   │
 │  2. 서버 DB에서 계획 상태 읽기 ← 프론트 말을 안 믿는다   │
 │  3. 상태에 맞는 도구 목록 뽑기 (toolRegistry)           │
 └───────────────────────┬────────────────────────────────┘
                         ▼
          ╔══════════ runLoop (최대 4바퀴) ══════════╗
          ║                                          ║
          ║   LLM에게 [대화 + 쓸 수 있는 도구 목록]   ║
          ║              │                           ║
          ║     ┌────────┴────────┐                  ║
          ║     │                 │                  ║
          ║  도구 안 씀        "get_weekly_summary    ║
          ║     │              불러줘"                ║
          ║     ▼                 │                  ║
          ║  이게 최종 답변!       ▼                  ║
          ║               서버가 진짜로 실행           ║
          ║               결과를 대화에 붙여서 ───────╫──┐
          ║                                          ║  │
          ╚══════════════════════════════════════════╝◀─┘
```

### 필수 함수 ④ — `AgentToolRegistry.find(name, status)` : 권한의 문지기

```java
public Optional<AgentTool> find(String name, PlanStatus status) {
    AgentTool tool = byName.get(name);
    return (tool != null && tool.isAvailableFor(status)) ? Optional.of(tool) : Optional.empty();
}
```

AI에게 "고정된 계획은 수정하지 마세요"라고 **부탁하지 않는다.** 아예 수정 도구를 손에
쥐여주지 않는다. 부탁은 어길 수 있지만, 없는 버튼은 누를 수 없다.

| 방식 | 비유 | 안전한가? |
| :--- | :--- | :--- |
| 프롬프트로 부탁 | "냉장고 열지 마" 쪽지 붙이기 | ❌ 무시하면 끝 |
| **도구를 안 줌 (이 방식)** | **냉장고에 자물쇠** | ✅ 물리적으로 불가능 |

게다가 `find`는 **두 번** 검사한다 — 목록을 줄 때 한 번, 실행할 때 또 한 번. AI가 옛날 대화를
기억해 없는 도구를 불러도 막힌다.

### 필수 함수 ⑤ — `AgentRunner.runLoop()` : 무한루프 방지턱

```java
for (int turn = 1; turn <= MAX_TOOL_TURNS; turn++) {   // MAX_TOOL_TURNS = 4
    ...
    if (!completion.hasToolCalls()) return ...;        // 도구 안 쓰면 그게 답
}
// 4바퀴를 다 돌았다 → 도구 목록을 빼고 한 번 더 질문
openRouterClient.completeWithTools(AGENT_FINAL, messages, MAX_REPLY_TOKENS, null);
```

마지막에 **도구 목록을 `null`로** 넘기는 것이 핵심이다. 도구가 없으면 AI는 도구를 부를 방법
자체가 없으니 **반드시 글로 답하게 된다.** "그만해"라고 부탁하는 대신 수단을 뺏는다 — 위의
자물쇠와 같은 아이디어다.

| 상한 | 값 | 없으면 생기는 일 |
| :--- | :---: | :--- |
| `MAX_TOOL_TURNS` | 4바퀴 | AI가 같은 도구를 무한 반복 → 요금 폭탄 |
| `MAX_CALLS_PER_TURN` | 3개 | 한 번에 도구 20개 호출 → 서버 부하 |
| `MAX_REPLY_TOKENS` | 1200 | 답변이 끝없이 길어짐 |

> 상한을 넘겨 잘라낸 도구 호출도 **모델에게 알린다**(`ToolResult.fail`). 조용히 버리면 모델이
> "실행됐다"고 착각한 채 답한다.

---

## 3. 포인트 원장 — "복식부기"가 뭔가요

```
   민수가 챌린지에 100P 참가비를 냄

   ┌─────────────────────────────────────────────┐
   │ tx_key = "join:7:민수"   ← 영수증 번호       │
   ├──────────────────────┬──────────────────────┤
   │ 계정: 민수            │ 계정: escrow:challenge:7
   │ 금액: -100           │ 금액: +100           │
   └──────────────────────┴──────────────────────┘
                     합계 = 0  ✅
```

돈은 **생기거나 사라지지 않고 자리만 옮긴다.** 그래서 한쪽이 -100이면 반드시 다른 쪽이 +100이다.
전 세계 모든 줄을 다 더하면 **항상 0**이어야 한다. 0이 아니면? 100% 버그다. 은행이 200년 전부터
쓰는 방법이다.

| 불변식 (절대 깨지면 안 되는 규칙) | 확인 방법 |
| :--- | :--- |
| ① 전체 원장 금액의 합 = 0 | `SELECT sum(amount) FROM point_ledger` → 0 |
| ② 사람별 원장 합계 = 지갑 잔액 | 둘이 다르면 잔액이 거짓말 중 |

> 운영 점검 쿼리는 [OPERATIONS.md 2-6장](OPERATIONS.md)에 있다.

### 필수 함수 ⑥ — `JdbcPointLedgerRepository.post()` : 두 줄을 한 번에

```java
INSERT INTO point_ledger (...) VALUES
       (:txKey, :from, :negative, ...),   ← 나가는 줄
       (:txKey, :to,   :amount,   ...)    ← 들어오는 줄
ON CONFLICT (tx_key, account) DO NOTHING

if (inserted == 1) throw new IllegalStateException(...);  // 한쪽만 들어감 = 즉시 사고
return inserted == 2;
```

| 결과 행 수 | 의미 | 처리 |
| :---: | :--- | :--- |
| 2행 | 정상 기록됨 | `true` 반환 |
| 0행 | **같은 영수증 번호가 이미 있음** (중복 요청) | `false` 반환 — 조용히 무시 |
| 1행 | 한쪽만 들어감 → 합계가 0이 아님 | **예외를 던져 전부 되돌림** |

**`tx_key`(멱등 키)가 핵심이다.** 인터넷이 끊겨 사용자가 버튼을 두 번 눌러도, 만들어지는 영수증
번호가 `deposit:42`로 **똑같기** 때문에 두 번째는 DB가 튕겨낸다. "이미 처리했나?" 하고 미리
조회할 필요가 없다.

| 사건 | 멱등 키 모양 |
| :--- | :--- |
| 가입 지급 | `signup:<owner>` |
| 챌린지 참가 | `join:<challengeId>:<owner>` |
| 챌린지 정산 | `payout:<challengeId>:<owner>` |
| 완주 보상 | `earn:plan:<planId>` |
| 목표 예치 | `deposit:<planId>` |
| 예치 환급 / 소각 | `deposit-refund:<planId>` / `deposit-forfeit:<planId>` |

### 필수 함수 ⑦ — `debit()` : 순서가 곧 규칙

```java
public void debit(String owner, int amount, PointTransfer transfer) {
    ensureWallet(owner);
    if (!ledger.post(transfer)) return;   // ★ 기표가 실제로 일어났을 때만 아래로 간다
    int debited = jdbc.update("""
            UPDATE point_wallets SET balance = balance - :amount
             WHERE owner = :owner AND balance >= :amount        ← 검사가 UPDATE 안에!
            """);
    if (debited == 0) throw new BusinessException(POINTS_INSUFFICIENT);
}
```

**이 순서가 실제 버그를 잡았다**(v0.32.0 개발 중 발견):

| 순서 | 중복 요청이 들어오면 | 결과 |
| :--- | :--- | :--- |
| 잔액 먼저 → 기표 나중 (❌ 버그였던 버전) | 잔액은 **두 번** 깎이고, 원장은 중복을 튕겨냄 | 잔액 1140, 원장 1420 → **불변식 ② 붕괴** |
| **기표 먼저 → 잔액 나중 (✅ 현재)** | 원장이 `false` 반환 → 잔액은 건드리지 않음 | 둘 다 그대로 → 안전 |

> 멱등의 단위는 "원장 줄"이 아니라 **"잔액 + 기표" 한 쌍**이다.

---

## 4. 동시성 — "판정을 쓰기 안으로"

이 저장소에서 가장 많이 반복되는 아이디어다. 챌린지 마지막 한 자리에 여러 명이 **동시에** 몰린
상황을 보자.

```
❌ 틀린 방법 (먼저 물어보고 나중에 실행)

  민수                          DB                          지연
   │── "자리 남았어?" ─────────▶ │                            │
   │◀── "응, 1자리" ─────────── │ ◀─── "자리 남았어?" ────────│
   │                            │ ───── "응, 1자리" ────────▶ │   ← 둘 다 "예"를 들음!
   │── "참가할게" ─────────────▶ │                            │
   │                            │ ◀──── "참가할게" ───────────│
                          정원 5명인데 6명 참가 ✗

✅ 맞는 방법 (묻지 않고, 조건을 명령 안에 넣는다)

  UPDATE challenges
     SET participant_count = participant_count + 1
   WHERE id = :id
     AND participant_count < capacity     ← 검사가 여기 안에 들어있다
     AND settled_at IS NULL

  민수 ──▶ 1행 바뀜 → 성공 🎉
  지연 ──▶ 0행 바뀜 → CHALLENGE_FULL (포인트도 안 깎임)
```

### 필수 함수 ⑧ — `JdbcChallengeRepository.join()` : 3단 원자 작업

```java
// 1) 중복 참가 방지 — 복합 PK가 판정
INSERT ... ON CONFLICT (challenge_id, owner) DO NOTHING
if (inserted == 0) throw CHALLENGE_ALREADY_JOINED;

// 2) 참가비 차감 — 잔액 검사도 UPDATE 안에
wallets.debit(owner, entryFee, transfer);

// 3) 자리 예약 — 정원 검사가 WHERE 안에
UPDATE challenges SET participant_count = participant_count + 1,
       started_at = CASE WHEN participant_count + 1 >= capacity THEN :now ELSE started_at END
 WHERE id = :id AND participant_count < capacity AND settled_at IS NULL
if (reserved == 0) throw CHALLENGE_FULL;   // ← 여기서 던지면 1·2단계도 함께 취소됨
```

| 단계 | 판정 주체 | 자바 `if`로 미리 검사하나? |
| :--- | :--- | :---: |
| 중복 참가 | DB의 복합 기본키 | ❌ 안 함 |
| 잔액 부족 | `WHERE balance >= :amount` | ❌ 안 함 |
| 정원 초과 | `WHERE participant_count < capacity` | ❌ 안 함 |

"빈 자리 있나요?" 하고 물어본 뒤 앉으려 하면, 묻고 앉는 사이에 남이 앉는다. 대신 **"빈 자리가
있으면 앉혀줘"를 한 문장으로** 말하면 DB가 그 순간 혼자 판단해서 딱 한 명만 앉힌다.

> 마지막 자리를 채우는 참가가 **시작 시각까지 같은 문장에서** 기록한다는 점에 주목.
> "정원이 찬 순간 = 시작"이라는 판정도 원자 구간 안에 있어야 환불 정산과 경쟁하지 않는다.

---

## 5. 보상과 예치 — 포인트 경제가 닫히는 곳

```
   system:issuance (발행)                           system:burn (소각)
        │                                                ▲
        │ 가입 1000P                                      │ 못 지킨 만큼
        │ 완주 보상 50~200P                                │
        ▼                                                │
   ┌──────────┐    걸기 10~500P     ┌──────────────────┐ │
   │  내 지갑  │ ─────────────────▶ │ escrow:plan:42   │─┘
   │          │ ◀───────────────── │  (예치 금고)      │
   └──────────┘   달성률만큼 환급    └──────────────────┘
                                      정산 후 반드시 0
```

### 필수 함수 ⑨ — `PlanRewardService.rewardFor()` : 완주 보상 계산

```java
public static int rewardFor(Plan plan) {
    TaskCounts counts = plan.countAllTasks();
    if (counts.total() == 0 || counts.completed() != counts.total()) return 0;  // 100% 아니면 0
    int duration = plan.duration() == null ? 1 : plan.duration();
    return Math.clamp((long) duration * PER_DAY, MIN_REWARD, MAX_REWARD);       // 10P/일, 50~200
}
```

| 계획 | 완료율 | 계산 | 보상 |
| :--- | :---: | :--- | ---: |
| 3일 계획 | 100% | 3×10=30 → 하한 50 | **50P** |
| 14일 계획 | 100% | 14×10=140 | **140P** |
| 30일 계획 | 100% | 30×10=300 → 상한 200 | **200P** |
| 14일 계획 | 93% | 100%가 아님 | **0P** |

### 필수 함수 ⑩ — `PlanDeposit.refundFor()` : 달성률 비례 환급

```java
public static int refundFor(int amount, int completed, int total) {
    if (total <= 0) return amount;                      // 할 일이 없었으면 전액 환급
    int done = Math.min(Math.max(completed, 0), total);
    return (int) ((long) amount * done / total);        // 내림 나눗셈
}
```

100P를 걸었을 때:

| 달성률 | 환급 | 소각 | 설명 |
| :---: | ---: | ---: | :--- |
| 10/10 (100%) | 100P | 0P | 전액 회수 |
| 7/10 (70%) | 70P | 30P | 못 지킨 만큼만 잃음 |
| 0/10 (0%) | 0P | 100P | 전액 소각 |

> **내림 나눗셈인 이유:** 환급액이 예치금을 절대 넘지 못하게 하려고. 100%일 때는
> `amount × total / total`이라 정확히 전액이 돌아온다 — 내림이 손해를 만들지 않는다.

### 필수 함수 ⑪ — `settleOnTerminal()` : 정산의 단일 입구

```java
public void settleOnTerminal(Plan plan) {
    PlanDeposit deposit = ...;
    if (deposit == null || deposit.settled()) return;

    int refund = PlanDeposit.refundFor(deposit.amount(), counts.completed(), counts.total());

    if (!depositRepository.claimSettlement(plan.id(), now, refund)) return;  // ★ 정산권 경쟁
    if (refund > 0)    wallets.credit(...);   // 돌려주고
    if (forfeited > 0) ledger.post(...);      // 나머지는 소각 계정으로
}
```

`claimSettlement`도 **조건부 UPDATE**다 — `WHERE settled_at IS NULL`. 동시에 "완료 버튼"과
"삭제 버튼"이 눌려도 **정확히 한 명만** 정산권을 얻고 나머지는 조용히 물러난다. 이중 환급이
원천 차단된다.

| 계획이 이렇게 끝나면 | 호출되는 곳 | 보상 | 예치 정산 |
| :--- | :--- | :---: | :---: |
| 완료 (COMPLETED) | `PlanService.complete()` | ✅ | ✅ |
| 중단 (CANCELLED) | `PlanService.cancel()` | ❌ | ✅ |
| 삭제 | `PlanService.delete()` | ❌ | ✅ (**삭제 전에** 실행) |

> **삭제 순서는 실제 버그였다.** DB에서 계획을 지우면 예치 행이 연쇄 삭제(CASCADE)돼 버려서,
> 나중에 정산하려 하면 근거가 이미 사라진 뒤였다. 포인트가 `escrow:plan:4`에 영원히 갇혔다.
> 그래서 **"정산 → 삭제"** 순서가 규칙이다. 이 함정은 실제 PostgreSQL에 붙여 돌렸을 때만
> 드러났다(인메모리에는 CASCADE가 없다) — [CONCURRENCY.md 11장](CONCURRENCY.md).

---

## 6. 자료 검색 — 임베딩 없이 한국어를 찾는 법

```
   질문:  "정규화가 뭐야"
   자료:  "정규화란 데이터 중복을 줄이는 과정이다"

   단어로 비교하면?   "정규화가" ≠ "정규화란"   →  못 찾음 ✗

   글자 2개씩(bigram)으로 자르면?
     질문: [정규][규화][화가][가뭐][뭐야]
     자료: [정규][규화][화란][란데][데이] ...
            ↑↑↑↑  ↑↑↑↑
            2개 겹침                       →  찾음! ✅
```

### 필수 함수 ⑫ — `BigramLexicalSearcher.dice()`

```java
static double dice(Map<String,Integer> a, Map<String,Integer> b) {
    overlap = Σ min(a[g], b[g]);                  // 겹치는 조각 개수
    return (2.0 * overlap) / (sizeA + sizeB);     // 0.0 ~ 1.0 점수
}
```

| 방식 | 한국어 조사 변형 | 외부 의존성 | 채택 |
| :--- | :---: | :--- | :---: |
| 단어 완전 일치 | ❌ 실패 | 없음 | |
| 형태소 분석기 | ✅ | 무거운 라이브러리 | |
| **문자 bigram Dice** | ✅ | **없음** | ✅ |
| 임베딩(벡터) | ✅ | 번들 모델이 영·중국어만 지원 | |

> 동점은 `(docId, seq)` 오름차순으로 고정한다 — 인메모리/JDBC 어느 프로필에서도 같은 순위가
> 나와야 평가·QA가 재현된다.

### 필수 함수 ⑬ — `KnowledgeChunker.split()` : 자료를 조각내기

```java
public static final int CHUNK_CHARS = 500;    // 한 조각 500자
public static final int OVERLAP_CHARS = 100;  // 앞 조각과 100자 겹치게
```

```
  긴 문단 ────────────────────────────────────────────────▶
  [───── 조각 1 (500자) ─────]
                     [───── 조각 2 (500자) ─────]
                     └─ 100자 겹침 ─┘
```

**왜 겹치게 자르나:** 중요한 문장이 딱 경계에 걸리면 어느 쪽에서도 온전히 검색되지 않는다.
100자를 겹쳐두면 최소 한쪽에는 문장 전체가 들어간다.

---

## 7. 비용 방어 — 두 층으로 막는다

### 필수 함수 ⑭ — `AiRateLimiter.tryAcquire()`

```java
AtomicInteger counter = todayCounters().computeIfAbsent(key, k -> new AtomicInteger());
if (counter.incrementAndGet() > limit) {   // ★ 먼저 올리고
    counter.decrementAndGet();             //   넘쳤으면 되돌린다
    return false;
}
return true;
```

**또 같은 아이디어다.** "지금 몇 번 썼지?" 물어본 뒤 올리면 그 사이에 남이 올린다. 그래서
**일단 올려보고 넘치면 되돌린다.** 원자적 증가(`incrementAndGet`)는 끼어들 틈이 없다.

| 층 | 세는 대상 | 어디서 | 왜 필요한가 |
| :--- | :--- | :--- | :--- |
| 소유자별 | **요청** 수 | `AgentRunner.run` 입구 | 정상 사용자 한 명의 폭주 방지 |
| **전역** | **LLM 호출** 수 | `OpenRouterClient` 안 | 게스트 ID를 새로 만들면 개인 한도가 초기화되므로, **지갑을 실제로 지키는 건 이쪽** |

> 단위가 다른 게 의도다. 한 번 대화하면 AI가 도구를 3번 부를 수 있는데, 그걸 개인 한도로 세면
> "대화 20번 했는데 60이 다 찼다"가 된다.
>
> 전역 상한이 `OpenRouterClient` 안에 있는 이유: **새 진입점이 생겨도 자동으로 덮인다.**
> 레거시 `/chats`·`/drafts`도 이미 보호받고 있다.

---

## 8. 스트리밍(SSE) 깊이 파기

### 8-1. SSE가 뭔가요

AI 답변은 한 번에 나오지 않는다. "안녕" → "하세" → "요" 처럼 조각조각 만들어진다. 다 만들어질
때까지 기다리면 사용자는 10초간 빈 화면을 본다. **SSE(Server-Sent Events)** 는 서버가 만드는
족족 조각을 흘려보내는 방식이다.

| 방식 | 방향 | 이 프로젝트에 맞나? |
| :--- | :--- | :--- |
| 일반 HTTP | 요청 1 → 응답 1 | ❌ 다 만들 때까지 기다려야 함 |
| 폴링(1초마다 물어보기) | 요청 N → 응답 N | ❌ 낭비가 크고 여전히 끊김 |
| WebSocket | 양방향 상시 연결 | 과하다 — 사용자→서버는 한 번뿐 |
| **SSE** | **서버 → 클라이언트 단방향** | ✅ 딱 맞음 |

**SSE의 생김새는 놀랄 만큼 단순하다.** 그냥 텍스트다:

```
data: {"type":"token","t":"안녕"}
                                  ← 빈 줄 하나가 "이벤트 끝" 신호
data: {"type":"token","t":"하세요"}

data: {"type":"done"}

```

### 8-2. 서버 쪽: 왜 별도 스레드가 필요한가

```java
public SseEmitter stream(AiChatRequest request, String owner, String sessionId) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);   // 120초
    sseExecutor.submit(() -> relay(request, owner, sessionId, emitter));  // ★ 딴 스레드로
    return emitter;                                            // ← 즉시 반환!
}
```

```
  [톰캣 요청 스레드]                    [sseExecutor 스레드]
        │                                     │
        │ emitter 만들고 바로 return           │
        │  (연결은 열린 채로 남는다)            │
        ▼                                     │
     스레드 반납 ✅                      LLM에서 읽어서 ─── emitter.send() ──▶ 브라우저
                                              │  (120초 동안 여기서 일함)
                                              ▼
                                        emitter.complete()
```

**만약 톰캣 스레드에서 그냥 돌리면?** 요청 하나가 스레드를 120초씩 붙잡는다. 동시 접속 200명이면
스레드 풀이 말라 서버 전체가 멈춘다. `AsyncConfig`에 풀이 **두 개**인 이유도 같다 — 슬랙 이벤트
처리가 SSE 릴레이를 방해하지 않도록 분리했다.

### 8-3. 세 가지 스트림 엔드포인트 비교

| 엔드포인트 | 무엇을 흘리나 | 이벤트 종류 | 파싱 난이도 |
| :--- | :--- | :--- | :--- |
| `/ai/drafts/stream` | 계획 초안을 **하루씩** | `day` / `done` / `error` | 중 (줄 단위 조립) |
| `/ai/chats/stream` | 산문 + 계획 변경(구형) | `token` / `plan` / `done` / `error` | **상 (구분자 상태머신)** |
| `/ai/agent/chats/stream` | 도구 호출 과정까지 전부 | `profile` `step` `tool_call` `tool_result` `token` `plan` `plan_refresh` `done` `error` | 하 (도구 스키마가 계약) |

> 에이전트 경로의 파싱이 **가장 쉽다**는 점이 이 프로젝트의 진화를 요약한다. 예전에는 모델이
> 산문 뒤에 `===PLAN===` 구분자와 JSON을 붙였고 서버가 그 문자열을 갈랐다. 이제는 **도구 스키마가
> 그 계약을 대신**하므로 "파싱 실패"라는 실패 모드 자체가 없다.

### 8-4. 어려운 문제 ① — 구분자가 조각 경계에 걸린다

구형 `/chats/stream`의 실제 난제다. LLM이 `===PLAN===`을 흘릴 때, 조각이 이렇게 쪼개져 온다:

```
   델타 1: "...끝났어요. ==="
   델타 2: "PLAN==={\"2026-01-01\":[...]}"
                 ↑
        여기서 잘렸다! "===" 만 보고는 구분자인지 알 수 없다
```

순진하게 짜면 `"...끝났어요. ==="` 를 그대로 화면에 뿌려 버린다. 해결책이 **홀드(hold)** 다:

```java
int hold = AiResponseParser.PLAN_SENTINEL.length() - 1;   // 구분자 길이 - 1
int safe = buf.replyPending.length() - hold;
if (safe > 0) {
    sseSend(emitter, Map.of("type", "token", "t", buf.replyPending.substring(0, safe)));
    buf.replyPending.delete(0, safe);   // 꼬리 (길이-1)글자는 남겨둔다
}
```

```
  버퍼: [ 안 전 한 부 분 ................ ][ 홀 드 구 간 ]
         └── 바로 내보낸다 ──────────────┘ └ 다음 조각과 합쳐서 판단 ┘
                                             (구분자 길이 - 1 글자)
```

**왜 "길이-1"인가:** 구분자가 아무리 심하게 잘려도 최대 (길이-1)글자만 앞 조각에 걸칠 수 있다.
그만큼만 붙들고 있으면 절대 놓치지 않는다.

### 8-5. 어려운 문제 ② — 하루가 완성될 때만 내보내기

초안 생성은 모델에게 "하루 = 한 줄(NDJSON)"로 쓰게 시킨다. 서버는 **개행이 나올 때마다** 한 줄을
떼어 `day` 이벤트로 보낸다:

```java
private void emitCompleteDays(StringBuilder buf, SseEmitter emitter) throws IOException {
    int nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
        String line = buf.substring(0, nl);
        buf.delete(0, nl + 1);      // 완성된 줄은 떼고
        emitDay(line, emitter);     // 꼬리는 버퍼에 남긴다
    }
}
```

```
  LLM 델타:  {"date":"2026-01-01","tasks":[ ... ]}\n{"date":"2026-01-02",...
                                                  ↑
                          여기까지 완성 → Day1 카드가 화면에 즉시 그려진다
                                                     ↑ 이건 아직 미완성 → 버퍼에 보관
```

덕분에 사용자는 14일치가 다 나올 때까지 기다리지 않고 **Day1부터 하나씩** 채워지는 걸 본다.

### 8-6. 어려운 문제 ③ — 프론트에서도 같은 문제가 반복된다

`db_service.js`의 `consumeSse`는 TCP 조각을 다시 조립한다. **네트워크는 `data: {...}\n\n` 단위로
도착한다고 보장하지 않는다.**

```javascript
buffer += decoder.decode(value, { stream: true });

let sep;
while ((sep = buffer.indexOf('\n\n')) >= 0) {   // 완성된 이벤트만
  const rawEvent = buffer.slice(0, sep);
  buffer = buffer.slice(sep + 2);               // 꼬리는 남긴다
  ...
  onEvent(JSON.parse(jsonStr));
}
```

| 계층 | 조각의 경계 | 완성 신호 | 남기는 것 |
| :--- | :--- | :--- | :--- |
| LLM → 서버 | 토큰 델타 | `\n` (초안) / 구분자 (대화) | 미완성 줄·홀드 구간 |
| 서버 → 브라우저 | TCP 패킷 | `\n\n` | 미완성 이벤트 |

> **같은 모양의 문제가 두 계층에서 반복된다.** "스트림에서 완성된 단위만 떼어내고 꼬리는
> 버퍼에 남긴다" — 스트리밍 코드를 읽을 때 이 패턴을 찾으면 절반은 이해한 것이다.
>
> `EventSource`라는 브라우저 내장 API가 있지만 **POST를 못 한다.** 이 앱은 대화 이력을 body로
> 보내야 해서 `fetch` + `ReadableStream`으로 직접 파싱한다.

### 8-7. 어려운 문제 ④ — `plan`과 `plan_refresh`를 왜 나눴나

에이전트가 계획을 바꾸는 방법은 **두 가지**이고, 프론트의 대응이 정반대여야 한다.

```
  ① update_plan_tasks (초안 수정)          ② carry_over_tasks (이월)
     서버에 아직 저장 안 함                    서버가 이미 저장함
            │                                        │
            ▼                                        ▼
     {"type":"plan","tasks":{...}}         {"type":"plan_refresh","planId":12}
            │                                        │
            ▼                                        ▼
     프론트가 초안으로 채택                   프론트가 서버에서 다시 읽음
     → 디바운스 PUT이 나중에 저장              → PUT을 보내지 않는다
```

**둘을 구분하지 않으면?** 이월은 고정(CONFIRMED)된 계획에서도 일어나는데, 프론트가 그걸 초안으로
채택하면 뒤따르는 PUT이 **409 PLAN_LOCKED로 튕긴다.** 화면에는 반영됐는데 서버는 거부하는
불일치가 생긴다.

### 8-8. 에러 처리 — SSE는 HTTP 상태 코드를 바꿀 수 없다

```
  일반 API:   [요청] ──▶ [처리] ──▶ 429 Too Many Requests   ← 에러를 상태코드로
  SSE:        [요청] ──▶ 200 OK + 연결 열림 ──▶ ...조각... ──▶ ???
                          ↑
              여기서 이미 200을 보냈다. 나중에 실패해도 못 바꾼다!
```

그래서 SSE는 **`error` 이벤트를 흘려보낸다**(HTTP는 여전히 200):

```java
catch (BusinessException e) {
    trySend(sink, Map.of("type", "error", "m", e.getMessage()));
    emitter.complete();
}
```

| 경로 | 한도 초과 시 | 프론트가 보는 것 |
| :--- | :--- | :--- |
| 비스트리밍 (`/ai/chats`) | HTTP **429** + JSON | `err.code === 'AI_DAILY_LIMIT_EXCEEDED'` |
| 스트리밍 (`/*/stream`) | HTTP **200** + `error` 이벤트 | `evt.type === 'error'` |

`trySend`가 예외를 삼키는 것도 의도다 — 사용자가 이미 탭을 닫았다면 연결이 끊긴 상태고, 그때
에러를 보내려다 또 예외가 나면 로그만 더러워진다.

### 8-9. 4단 폴백 체인 — "화면은 절대 멈추지 않는다"

```
   ① 에이전트 스트리밍  (도구 호출 가능)
        │ 실패 (도구 미지원 모델 · 업스트림 오류 · 루프 상한)
        ▼
   ② 자유 대화 스트리밍  (도구 없음, 산문만)
        │ 실패 (토큰 0개)
        ▼
   ③ 비스트리밍 대화     (한 번에 받기)
        │ 실패 (API 키 없음 · 네트워크 단절)
        ▼
   ④ mock 응답          (서버 없이도 데모가 돌아간다)
```

**중요한 예외 하나:** 이미 산문을 일부 받았다면 **폴백하지 않는다.**

```javascript
if (replyText.trim()) {
  return { reply: replyText.trim(), ... };   // 받은 걸로 마감
}
const fallback = await streamChatWithCoach(...);  // 하나도 못 받았을 때만 폴백
```

답이 화면에 절반 그려졌다가 **다른 답으로 통째로 바뀌면** 사용자에게는 오류보다 더 이상하게
보이기 때문이다.

### 8-10. 콜백 세계와 블로킹 세계의 다리 — `CountDownLatch`

LangChain4j의 스트리밍 API는 **콜백** 방식이고(`onPartialResponse`, `onCompleteResponse`),
`streamCompletion`을 부르는 쪽은 **끝날 때까지 기다리는** 방식이다. 둘을 잇는 것이 이 코드다:

```java
CountDownLatch done = new CountDownLatch(1);
AtomicReference<Throwable> failure = new AtomicReference<>();

streamingChatModel.chat(request, new StreamingChatResponseHandler() {
    public void onPartialResponse(String delta) { onDelta.accept(delta); }   // 조각 도착
    public void onCompleteResponse(ChatResponse r) { usage.set(...); done.countDown(); } // 끝
    public void onError(Throwable e) { failure.compareAndSet(null, e); done.countDown(); }
});

done.await();   // ★ 여기서 멈춰 서서 기다린다
```

```
   [sseExecutor 스레드]              [LangChain4j 내부 스레드]
          │                                  │
    done.await() 에서                  onPartialResponse ──▶ emitter.send()
      멈춰서 대기                       onPartialResponse ──▶ emitter.send()
          │                            onCompleteResponse
          │◀────── countDown() ───────────┘
          ▼
    사용량 로그 기록 후 반환
```

**`AtomicReference` 두 개가 필요한 이유:** 서로 다른 스레드가 쓰고 읽는 값이라 평범한 변수로는
안전하지 않다. `compareAndSet(null, e)`은 **첫 번째 에러만** 기록한다 — 나중 에러가 원인을
덮어쓰지 않게.

---

## 9. 상태 관리 — 백엔드

### 9-1. 상태에도 종류가 있다

"상태"라는 말이 한 가지가 아니다. 이 서버는 **수명이 다른 네 가지**를 구분해 다룬다.

| 종류 | 사는 곳 | 수명 | 예시 | 서버 재시작하면 |
| :--- | :--- | :--- | :--- | :--- |
| **영속 상태** | PostgreSQL | 영원 | 계획·회고·포인트 원장 | 살아남음 ✅ |
| **트랜잭션 상태** | DB 트랜잭션 | 수 ms | 차감 중인 잔액 | 롤백 |
| **프로세스 상태** | 자바 메모리 | 프로세스 수명 | AI 호출 횟수, 초안 대화 세션 | 사라짐 ⚠️ |
| **요청 상태** | 메서드 지역변수 | 요청 1회 | `AgentContext` | 사라짐 |

**"사라져도 되는가"를 먼저 정하고 자리를 고른다.**

| 왜 이 자리인가 | 근거 |
| :--- | :--- |
| 포인트 원장 → DB | 돈이다. 한 줄이라도 잃으면 불변식이 깨진다 |
| AI 호출 횟수 → 메모리 | "오늘 이 프로세스가 얼마 썼나"만 알면 된다. 단일 컨테이너라 갈라질 일이 없고, DB 쓰기를 늘리지 않는 게 이득 |
| 계획 생성 한도 → **DB(감사 이력)** | 계획을 삭제해도 그날 횟수가 돌아오면 안 된다 → 삭제를 살아남아야 하므로 메모리로는 부족 |
| 초안 대화 세션 → 메모리 + TTL 2시간 | 끊긴 대화가 메모리에 쌓이지 않게 |

> 같은 "일일 한도"인데 하나는 메모리, 하나는 DB다. **규칙이 요구하는 수명이 다르기 때문**이다.

### 9-2. 이중 저장소 — 같은 계약, 다른 구현

모든 저장소에 구현이 **두 개**씩 있다.

```
                    ┌─────────────────────────┐
                    │  PlanRepository (계약)   │   ← 서비스는 이것만 안다
                    └───────────┬─────────────┘
              ┌─────────────────┴─────────────────┐
              ▼                                   ▼
   @Profile("!postgres")                  @Profile("postgres")
   InMemoryPlanRepository                 JdbcPlanRepository
   ConcurrentHashMap                      PostgreSQL + JSONB
   (로컬 개발 · 단위 테스트)                 (배포)
```

| 항목 | 인메모리 | JDBC |
| :--- | :--- | :--- |
| 원자성을 얻는 방법 | `computeIfPresent` (키 단위 잠금) | `SELECT ... FOR UPDATE` + 트랜잭션 |
| 실패하면 | **되돌릴 수 없다** ⚠️ | 롤백된다 ✅ |
| 그래서 지켜야 할 규칙 | **검사를 모든 변경 앞에** | 순서 자유(롤백이 받쳐줌) |
| 속도 | 매우 빠름 | 네트워크 왕복 |

**이 차이가 실제 버그를 만들었다.** 목표 예치에서 "예치 행 등록 → 잔액 차감" 순서로 짰더니,
JDBC는 차감 실패 시 등록도 롤백됐지만 **인메모리는 "돈은 안 냈는데 예치는 걸린" 행이 남았다.**
그 행은 나중에 정산되면서 **낸 적 없는 돈을 돌려준다.** 그래서 잔액 검사를 등록보다 앞으로
옮겼다.

### 9-3. `mutate()` — 원자 구간을 함수로 감싸기

읽고-고치고-쓰는 사이에 남이 끼어들지 못하게, 세 단계를 **한 함수 안에** 넣는다.

```java
Plan updated = planRepository.mutate(id, current -> {
    if (!owner.equals(current.owner()))          throw PLAN_NOT_FOUND;       // 가드
    if (!current.statusOrDraft().canTransitionTo(target)) throw INVALID_STATUS_TRANSITION;
    return applyTransition(current, target);                                  // 변형
});
```

```
   ❌ 흔한 방식                         ✅ mutate 방식
   plan = repo.findById(id)             repo.mutate(id, current -> {
   if (plan.status != DRAFT) throw          검사 ─┐
   plan.status = CONFIRMED                  변형  ├─ 이 구간에 아무도 못 들어온다
   repo.save(plan)                          반환 ─┘
        ↑                                });
   여기 사이가 열려 있다
   (남이 먼저 고정해 버리면?)
```

| 구현 | 원자성의 정체 |
| :--- | :--- |
| 인메모리 | `store.computeIfPresent(id, ...)` — 같은 키에 대해 한 번에 한 스레드만 |
| JDBC | `SELECT ... FOR UPDATE` 로 행 잠금 → 커밋까지 유지 |

**둘 다 "가드가 예외를 던지면 아무것도 변하지 않는다"는 같은 계약을 지킨다.** 서비스 코드는
어느 프로필인지 몰라도 된다.

### 9-4. 소유자 판정은 단 한 지점에서

```java
// OwnerArgumentResolver
if (bearer != null) {
    return authRepository.findUserIdByToken(hash(bearer))
            .orElseThrow(() -> new BusinessException(AUTH_TOKEN_INVALID));  // 401
}
return OwnerGuestId.resolve(webRequest.getHeader("X-Guest-Id"));
```

```
   요청 헤더
     ├─ Authorization: Bearer xxx  → 세션 조회 → 로그인 사용자 ID
     │                             (세션 만료면 401, 게스트로 조용히 강등 ❌)
     └─ X-Guest-Id: guest-abc123   → 게스트 ID 그대로
                                          │
                                          ▼
                        컨트롤러의 @Owner String owner 파라미터
```

**만료된 세션을 게스트로 조용히 강등하지 않는 이유:** 사용자는 로그인 상태라고 믿는데 데이터는
게스트 보관함에 쌓인다. 어느 쪽에도 온전한 기록이 남지 않는 최악의 결과다. 401을 던지면 프론트가
저장된 auth를 지우고 명시적으로 게스트로 복귀한다.

### 9-5. 파생 상태는 저장하지 않는다

계산으로 얻을 수 있는 값을 필드로 들면, **원본과 어긋날 수 있는 상태가 하나 늘어난다.**

| 값 | 저장하나? | 이유 |
| :--- | :--- | :--- |
| `AgentContext.profile()` | ❌ 계산 | 입력이 `status` 하나뿐 — 저장하면 둘이 어긋날 수 있다 |
| `Plan.conditionKey()` | ❌ 계산 | (카테고리, 기간)의 순수 함수. 기간이 바뀌어도 동기화 코드 없이 저절로 맞는다 |
| `PlanResponse.progress` | ❌ 계산 | `countAllTasks()` 결과 |
| `point_wallets.balance` | ⚠️ **저장(캐시)** | 원장 합계가 진실. 매번 SUM 하면 느려서 캐시로 둔다 — 대신 **불변식 ②로 매번 검증 가능** |

> 잔액만 예외인데, 그것도 "캐시"라고 못 박고 **검증 쿼리를 문서에 남겨 두었다.** 어긋나면
> 즉시 알 수 있는 상태만 중복해서 들고 있는다.

### 9-6. `AgentContext` — 유일하게 "가변"인 상태

이 저장소의 도메인 객체는 거의 전부 `record`(불변)인데 `AgentContext`만 가변 클래스다.

```java
public void applyTasks(Map<String, Object> tasks) {   // 도구가 계획을 고치면 누적
    this.currentTasks = tasks;
    this.planChanged = true;
}
```

**왜:** 한 번의 대화에서 도구가 여러 번 계획을 고칠 수 있다(3일차 수정 → 5일차 추가). 그때마다
**"직전까지 반영된 계획"을 다음 도구가 봐야 한다.** 루프는 단일 스레드 순차 실행이라 동기화도
필요 없다.

```
  turn 1: update_plan_tasks(3일차) ──▶ context.applyTasks(...) ─┐
  turn 2: update_plan_tasks(5일차) ◀── 여기서 3일차 수정본을 본다 ┘
                  │
                  ▼
  루프 끝: {"type":"plan", tasks: 최종본} 을 한 번만 내보낸다
```

**소유권 주의 (보안):** `owner`는 컨트롤러가 헤더에서 해석한 값이고, **어떤 도구도 `owner`를
인자로 받지 않는다.** 모델이 준 값을 소유자로 쓰면 남의 계획에 접근할 수 있기 때문이다.

---

## 10. 상태 관리 — 프론트엔드

### 10-1. `useState` vs `useRef` — 화면을 다시 그리느냐

React 초보가 가장 많이 헷갈리는 지점이고, 이 코드베이스는 둘을 엄격히 나눠 쓴다.

| | `useState` | `useRef` |
| :--- | :--- | :--- |
| 값이 바뀌면 | **화면을 다시 그린다** | 아무 일도 안 일어난다 |
| 쓰는 곳 | 사용자에게 보이는 것 | 타이머 ID, 플래그, "지난 값" |
| 예시 | `messages`, `draftChecklist` | `syncTimerRef`, `dirtyRef`, `aliveRef` |

```
   [useState]  값 변경 ──▶ 리렌더 ──▶ 화면 갱신
   [useRef]    값 변경 ──▶ (조용히 보관)
```

**타이머 ID를 `useState`로 두면?** 타이머를 걸 때마다 화면 전체가 다시 그려진다. 600ms마다
깜빡이는 앱이 된다.

### 10-2. `chat_coach.jsx`의 상태 지도

이 파일 하나에 `useState`가 40개 넘는다. 무질서해 보이지만 **역할별 묶음**이 있다.

| 묶음 | 대표 상태 | 하는 일 |
| :--- | :--- | :--- |
| 대화 | `messages`, `inputValue`, `isTyping` | 말풍선과 입력창 |
| 계획(라이브) | `draftChecklist`, `slots`, `activePlanId` | **화면이 보고 있는 계획** |
| 에이전트 추적 | `activeSteps`, `expandedTrace` | 도구 호출 과정 패널 |
| 서버 스냅샷 | `savedPlans`, `todayDashboard` | 서버에서 읽어온 목록 |
| 회고 | `reflections`, `reflectionDrafts` | 저장된 회고 / 입력 중인 회고 |
| 부가 기능 | `knowledgeDocs`, `deposit` | 자료·예치 패널 |

**핵심 구분 하나:** `reflections`(서버에 저장된 것)와 `reflectionDrafts`(입력 중인 것)를 나눈다.
합치면 타이핑 도중에 서버 응답이 도착해 **사용자가 쓰던 글을 덮어쓴다.**

### 10-3. `usePlanSync` — 자동 저장의 심장

화면(`chat_coach.jsx`)은 타이머나 플래그를 직접 만지지 않는다. 전부 이 훅 안에 있다.

```javascript
const syncTimerRef      = useRef(null);   // 디바운스 타이머 ID
const dirtyRef          = useRef(null);   // 아직 서버에 못 보낸 최신 변경
const lastSyncedRef     = useRef(null);   // 서버에 있다고 아는 내용(JSON 문자열)
const archivePendingRef = useRef(false);  // 보관 실패 → 재시도 대기
const aliveRef          = useRef(true);   // 화면이 아직 살아있나
```

| ref | 없으면 생기는 일 |
| :--- | :--- |
| `syncTimerRef` | 체크박스를 10번 누르면 PUT이 10번 나간다 |
| `dirtyRef` | 전환·리셋 직전의 변경이 유실된다 |
| `lastSyncedRef` | 서버와 똑같은 내용을 계속 다시 보낸다(no-op PUT) |
| `archivePendingRef` | 서버가 잠깐 죽었을 때 만든 계획이 영영 저장 안 된다 |
| `aliveRef` | 떠난 화면이 뒤늦게 데이터를 만들어낸다 |

### 10-4. 디바운스 — 연타를 한 번으로

```
  체크박스 클릭:  ●    ●  ●        ●                      (4번)
                 │    │  │        │
  타이머:        ├600─╳  ╳        ├──600ms──▶ PUT 1회 ✅
                      ↑  ↑
                 이전 타이머 취소하고 새로 건다
```

```javascript
if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
syncTimerRef.current = setTimeout(() => {
    syncTimerRef.current = null;
    syncActivePlan({ recreateIfMissing: true });
}, SYNC_DEBOUNCE_MILLIS);   // 600ms
```

**600ms인 이유:** 사람이 연달아 체크하는 간격보다는 길고, "저장 안 됐나?" 하고 불안해지기에는
짧은 값이다.

> **이 `useEffect`는 cleanup에서 타이머를 지우지 않는다.** 보통 React에서는 지우는 게 정석이지만,
> 여기서는 계획 전환 같은 의존성 변경에 타이머가 취소되면 **대기 중인 변경이 유실된다.** 대신
> 전환·리셋은 `syncActivePlan()`으로 먼저 즉시 보내고(flush), 삭제는 `cancelPendingSync()`로
> 명시적으로 취소한다.

### 10-5. 좀비 계획 — 삭제한 게 되살아나는 버그

```
   사용자: 계획 삭제 클릭
        │
        ▼
   DELETE /plans/12  ──▶ 서버에서 삭제됨 ✅
        │
        │   그런데 600ms 전에 걸어둔 타이머가 아직 살아있다면?
        ▼
   PUT /plans/12  ──▶ 404 PLAN_NOT_FOUND
        │
        ▼
   "어? 없네. 작업 잃으면 안 되니까 새로 만들자"
        │
        ▼
   POST /plans  ──▶ 방금 지운 계획이 부활 🧟
```

그래서 삭제 경로는 **먼저 대기 중인 동기화를 취소한다**:

```javascript
const cancelPendingSync = () => {
  if (syncTimerRef.current) { clearTimeout(syncTimerRef.current); syncTimerRef.current = null; }
  dirtyRef.current = null;
  archivePendingRef.current = false;
};
```

그리고 화면을 떠날 때의 동기화는 `recreateIfMissing: false`로 부른다 — **떠나는 계획이 이미
삭제됐어도 새로 만들지 않는다.**

| 상황 | `recreateIfMissing` | 이유 |
| :--- | :---: | :--- |
| 백그라운드 자동 저장 | `true` | 다른 탭에서 지웠거나 서버 재시작 — 작업을 잃지 않게 |
| 계획 전환·리셋 직전 | `false` | 떠나는 계획을 되살리면 고아 데이터가 생긴다 |

### 10-6. `aliveRef` — 떠난 화면은 서버를 건드리지 않는다

```javascript
useEffect(() => {
  aliveRef.current = true;             // ★ 마운트마다 되올린다
  return () => {
    aliveRef.current = false;
    if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
  };
}, []);
```

```
   사용자가 탭을 닫음 / 컴포넌트 언마운트
          │
          │   하지만 3초 전 시작한 스트리밍 응답이 지금 도착한다면?
          ▼
   if (!aliveRef.current) return;   ← 여기서 전부 차단
```

**`aliveRef.current = true`를 마운트마다 되올리는 이유:** React StrictMode는 개발 중에
`마운트 → cleanup → 재마운트`를 일부러 한 번 더 돈다. 되올리지 않으면 cleanup이 남긴 `false`가
그대로 남아 **모든 갱신이 영구히 막힌다.**

### 10-7. 낙관적 업데이트와 되돌리기

체크박스를 누르면 **서버 응답을 기다리지 않고 먼저 화면을 바꾼다**(낙관적 업데이트). 빠르게
느껴지지만, 서버가 거부하면 되돌려야 한다.

```
   체크 클릭 ──▶ 화면 즉시 체크 ✅ ──▶ 600ms 후 PUT
                                          │
                        ┌─────────────────┼─────────────────┐
                        ▼                 ▼                 ▼
                    200 OK           409 PLAN_LOCKED   409 PAST_TASK_LOCKED
                        │                 │                 │
                     그대로          대기 변경 폐기     alert + 서버 상태로 되돌리기
                                    (서버가 진실)      (낙관 반영된 체크를 지운다)
```

```javascript
if (err.code === 'PAST_TASK_LOCKED') {
  window.alert('지난 날짜의 완료 체크는 변경할 수 없어요. 화면을 서버 상태로 되돌립니다.');
  const fresh = await fetchPlan(pending.id);
  if (aliveRef.current) applyServerPlan(fresh);   // ← 되돌리기
  return;
}
```

**언제 이 일이 생기나:** 자정을 넘겨 열어둔 화면. 어제였던 "오늘"이 이제 "어제"가 됐는데 화면의
체크박스는 아직 활성화돼 있다. 서버가 막고 프론트가 되돌린다.

| 서버 거부 코드 | 프론트 대응 | 재시도? |
| :--- | :--- | :---: |
| `PLAN_LOCKED` | 대기 변경 폐기(로그만) | ❌ 계속 409 |
| `PAST_TASK_LOCKED` | alert + 서버에서 다시 읽어 되돌림 | ❌ |
| `PLAN_NOT_FOUND` | 경우에 따라 새로 보관 | 조건부 |
| `PLAN_LIMIT_EXCEEDED` / `PLAN_DAILY_LIMIT_EXCEEDED` | 안내 말풍선만 | ❌ 소용없음 |
| 기타(네트워크 등) | `dirtyRef`에 되돌려 놓고 다음 변경 때 재시도 | ✅ |

### 10-8. 경합 차단 — `lastSyncedRef`를 setState보다 먼저

서버 응답을 화면에 반영할 때, **순서가 중요하다.**

```javascript
const applyServerPlan = (plan) => {
  const { slots, draftChecklist } = fromPlanResponse(plan);
  lastSyncedRef.current = JSON.stringify(toPlanPayload(draftChecklist));  // ★ 먼저
  dirtyRef.current = null;
  setDraftChecklist(draftChecklist);                                      //   나중
  setSlots(slots);
};
```

**만약 setState를 먼저 하면?** `draftChecklist`가 바뀌는 순간 자동 동기화 `useEffect`가 깨어나
"서버와 다르네!" 하고 **방금 서버에서 받은 내용을 서버로 되돌려 보낸다.** `lastSyncedRef`를 먼저
채워 두면 그 effect가 "이미 같다"고 판단하고 조용히 넘어간다.

### 10-9. 서버가 진실 원천 — 세 갈래 대응

| 충돌 상황 | 누가 이기나 | 코드 |
| :--- | :--- | :--- |
| 프론트가 보낸 상태 vs DB의 상태 | **DB** | `AgentRunner.buildContext`가 DB에서 직접 읽음 |
| 프론트의 낙관적 체크 vs 서버 거부 | **서버** | `applyServerPlan(fresh)`로 되돌림 |
| 프론트의 계산 vs 서버의 계산 | **서버** | 완료율·병합·진행률 모두 서버 소유 |

```
  나쁜 사용자: "이 계획은 DRAFT입니다" 라고 요청 본문에 적어 보냄
                        │
                        ▼
  AgentRunner.buildContext() → planService.getPlan(planId, owner) ← DB에서 직접 읽음
                        │
                        ▼
              실제 상태는 CONFIRMED → 수정 도구 안 줌 → 공격 실패 ✅
```

---

## 11. 필수 함수 총정리

| # | 함수 | 파일 | 한 줄 역할 |
| :-: | :--- | :--- | :--- |
| ① | `PlanStatus.allows*()` | `plan/entity/PlanStatus.java` | "지금 이거 해도 돼?"의 유일한 정답지 |
| ② | `PlanStatus.canTransitionTo()` | 〃 | 상태 전이표 — 초안→완료 같은 건너뛰기 차단 |
| ③ | `Plan.countAllTasks()` | `plan/entity/Plan.java` | 완료율의 유일한 계산기 (4곳이 공유) |
| ④ | `AgentToolRegistry.find()` | `ai/agent/` | AI 권한의 문지기 — 두 번 검사 |
| ⑤ | `AgentRunner.runLoop()` | `ai/service/` | 도구 루프 4바퀴 + 강제 종료 |
| ⑥ | `PointLedgerRepository.post()` | `points/repository/` | 두 줄 동시 기록, 중복은 DB가 튕김 |
| ⑦ | `PointWalletRepository.debit()` | 〃 | 기표 먼저 → 잔액 나중 (순서가 규칙) |
| ⑧ | `ChallengeRepository.join()` | `challenge/repository/` | 3단 원자 작업 — 정원 초과 원천 차단 |
| ⑨ | `PlanRewardService.rewardFor()` | `points/service/` | 완주 보상 = 기간×10 (50~200) |
| ⑩ | `PlanDeposit.refundFor()` | `points/entity/` | 환급 = 예치금 × 달성률 (내림) |
| ⑪ | `PlanDepositService.settleOnTerminal()` | `points/service/` | 정산의 단일 입구, 정산권 경쟁 |
| ⑫ | `BigramLexicalSearcher.dice()` | `knowledge/search/` | 글자 2개 겹침으로 한국어 검색 |
| ⑬ | `KnowledgeChunker.split()` | `knowledge/service/` | 자료를 500자씩(100자 겹침) 조각내기 |
| ⑭ | `AiRateLimiter.tryAcquire()` | `ai/usage/` | 올려보고 넘치면 되돌리기 |
| ⑮ | `AiService.feedDelta()` | `ai/service/` | 구분자가 조각 경계에 걸리는 문제 해결(홀드) |
| ⑯ | `OpenRouterClient.streamCompletion()` | `ai/client/` | 콜백 세계 ↔ 블로킹 세계의 다리 |
| ⑰ | `consumeSse()` | `frontend/src/db_service.js` | TCP 조각을 SSE 이벤트로 재조립 |
| ⑱ | `PlanRepository.mutate()` | `plan/repository/` | 검사·변형·저장을 한 원자 구간에 |
| ⑲ | `usePlanSync` 의 5개 ref | `frontend/src/use_plan_sync.js` | 자동 저장·유실 방지·좀비 방지 |
| ⑳ | `OwnerArgumentResolver` | `global/auth/` | 소유자 판정의 단 하나의 지점 |

---

## 12. 코드 전체를 관통하는 규칙

이 네 가지만 이해하면 나머지는 전부 응용이다.

| 규칙 | 뜻 | 나타나는 곳 |
| :--- | :--- | :--- |
| **① 판정을 쓰기 안으로** | "되나요?" 묻지 말고 "되면 해줘"를 한 문장으로 | 챌린지 정원 · 잔액 차감 · 정산권 · 중복 기표 · 호출 횟수 · `mutate` — **6곳 이상** |
| **② 규칙은 한 곳에만** | 같은 표를 두 파일에 적지 않는다 | `PlanStatus` 능력 플래그 · `countAllTasks` · `PointAccounts` 이름 규칙 · `OwnerArgumentResolver` |
| **③ 클라이언트를 믿지 않는다** | 완료율·상태·시각은 전부 서버가 다시 계산 | `AgentRunner.buildContext` · 회고 저장 · 정산 달성률 · 완료 토글의 날짜 해석 |
| **④ 조용한 실패를 만들지 않는다** | 이상하면 예외를 던지거나 알린다 | 원장 1행 삽입 → 예외 · 잘라낸 도구 호출 → 모델에게 통보 · 백업 업로드 실패 → 비정상 종료 |

### 규칙 ④가 특히 중요한 이유

```
   "조용한 성공"이 가장 위험한 실패다.

   백업 스크립트가 업로드에 실패했는데 exit 0을 반환하면?
        → cron 로그는 깨끗하다
        → 6개월 뒤 복구하려는 순간에야 백업이 하나도 없음을 안다

   원장이 한 줄만 들어갔는데 넘어가면?
        → 화면은 멀쩡하다
        → 불변식이 깨진 채로 영원히 남고, 원인 시점을 찾을 수 없다
```

그래서 이 저장소는 **애매하면 시끄럽게 실패하는 쪽**을 고른다.

---

## 더 읽을거리

| 궁금한 것 | 문서 |
| :--- | :--- |
| 전체 구조·ERD를 그림으로 | [DATA_FLOW.md](DATA_FLOW.md) |
| 동시성 — 틀린 구현이 깨지는 인터리빙과 대안 비교 | [CONCURRENCY.md](CONCURRENCY.md) |
| 에이전트 도구 카탈로그·토큰 계측 | [AGENT.md](AGENT.md) |
| 실제 모델로 도구 선택 정확도를 재는 법 | [EVAL.md](EVAL.md) |
| 버전별 진화 과정과 의도 | [EVOLUTION.md](EVOLUTION.md) |
| 배포 이후의 운영·장애 복구 | [OPERATIONS.md](OPERATIONS.md) |
