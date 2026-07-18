# DelayNoMore — 대화형 투두리스트 생성 데모

**🔗 라이브 데모: <http://delaynomoreapp.duckdns.org/>**
(현재 HTTP로 서비스 중이며, DB·로그인 연동 마무리 후 HTTPS를 적용할 예정입니다.)

> [DelayNoMore](https://github.com/hello-pebble/DelayNoMore)의 **"대화를 통해 투두리스트(하루 단위 실행 계획)를 생성하는"** 핵심 흐름만 떼어낸 최소 배포판입니다. 현재 버전: **v0.7.0**

AI 코치와의 대화로 하루 단위 계획(체크리스트)을 만들고 → 오늘 할 일을 실행하고 → "오늘 마무리" 회고로 하루를 닫습니다.
최종 목표는 **계획을 고정하는 순간, 체크리스트 완성 도우미 대신 그 목표의 전문 에이전트가 이어받는 것**입니다.

## 핵심 기능

- **슬롯필링 대화 → 계획 생성** — 목표·기간·시간·수준을 묻고 하루 단위 투두리스트 초안을 생성(SSE 실시간 스트리밍, API 키 없으면 mock 폴백).
- **자유 대화로 계획 보완** — 수정 요청·질문을 LLM이 구분해 처리하고, "계획 저장"으로 고정(확정)하면 수정 없이 실행에 집중.
- **여러 계획 보관 + 오늘 할 일** — 계획은 서버 보관함에 자동 저장(휘발성 데모 저장소)되고, 가운데 칸에 모든 계획의 오늘 항목이 모입니다.
- **일일 회고** — 자동 계산된 완료율에 체감 난이도·이유를 더해 하루를 마무리.
- **미완료 내일로 이동** — 오늘 못 끝낸 항목을 버튼 하나로 내일로 이월(고정 전 계획 전용).
- **계획 변경 이력** — 생성·수정·고정·완료 체크·회고·삭제가 언제, 어느 세션에서 일어났는지 계획별로 조회.

## 빠른 시작

```bash
# 백엔드 (포트 8080)
cd backend && OPENROUTER_API_KEY=<your_key> ./gradlew bootRun

# 프론트엔드 (포트 5173)
cd frontend && npm install && npm run dev
```

또는 단일 컨테이너로:

```bash
docker build -t delaynomore . && docker run -p 8080:8080 -e OPENROUTER_API_KEY=<your_key> delaynomore
```

`OPENROUTER_API_KEY`가 없어도 기동되며 mock 폴백으로 동작합니다. 자세한 내용은 [실행·배포 가이드](docs/DEPLOY.md)를 보세요.

## 문서

| 문서 | 내용 |
| :--- | :--- |
| [기능 상세](docs/FEATURES.md) | 화면 구성과 10가지 기능의 상세 동작 |
| [구조](docs/ARCHITECTURE.md) | 디렉토리 구조 · 기술 스택 · API 개요 |
| [발전 과정](docs/EVOLUTION.md) | v0.1.0부터의 버전별 다이어그램·의도 · 토큰 절감 증빙 · 버전 관리 정책 |
| [로드맵](docs/ROADMAP.md) | 다음 단계 6가지와 최종 목표(전문 에이전트 인계) |
| [실행·배포](docs/DEPLOY.md) | 로컬 실행 · 단일 컨테이너 배포 · 환경변수 |
| [CHANGELOG](CHANGELOG.md) | 버전별 상세 변경 이력 |
