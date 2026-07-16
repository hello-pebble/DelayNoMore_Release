// AI Slot-Filling 및 계획 생성 엔진
import { postAiDraft, postAiChat, streamAiChat, streamAiDraft, getAiHealth } from "./db_service";
import { todayStr, formatLocalDate, parseLocalDate } from "./date_utils";


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
      // 서버 검증(1~14일)과 동일한 상한 — 어긋나면 백엔드가 400을 주고 mock으로 새기 때문에 맞춘다.
      if (isNaN(days) || days <= 0 || days > 14) {
        return { isValid: false, error: "1일에서 14일 사이의 숫자로 입력해 주세요." };
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

// 초안 생성 스트리밍 — "하루 = 한 이벤트"로 도착하는 대로 체크리스트를 Day1부터 하나씩 채운다.
// onDay(partialDraft, dayCount)로 부분 계획을 넘겨 우측 패널이 점진적으로 그려지게 한다.
// 스트림이 실패하거나 한 건도 못 받으면 비스트리밍 generateChecklistDraft(→ 최종적으로 mock)로 폴백한다.
export async function streamChecklistDraft(slots, onDay) {
  const { goalName, duration, dailyHours, currentLevel } = slots;
  const startDate = getFormattedDate(0);
  const endDate = getFormattedDate(duration - 1);
  const tasks = {};
  let dayCount = 0;
  let streamError = null;

  // 지금까지 모인 날짜로 정렬된 draft 스냅샷을 만든다(Day 순서 보장).
  const snapshot = () => {
    const ordered = {};
    Object.keys(tasks).sort().forEach((k) => { ordered[k] = tasks[k]; });
    const dates = Object.keys(ordered);
    return {
      id: `chk-${Date.now()}`,
      goalName,
      duration: dates.length || duration,
      dailyHours,
      currentLevel,
      tasks: ordered,
      status: 'DRAFT',
      startDate,
      endDate: dates[dates.length - 1] || endDate,
      createdAt: new Date().toISOString()
    };
  };

  try {
    await streamAiDraft(
      { goalName, duration, dailyHours, currentLevel },
      (evt) => {
        if (evt.type === 'day' && evt.date && Array.isArray(evt.tasks)) {
          const items = evt.tasks
            .map((t, idx) => {
              const content = typeof t === 'string' ? t : t?.content;
              if (typeof content !== 'string' || !content.trim()) return null;
              return { id: `t-${evt.date}-${idx}`, content: content.trim(), completed: false };
            })
            .filter(Boolean);
          if (items.length > 0) {
            tasks[evt.date] = items;
            dayCount += 1;
            if (onDay) onDay(snapshot(), dayCount);
          }
        } else if (evt.type === 'error') {
          streamError = evt.m || 'draft stream error';
        }
      }
    );

    if (dayCount === 0) {
      throw new Error(streamError || 'no days streamed');
    }
    return snapshot();
  } catch (error) {
    console.error("Streaming draft failed, falling back to non-stream/mock:", error);
    // 일부라도 이미 받은 게 있으면 그걸로 마감(부분 계획), 아니면 비스트리밍 재생성으로 폴백.
    if (dayCount > 0) {
      return snapshot();
    }
    return generateChecklistDraft(slots);
  }
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

    // 응답에서 화면에 그릴 수 있는 tasks를 뽑아 검증한다. 모델이 {tasks:{...}}가 아니라
    // 날짜맵을 최상위로 주거나(=resultJson 자체), 빈 객체를 주는 경우까지 감안한다.
    // 유효한 tasks가 없으면 화이트스크린 대신 mock 폴백으로 데모 흐름을 지킨다.
    const tasks = normalizeTasks(resultJson?.tasks) || normalizeTasks(resultJson);
    if (!tasks) {
      console.warn("AI 초안 응답에 유효한 tasks가 없어 mock 폴백으로 대체합니다:", resultJson);
      return generateMockChecklistDraft(slots, refinementPrompt);
    }

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
      tasks,
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

// LLM이 돌려준 다양한 형태를 "날짜 → 할 일 배열" 맵으로 강제 변환한다.
// 모델마다 스키마가 달라서(예: {tasks:{날짜:[...]}}, {plan:[{date,tasks}]}, 최상위 배열 등)
// 한 형태만 기대하면 화면이 안 그려진다. 여기서 흔한 변형을 모두 흡수한다.
function coerceToDateMap(raw) {
  if (!raw || typeof raw !== 'object') return null;

  let node = raw;

  // 1) {plan|days|schedule|checklist|tasks: [ ... ]} 처럼 배열을 감싼 래퍼면 그 배열을 꺼낸다.
  if (!Array.isArray(node)) {
    const arrayKey = ['plan', 'days', 'schedule', 'checklist', 'tasks', 'items']
      .find((k) => Array.isArray(node[k]));
    if (arrayKey) {
      node = node[arrayKey];
    } else if (node.tasks && typeof node.tasks === 'object') {
      // 2) {tasks: {날짜: [...]}} → 안쪽 맵 사용
      node = node.tasks;
    }
  }

  // 3) [{date, tasks}, ...] 형태의 배열 → 날짜맵으로 변환
  if (Array.isArray(node)) {
    const map = {};
    node.forEach((item, i) => {
      if (!item || typeof item !== 'object') return;
      const date = item.date || item.day || item.name || `Day ${i + 1}`;
      const list = item.tasks || item.items || item.todos || item.list || [];
      map[String(date)] = list;
    });
    return Object.keys(map).length ? map : null;
  }

  // 4) 이미 {날짜: [...]} 맵 형태
  if (typeof node === 'object') return node;
  return null;
}

// 화면에 그릴 수 있는 (날짜 → [{id, content, completed}]) 맵으로 검증/정규화한다.
// 유효한 항목이 하나도 없으면 null을 반환해 계획 갱신을 막는다.
function normalizeTasks(rawTasks) {
  const dateMap = coerceToDateMap(rawTasks);
  if (!dateMap || typeof dateMap !== 'object' || Array.isArray(dateMap)) return null;
  const dates = Object.keys(dateMap);
  if (dates.length === 0) return null;

  const normalized = {};
  for (const date of dates) {
    const list = dateMap[date];
    if (!Array.isArray(list)) continue;
    const items = list
      .map((task, idx) => {
        const content = typeof task === 'string' ? task : task?.content;
        if (typeof content !== 'string' || !content.trim()) return null;
        return {
          id: task?.id || `t-${date}-${idx}`,
          content: content.trim(),
          completed: task?.completed === true
        };
      })
      .filter(Boolean);
    if (items.length > 0) {
      normalized[date] = items;
    }
  }
  return Object.keys(normalized).length > 0 ? normalized : null;
}

// 계획 patch(변경된 날짜만: "날짜 → 문자열 배열", 값이 null이면 삭제)를 현재 계획에 병합한다.
// 백엔드가 전체 계획을 재전송하지 않고 바뀐 부분만 보내므로(토큰 절약), 병합은 프론트에서 한다.
// 문자열을 {id, content, completed} 객체로 복원하고 날짜순으로 다시 정렬한다.
function applyPlanPatch(currentTasks, patch) {
  if (!patch || typeof patch !== 'object') return null;
  const next = { ...(currentTasks || {}) };

  for (const [date, value] of Object.entries(patch)) {
    if (value === null) {
      // 기간 단축 등 — 해당 날짜 삭제
      delete next[date];
      continue;
    }
    if (!Array.isArray(value)) continue;
    const items = value
      .map((task, idx) => {
        const content = typeof task === 'string' ? task : task?.content;
        if (typeof content !== 'string' || !content.trim()) return null;
        return { id: `t-${date}-${idx}`, content: content.trim(), completed: false };
      })
      .filter(Boolean);
    if (items.length > 0) {
      next[date] = items;
    } else {
      delete next[date];
    }
  }

  // 날짜 키를 오름차순으로 다시 정렬해 Day 순서를 맞춘다.
  const ordered = {};
  Object.keys(next).sort().forEach((k) => { ordered[k] = next[k]; });
  return Object.keys(ordered).length > 0 ? ordered : null;
}

// patch를 draft에 적용해 갱신된 draft를 만든다(기간 연장/단축 시 duration/endDate 재계산).
function draftWithPatch(draft, patch) {
  const tasks = applyPlanPatch(draft?.tasks, patch);
  if (!tasks) return null;
  const dates = Object.keys(tasks).sort();
  return { ...draft, tasks, duration: dates.length, endDate: dates[dates.length - 1] || draft.endDate };
}

// 초안 생성 이후의 자유 대화(스트리밍) — 산문 reply는 토큰이 오는 대로 onToken(누적 텍스트)으로
// 흘려보내고, 계획 변경분(patch)은 스트림 끝에서 병합한다. 반환: { reply, updatedDraft }.
// 스트림이 아예 실패(토큰 0개)하면 비스트리밍 → mock 순으로 폴백한다.
export async function streamChatWithCoach(slots, draft, history, message, onToken) {
  let replyText = '';
  let patch = null;
  let streamError = null;

  try {
    await streamAiChat(
      {
        goalName: slots.goalName,
        duration: slots.duration,
        dailyHours: slots.dailyHours,
        currentLevel: slots.currentLevel,
        tasks: draft?.tasks || {},
        history,
        message
      },
      (evt) => {
        if (evt.type === 'token') {
          replyText += evt.t || '';
          if (onToken) onToken(replyText);
        } else if (evt.type === 'plan') {
          patch = evt.patch || null;
        } else if (evt.type === 'error') {
          streamError = evt.m || 'stream error';
        }
      }
    );

    // 토큰을 하나도 못 받았는데 에러였다면 폴백으로 넘긴다.
    if (streamError && !replyText.trim() && !patch) {
      throw new Error(streamError);
    }

    if (patch) {
      const updatedDraft = draftWithPatch(draft, patch);
      if (updatedDraft) {
        return {
          reply: replyText.trim() || "요청하신 내용을 반영해 계획을 수정했습니다. 오른쪽 체크리스트를 확인해 주세요.",
          updatedDraft
        };
      }
    }

    if (replyText.trim()) {
      return { reply: replyText.trim(), updatedDraft: null };
    }
    throw new Error("empty stream reply");
  } catch (error) {
    console.error("Streaming chat failed, falling back to non-stream/mock:", error);
    // 이미 일부 산문을 보여준 상태에서 폴백하면 답이 중복 갱신되니, 받은 게 있으면 그걸로 마감한다.
    if (replyText.trim()) {
      return { reply: replyText.trim(), updatedDraft: patch ? draftWithPatch(draft, patch) : null };
    }
    return chatWithCoach(slots, draft, history, message);
  }
}

// 초안 생성 이후의 자유 대화(비스트리밍 폴백) — LLM이 의도(수정/질문/불명확)를 판단한다.
// 반환: { reply: string, updatedDraft: draft | null }
export async function chatWithCoach(slots, draft, history, message) {
  try {
    const result = await postAiChat({
      goalName: slots.goalName,
      duration: slots.duration,
      dailyHours: slots.dailyHours,
      currentLevel: slots.currentLevel,
      tasks: draft?.tasks || {},
      history,
      message
    });
    console.log("🟢 [Backend API] AI 대화 응답 수신 성공:", result);

    const reply = typeof result?.reply === 'string' && result.reply.trim()
      ? result.reply.trim()
      : null;

    if (result?.planUpdated === true) {
      const updatedDraft = draftWithPatch(draft, result.patch);
      if (updatedDraft) {
        return {
          reply: reply || "요청하신 내용을 반영해 계획을 수정했습니다. 오른쪽 체크리스트를 확인해 주세요.",
          updatedDraft
        };
      }
      // planUpdated라고 했지만 patch가 깨진 경우 — 기존 계획을 보존하고 재요청을 유도한다.
      return {
        reply: "계획을 수정하다가 형식 오류가 발생했어요. 같은 요청을 한 번만 다시 보내주시겠어요?",
        updatedDraft: null
      };
    }

    if (reply) {
      return { reply, updatedDraft: null };
    }
    throw new Error("empty reply from /api/v1/ai/chats");
  } catch (error) {
    console.error("Failed to chat via Backend API:", error);
    return mockChatWithCoach(slots, draft, message);
  }
}

// 대화 폴백 (백엔드/AI 미가용 시) — 아는 키워드면 mock 재생성으로 반영하고,
// 모르는 요청이면 "반영했다"고 거짓말하는 대신 예시와 함께 되묻는다.
export function mockChatWithCoach(slots, draft, message) {
  const text = (message || '').trim();

  // "기간을 늘려줘"류는 하루 분량 조정과 다른 요청이므로 먼저 구분한다 —
  // 기존 Day는 건드리지 않고 새 Day만 이어붙인다. 숫자가 있으면 그만큼, 없으면 3일 기본.
  const extendDuration = text.includes('기간') && (text.includes('늘려') || text.includes('연장'));
  if (extendDuration && draft?.tasks) {
    const numMatch = text.match(/(\d+)\s*일/);
    const addDays = numMatch ? Math.max(1, Math.min(14, parseInt(numMatch[1], 10))) : 3;
    const extended = extendMockChecklistDays(draft, slots, addDays);
    return {
      reply: `(오프라인 모드) 기간을 ${addDays}일 늘렸습니다. 오른쪽 체크리스트에서 확인해 보세요.`,
      updatedDraft: extended
    };
  }

  const isReduced = text.includes('줄여') || text.includes('적게') || text.includes('힘들어') || text.includes('야근');
  const isIncreased = text.includes('늘려') || text.includes('많이') || text.includes('부족');
  const skipWeekend = text.includes('주말') && (text.includes('쉬') || text.includes('빼'));

  if (isReduced || isIncreased || skipWeekend) {
    const refined = generateMockChecklistDraft(slots, text);
    const changed = skipWeekend
      ? "주말을 휴식일로 바꿨습니다"
      : isReduced
        ? "하루 분량을 한 단계 줄였습니다"
        : "하루 분량을 한 단계 늘렸습니다";
    return {
      reply: `(오프라인 모드) ${changed}. 오른쪽 체크리스트에서 확인해 보세요.`,
      updatedDraft: refined
    };
  }

  return {
    reply: "(오프라인 모드) 지금은 AI 연결 없이 동작 중이라 정해진 패턴의 요청만 반영할 수 있어요.\n예: \"주말은 빼줘\", \"일정을 줄여줘\", \"일정을 늘려줘\"",
    updatedDraft: null
  };
}

// mock 계획 생성/연장이 공유하는 하루 템플릿(공부/운동). 목표명에 운동 관련 키워드가
// 있으면 운동 템플릿을, 아니면 공부 템플릿을 순환해서 쓴다.
const STUDY_TEMPLATES = [
  ["핵심 개념 파악하기", "기초 용어 정리 및 노트 작성", "1챕터 기본 예제 풀이"],
  ["핵심 내용 심화 학습", "관련 동영상 강의 시청", "요약본 보며 리마인드"],
  ["실전 예제 실습하기", "오류 디버깅 및 분석", "배운 내용 블로그/메모장에 정리"],
  ["기출 문제 또는 종합 실습 도전", "틀린 부분 오답 노트 작성", "부족한 파트 보충 학습"],
  ["전체 내용 최종 스크리닝", "핵심 암기 사항 재확인", "마무리 회고 및 스스로 피드백"]
];

const WORKOUT_TEMPLATES = [
  ["가벼운 스트레칭 및 웜업 10분", "목표 강도 운동 30분 진행", "수분 섭취 및 가벼운 폼롤러 마사지"],
  ["코어 운동 중심 단기 단련", "인터벌 트레이닝 20분", "근육 이완 스트레칭"],
  ["목표 세트 수 달성하기 (어제보다 강도 +5%)", "유산소 운동 30분 병행", "샤워 후 식단 기록"],
  ["전신 컨디셔닝 트레이닝", "정적 스트레칭 15분", "오늘 피로도 점검 및 기록"],
  ["가벼운 리커버리 러닝/조깅", "전신 폼롤러 스트레칭 20분", "계획 달성 축하 한마디"]
];

function isWorkoutGoal(goalName) {
  return goalName.includes('운동') || goalName.includes('러닝') || goalName.includes('헬스') || goalName.includes('다이어트');
}

// 투자 시간에 비례한 하루 할 일 개수(백엔드 tasksPerDayRange와 동일 기준).
function mockTaskCountForHours(dailyHours) {
  const dh = Number(dailyHours) || 2;
  if (dh <= 1) return 2;
  if (dh === 2) return 3;
  if (dh <= 4) return 4;
  if (dh <= 6) return 5;
  return 6;
}

// mock 폴백에서 "기간 늘려줘" 요청을 처리한다 — 기존 Day는 그대로 두고,
// 마지막 날짜 다음부터 addDays일치 새 Day만 만들어 이어붙인다.
function extendMockChecklistDays(draft, slots, addDays) {
  const existingTasks = draft?.tasks || {};
  const existingDates = Object.keys(existingTasks).sort();
  const lastDate = parseLocalDate(existingDates[existingDates.length - 1]) || new Date();

  const { goalName } = slots;
  const activeTemplate = isWorkoutGoal(goalName) ? WORKOUT_TEMPLATES : STUDY_TEMPLATES;
  const count = mockTaskCountForHours(slots.dailyHours);

  const newTasks = { ...existingTasks };
  for (let i = 1; i <= addDays; i++) {
    const d = new Date(lastDate);
    d.setDate(d.getDate() + i);
    const dateStr = formatLocalDate(d);
    const baseTasks = activeTemplate[(existingDates.length + i - 1) % activeTemplate.length];
    const dayTasks = [];
    for (let j = 0; j < count; j++) {
      const content = j < baseTasks.length ? baseTasks[j] : `${baseTasks[j % baseTasks.length]} (심화 반복)`;
      dayTasks.push({ id: `t-ext-${dateStr}-${j}`, content: `${goalName}: ${content}`, completed: false });
    }
    newTasks[dateStr] = dayTasks;
  }

  const allDates = Object.keys(newTasks).sort();
  return {
    ...draft,
    tasks: newTasks,
    duration: allDates.length,
    endDate: allDates[allDates.length - 1]
  };
}

// 템플릿 기반 계획 생성 (Fallback용 — 백엔드/AI 미가용 시에도 데모 흐름이 끊기지 않게 한다)
export function generateMockChecklistDraft(slots, refinementPrompt = '') {
  const { goalName, duration, dailyHours, currentLevel } = slots;
  const tasks = {};

  const activeTemplate = isWorkoutGoal(goalName) ? WORKOUT_TEMPLATES : STUDY_TEMPLATES;

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
      // 하루 할 일 개수를 투자 시간에 비례시킨다(백엔드 프롬프트와 동일한 기준).
      const dh = Number(dailyHours) || 2;
      let count = dh <= 1 ? 2 : dh === 2 ? 3 : dh <= 4 ? 4 : dh <= 6 ? 5 : 6;
      if (isReduced) count = Math.max(1, count - 1);
      if (isIncreased) count = Math.min(6, count + 1);

      for (let j = 0; j < count; j++) {
        // 템플릿(3개)보다 많이 필요하면 앞 항목을 순환하며 '심화'로 채운다.
        let content = j < baseTasks.length ? baseTasks[j] : `${baseTasks[j % baseTasks.length]} (심화 반복)`;

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

// 계획을 복사/다운로드용 순수 텍스트로 직렬화한다. 마크다운 체크박스 표기를 써서
// 노트 앱 등에 붙여넣어도 바로 읽히게 한다.
export function formatChecklistAsText(checklist) {
  if (!checklist) return '';
  const lines = [];
  lines.push(`# ${checklist.goalName}`);
  lines.push(`기간 ${checklist.duration}일 · 하루 ${checklist.dailyHours}시간 · ${checklist.currentLevel}`);
  lines.push('');

  Object.entries(checklist.tasks || {}).forEach(([date, taskList], idx) => {
    lines.push(`## Day ${idx + 1} · ${date}`);
    (Array.isArray(taskList) ? taskList : []).forEach((task) => {
      lines.push(`- [${task.completed ? 'x' : ' '}] ${task.content}`);
    });
    lines.push('');
  });

  lines.push('— DelayNoMore로 생성한 계획입니다.');
  return lines.join('\n');
}
