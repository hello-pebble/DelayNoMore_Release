// AI Slot-Filling 및 계획 생성 엔진
import { postAiDraft, getAiHealth } from "./db_service";
import { todayStr } from "./date_utils";


export const REQUIRED_SLOTS = {
  GOAL_NAME: 'goalName',
  DURATION: 'duration',
  DAILY_HOURS: 'dailyHours',
  CURRENT_LEVEL: 'currentLevel'
};

export const INITIAL_SLOTS = {
  goalName: '',
  duration: 0,
  dailyHours: 0,
  currentLevel: ''
};

// 현재 어떤 슬롯을 질문해야 하는지 리턴
export function getNextEmptySlot(slots) {
  if (!slots.goalName) return REQUIRED_SLOTS.GOAL_NAME;
  if (!slots.duration) return REQUIRED_SLOTS.DURATION;
  if (!slots.dailyHours) return REQUIRED_SLOTS.DAILY_HOURS;
  if (!slots.currentLevel) return REQUIRED_SLOTS.CURRENT_LEVEL;
  return null;
}

// 다음 질문 메시지 생성
export function getNextQuestion(slot) {
  switch (slot) {
    case REQUIRED_SLOTS.GOAL_NAME:
      return "반갑습니다! 미루기 습관을 타파할 AI 코치입니다. 🔥\n완벽한 맞춤형 계획을 짜기 위해 몇 가지 질문을 드릴게요.\n\n먼저 **어떤 목표**를 이루고 싶으신가요?\n*(예: 정보처리기사 실기 합격, React 기초 공부, 매일 아침 러닝하기)*";
    case REQUIRED_SLOTS.DURATION:
      return "멋진 목표군요! 이 계획을 **며칠 동안 실행**하고 싶으신가요?\n*(추천: 실행력 집중을 위해 3일 ~ 7일을 권장해요! 숫자로만 입력해 주세요)*";
    case REQUIRED_SLOTS.DAILY_HOURS:
      return "하루에 이 목표에 **투자할 수 있는 시간**은 평균 몇 시간인가요?\n*(숫자로만 입력해 주세요. 예: 2, 4 등)*";
    case REQUIRED_SLOTS.CURRENT_LEVEL:
      return "마지막 질문입니다! 이 목표에 대한 **현재 본인의 지식수준이나 상태**는 어떤가요?\n*(예: 완전 초보, 기본 개념만 아는 수준, 실전 유경험자)*";
    default:
      return "모든 정보가 준비되었습니다! 이제 최적의 하루 단위 캘린더 계획표를 작성해 드릴게요. 잠시만 기다려 주세요...";
  }
}

// 유저 메시지로부터 슬롯 값 추출 시도
export function parseUserMessage(message, currentSlot) {
  const trimmed = message.trim();

  switch (currentSlot) {
    case REQUIRED_SLOTS.GOAL_NAME:
      if (trimmed.length <= 1) {
        return { isValid: false, error: "목표를 조금 더 구체적으로 작성해 주세요!" };
      }
      return { value: trimmed, isValid: true };

    case REQUIRED_SLOTS.DURATION: {
      const days = parseInt(trimmed.replace(/[^0-9]/g, ''), 10);
      if (isNaN(days) || days <= 0 || days > 30) {
        return { isValid: false, error: "1일에서 30일 사이의 숫자로 입력해 주세요." };
      }
      return { value: days, isValid: true };
    }

    case REQUIRED_SLOTS.DAILY_HOURS: {
      const hours = parseInt(trimmed.replace(/[^0-9]/g, ''), 10);
      if (isNaN(hours) || hours <= 0 || hours > 24) {
        return { isValid: false, error: "1시간에서 24시간 사이의 숫자로 입력해 주세요." };
      }
      return { value: hours, isValid: true };
    }

    case REQUIRED_SLOTS.CURRENT_LEVEL:
      if (trimmed.length < 2) {
        return { isValid: false, error: "본인의 상태나 수준을 입력해 주세요!" };
      }
      return { value: trimmed, isValid: true };

    default:
      return { isValid: false };
  }
}

// 오늘(+offsetDays) 기준 YYYY-MM-DD — 로컬 날짜 유틸에 위임(단일 구현).
export function getFormattedDate(offsetDays = 0) {
  return todayStr(offsetDays);
}

// AI 기반 계획표 초안 생성 (백엔드 프록시 경유, 실패 시 mock 폴백)
export async function generateChecklistDraft(slots, refinementPrompt = '', onChunk = null, previousDraft = null) {
  const { goalName, duration, dailyHours, currentLevel } = slots;
  const startDate = getFormattedDate(0);
  const endDate = getFormattedDate(duration - 1);

  try {
    // 재수정이면 직전 초안의 태스크를 함께 넘겨, 백엔드가 assistant 턴으로 이어 붙여
    // "처음부터 다시"가 아니라 "직전 계획을 고쳐" 달라고 멀티턴으로 요청하게 한다.
    const resultJson = await postAiDraft({
      goalName,
      duration,
      dailyHours,
      currentLevel,
      refinementPrompt,
      previousTasks: previousDraft?.tasks || null
    });
    console.log("🟢 [Backend API] AI 계획 초안 수신 성공:", resultJson);

    // REST API이므로 onChunk에 완성된 응답을 한 번에 전달하여 UI 렌더링 지원
    if (onChunk) {
      onChunk(JSON.stringify(resultJson, null, 2));
    }

    return {
      id: `chk-${Date.now()}`,
      goalName,
      duration,
      dailyHours,
      currentLevel,
      tasks: resultJson.tasks,
      status: 'DRAFT',
      startDate,
      endDate,
      createdAt: new Date().toISOString()
    };

  } catch (error) {
    console.error("Failed to generate AI checklist draft via Backend API:", error);
    return generateMockChecklistDraft(slots, refinementPrompt);
  }
}

// 템플릿 기반 계획 생성 (Fallback용 — 백엔드/AI 미가용 시에도 데모 흐름이 끊기지 않게 한다)
export function generateMockChecklistDraft(slots, refinementPrompt = '') {
  const { goalName, duration, dailyHours, currentLevel } = slots;
  const tasks = {};

  // 기본 공부/운동 템플릿 구성
  const isWorkout = goalName.includes('운동') || goalName.includes('러닝') || goalName.includes('헬스') || goalName.includes('다이어트');

  const studyTemplates = [
    ["핵심 개념 파악하기", "기초 용어 정리 및 노트 작성", "1챕터 기본 예제 풀이"],
    ["핵심 내용 심화 학습", "관련 동영상 강의 시청", "요약본 보며 리마인드"],
    ["실전 예제 실습하기", "오류 디버깅 및 분석", "배운 내용 블로그/메모장에 정리"],
    ["기출 문제 또는 종합 실습 도전", "틀린 부분 오답 노트 작성", "부족한 파트 보충 학습"],
    ["전체 내용 최종 스크리닝", "핵심 암기 사항 재확인", "마무리 회고 및 스스로 피드백"]
  ];

  const workoutTemplates = [
    ["가벼운 스트레칭 및 웜업 10분", "목표 강도 운동 30분 진행", "수분 섭취 및 가벼운 폼롤러 마사지"],
    ["코어 운동 중심 단기 단련", "인터벌 트레이닝 20분", "근육 이완 스트레칭"],
    ["목표 세트 수 달성하기 (어제보다 강도 +5%)", "유산소 운동 30분 병행", "샤워 후 식단 기록"],
    ["전신 컨디셔닝 트레이닝", "정적 스트레칭 15분", "오늘 피로도 점검 및 기록"],
    ["가벼운 리커버리 러닝/조깅", "전신 폼롤러 스트레칭 20분", "계획 달성 축하 한마디"]
  ];

  const activeTemplate = isWorkout ? workoutTemplates : studyTemplates;

  // 리플래닝 피드백 키워드 파싱
  const isReduced = refinementPrompt.includes('줄여') || refinementPrompt.includes('적게') || refinementPrompt.includes('힘들어') || refinementPrompt.includes('야근');
  const isIncreased = refinementPrompt.includes('늘려') || refinementPrompt.includes('많이') || refinementPrompt.includes('부족');
  const skipWeekend = refinementPrompt.includes('주말') && (refinementPrompt.includes('쉬') || refinementPrompt.includes('빼'));

  for (let i = 0; i < duration; i++) {
    const targetDate = getFormattedDate(i);
    const dateObj = new Date();
    dateObj.setDate(dateObj.getDate() + i);
    const isSatOrSun = dateObj.getDay() === 0 || dateObj.getDay() === 6;

    let dayTasks = [];
    const templateIndex = i % activeTemplate.length;
    const baseTasks = activeTemplate[templateIndex];

    if (skipWeekend && isSatOrSun) {
      dayTasks = [
        { id: `t-${i}-rest`, content: "주말 휴식 및 리커버리 (미루지 않고 쉰 나에게 칭찬하기)", completed: false }
      ];
    } else {
      let count = baseTasks.length;
      if (isReduced) count = Math.max(1, count - 1);
      if (isIncreased) count = Math.min(5, count + 1);

      for (let j = 0; j < count; j++) {
        let content = baseTasks[j];

        // 커스텀 수정 키워드 반영
        if (isReduced) {
          content = `[라이트 세션] ${content} (부담 최소화)`;
        } else if (isIncreased) {
          content = `[딥 포커스] ${content} (+추가 확장 연습)`;
        }

        dayTasks.push({
          id: `t-${i}-${j}`,
          content: `${goalName}: ${content}`,
          completed: false
        });
      }
    }

    tasks[targetDate] = dayTasks;
  }

  return {
    id: `chk-${Date.now()}`,
    goalName,
    duration,
    dailyHours,
    currentLevel,
    tasks,
    status: 'DRAFT',
    startDate: getFormattedDate(0),
    endDate: getFormattedDate(duration - 1),
    createdAt: new Date().toISOString()
  };
}

// OpenRouter 연결 상태 점검 (백엔드 프록시 경유 — 키는 서버에만 존재해 브라우저 번들에 노출되지 않는다)
export async function checkOpenRouterConnection() {
  return getAiHealth();
}
