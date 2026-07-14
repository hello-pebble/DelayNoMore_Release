import React, { useState, useEffect, useRef } from 'react';
import { Send, Copy, Download, Check, Save, RotateCcw, CalendarPlus } from 'lucide-react';
import {
  REQUIRED_SLOTS,
  getNextEmptySlot,
  getNextQuestion,
  parseUserMessage,
  generateChecklistDraft,
  chatWithCoach,
  formatChecklistAsText,
  INITIAL_SLOTS
} from '../ai_engine';

// 계획 저장(로컬 세션 유지용 — 서버 저장 없음)에 쓰는 localStorage 키.
const SAVED_PLAN_KEY = 'delaynomore:savedPlan';

// 슬롯필링 질문에 제공하는 빠른 선택지. 자유 입력도 계속 가능하다.
// 라벨 텍스트를 그대로 전송한다(기간/시간은 파서가 숫자만 추출).
const DURATION_PRESETS = ['3일', '5일', '7일'];
const DAILY_HOURS_PRESETS = ['1시간', '2시간', '4시간', '6시간'];
const LEVEL_PRESETS = ['완전 초보', '기본 개념은 아는 수준', '실전 경험 있음'];

// 계획 저장 — 서버/DB 없이 브라우저 localStorage에만 보관한다(이 브라우저에서만 유지).
// 프라이빗 모드 등 localStorage가 막힌 환경에서도 앱이 죽지 않게 모두 try/catch로 감싼다.
function saveSavedPlan(slots, draftChecklist) {
  try {
    localStorage.setItem(SAVED_PLAN_KEY, JSON.stringify({ slots, draftChecklist, savedAt: Date.now() }));
    return true;
  } catch {
    return false;
  }
}

function loadSavedPlan() {
  try {
    const raw = localStorage.getItem(SAVED_PLAN_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed?.slots || !parsed?.draftChecklist) return null;
    return parsed;
  } catch {
    return null;
  }
}

function clearSavedPlan() {
  try {
    localStorage.removeItem(SAVED_PLAN_KEY);
  } catch {
    // 무시 — 저장이 애초에 안 된 환경이면 지울 것도 없다.
  }
}

// 마운트 시 최초 상태를 계산한다 — 저장된 계획이 있으면 복원, 없으면 첫 질문으로 시작.
// useState의 지연 초기화 함수로 써서(컴포넌트 몸체가 아니라) "effect 안에서 setState"를
// 피하고, StrictMode가 두 번 호출해도 순수 함수라 안전하다.
function buildInitialState() {
  const saved = loadSavedPlan();
  if (saved) {
    return {
      slots: saved.slots,
      draftChecklist: saved.draftChecklist,
      currentSlot: null,
      messages: [
        {
          id: 'bot-restored',
          sender: 'bot',
          text: `이전에 저장한 "${saved.draftChecklist?.goalName || '계획'}"을 불러왔습니다. 오른쪽 체크리스트를 확인해 주세요. 계속 대화로 수정하거나, 아래 "처음부터 다시 만들기"로 새 계획을 시작할 수 있어요.`
        }
      ]
    };
  }
  const nextSlot = getNextEmptySlot(INITIAL_SLOTS);
  return {
    slots: { ...INITIAL_SLOTS },
    draftChecklist: null,
    currentSlot: nextSlot,
    messages: [{ id: 'bot-init-first', sender: 'bot', text: getNextQuestion(nextSlot) }]
  };
}

// 봇 말풍선 텍스트를 타이핑하듯 점진적으로 드러낸다("스트리밍처럼" 보이는 효과).
// 실제 토큰 스트리밍(SSE)이 아닌 프론트 연출이므로, 사람이 인지할 수 있는 속도
// (약 60자/초)로 재생하고 깜빡이는 커서로 진행 중임을 보여준다.
// (이전 구현은 약 190자/초라 사실상 즉시 표시로 보였다.)
// 클릭하면 즉시 전체를 보여준다(기다리기 싫은 사용자를 위한 스킵).
// 부모가 text가 바뀔 때마다 key={text}로 이 컴포넌트를 리마운트시켜
// placeholder → 최종 답변 전환 시 처음부터 다시 재생되게 한다.
function TypedBotText({ text }) {
  const [count, setCount] = useState(0);
  useEffect(() => {
    let i = 0;
    const id = setInterval(() => {
      i += 2;
      setCount(Math.min(i, text.length));
      if (i >= text.length) clearInterval(id);
    }, 32);
    return () => clearInterval(id);
  }, [text]);

  const done = count >= text.length;
  return (
    <span onClick={() => setCount(text.length)} style={{ cursor: done ? 'default' : 'pointer' }}>
      {text.slice(0, count)}
      {!done && (
        <span
          style={{
            display: 'inline-block',
            width: '2px',
            height: '1em',
            background: 'currentColor',
            marginLeft: '2px',
            verticalAlign: 'text-bottom',
            animation: 'blink 1s infinite'
          }}
        />
      )}
    </span>
  );
}

// 고유 ID 생성 유틸리티 (중복 Key 방지)
const generateUniqueId = (prefix = 'msg') => {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
};

// navigator.clipboard는 보안 컨텍스트(HTTPS/localhost)에서만 동작한다.
// 이 프로젝트의 데모 배포는 평문 HTTP라 그 API가 없을 수 있어, 구형 execCommand로 폴백한다.
async function copyTextToClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 아래 폴백으로 계속 진행
    }
  }
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

const quickReplyButtonStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '4px',
  padding: '6px 10px',
  fontSize: '12px',
  border: '1px solid var(--border)',
  borderRadius: '999px',
  background: 'var(--bg-card)',
  color: 'var(--text-main)',
  cursor: 'pointer'
};

const exportButtonStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '4px',
  padding: '5px 9px',
  fontSize: '12px',
  border: '1px solid var(--border)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
  color: 'var(--text-main)',
  cursor: 'pointer'
};

export default function ChatCoach() {
  // 지연 초기화 함수 하나로 최초 상태(저장된 계획 복원 또는 첫 질문)를 한 번만 계산한다.
  const [initial] = useState(buildInitialState);
  const [slots, setSlots] = useState(initial.slots);
  const [messages, setMessages] = useState(initial.messages);
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const [draftChecklist, setDraftChecklist] = useState(initial.draftChecklist);
  const [currentSlot, setCurrentSlot] = useState(initial.currentSlot);
  const [isThinking, setIsThinking] = useState(false);
  const [elapsedTime, setElapsedTime] = useState(0);
  const [thinkingStatus, setThinkingStatus] = useState('');
  const [copyFeedback, setCopyFeedback] = useState(null); // null | 'ok' | 'error'

  const chatEndRef = useRef(null);
  const thinkingTimerRef = useRef(null);
  const statusTimerRef = useRef(null);

  // 스크롤 자동으로 아래로 내리기
  const scrollToBottom = () => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, isTyping, isThinking]);

  // 생각 중 타이머 해제 cleanup
  useEffect(() => {
    return () => {
      if (thinkingTimerRef.current) clearInterval(thinkingTimerRef.current);
      if (statusTimerRef.current) clearInterval(statusTimerRef.current);
    };
  }, []);

  const startThinking = () => {
    setIsThinking(true);
    setElapsedTime(0);

    const statuses = [
      "요청 사항 분석 중...",
      "지연 행동 유형 분류 중...",
      "CBT 맞춤 극복 전략 수립 중...",
      "일자별 집중 분량 설계 중...",
      "하루 가용 시간 분배 중...",
      "일정표 조립 진행 중..."
    ];
    setThinkingStatus(statuses[0]);

    // 1초 주기 타이머
    thinkingTimerRef.current = setInterval(() => {
      setElapsedTime(prev => prev + 1);
    }, 1000);

    // 1.5초 주기 멘트 순환
    let index = 1;
    statusTimerRef.current = setInterval(() => {
      setThinkingStatus(statuses[index % statuses.length]);
      index++;
    }, 1500);
  };

  const stopThinking = () => {
    setIsThinking(false);
    if (thinkingTimerRef.current) {
      clearInterval(thinkingTimerRef.current);
      thinkingTimerRef.current = null;
    }
    if (statusTimerRef.current) {
      clearInterval(statusTimerRef.current);
      statusTimerRef.current = null;
    }
  };

  // 실제 전송 로직 — 입력창 텍스트뿐 아니라 빠른 선택 버튼(기간/시간 프리셋,
  // "기간 늘리기" 등)에서도 재사용한다.
  const sendMessage = async (rawText) => {
    const userText = (rawText || '').trim();
    if (!userText) return;

    const userMsgId = generateUniqueId('user');

    // 1. 유저 메시지 추가
    setMessages((prev) => [...prev, { id: userMsgId, sender: 'user', text: userText }]);
    setInputValue('');
    setIsTyping(true);

    try {
      // 초안 생성 이후에는 자유 대화 모드 — LLM이 의도(수정/질문/불명확)를 직접 판단한다.
      // 무조건 재생성하고 "반영했습니다"라고 답하던 이전 방식을 대체한다.
      if (draftChecklist) {
        startThinking();

        // 최근 대화 이력(방금 보낸 메시지 제외)을 role/content 형태로 전달해
        // "반영 안됐는데?" 같은 맥락 의존 발화도 이해할 수 있게 한다.
        const history = messages.slice(-10).map((msg) => ({
          role: msg.sender === 'user' ? 'user' : 'assistant',
          content: msg.text
        }));

        const { reply, updatedDraft } = await chatWithCoach(slots, draftChecklist, history, userText);

        stopThinking();
        if (updatedDraft) {
          setDraftChecklist(updatedDraft);
          // 기간 연장/단축처럼 날짜 개수가 바뀌었을 수 있으니 슬롯도 함께 맞춘다
          // (다음 요청의 [Goal] Duration이 실제 계획과 어긋나지 않게).
          if (updatedDraft.duration && updatedDraft.duration !== slots.duration) {
            setSlots((prev) => ({ ...prev, duration: updatedDraft.duration }));
          }
        }
        setMessages((prev) => [
          ...prev,
          { id: generateUniqueId('bot'), sender: 'bot', text: reply }
        ]);
        return;
      }

      // 일반 슬롯필링 대화 상황인 경우
      const parseResult = parseUserMessage(userText, currentSlot);

      if (!parseResult.isValid) {
        // 입력 유효성 실패 -> 경고 메시지
        const replyText = `⚠️ ${parseResult.error || "입력 형식이 맞지 않습니다. 다시 한 번 적어주세요."}`;
        setMessages((prev) => [
          ...prev,
          {
            id: generateUniqueId('bot'),
            sender: 'bot',
            text: replyText
          }
        ]);
        return;
      }

      // 슬롯 값 업데이트
      const updatedSlots = { ...slots, [currentSlot]: parseResult.value };
      setSlots(updatedSlots);

      const nextSlot = getNextEmptySlot(updatedSlots);

      if (nextSlot) {
        // 다음 빈 슬롯이 있으면 질문
        setCurrentSlot(nextSlot);
        const replyText = getNextQuestion(nextSlot);
        setMessages((prev) => [
          ...prev,
          {
            id: generateUniqueId('bot'),
            sender: 'bot',
            text: replyText
          }
        ]);
        return;
      }

      // 모든 슬롯 완비 -> 드래프트 생성
      const botMsgId = generateUniqueId('stream');
      setMessages((prev) => [
        ...prev,
        {
          id: botMsgId,
          sender: 'bot',
          text: "입력하신 정보로 맞춤 계획표를 만들고 있습니다. 잠시만 기다려 주세요."
        }
      ]);

      startThinking();

      let hasStartedStreaming = false;
      const checklist = await generateChecklistDraft(updatedSlots, '', () => {
        if (!hasStartedStreaming) {
          hasStartedStreaming = true;
          stopThinking();
        }
        setMessages((prev) =>
          prev.map(msg =>
            msg.id === botMsgId
              ? { ...msg, text: `맞춤 계획표를 조립하고 있습니다...` }
              : msg
          )
        );
      });

      stopThinking();
      setDraftChecklist(checklist);
      const replyText = "계획 초안을 완성했습니다. 오른쪽 체크리스트를 확인해 주세요. 수정하고 싶은 부분이 있으면 채팅으로 알려주세요.";
      setMessages((prev) =>
        prev.map(msg =>
          msg.id === botMsgId
            ? { ...msg, text: replyText }
            : msg
        )
      );
    } catch (error) {
      console.error("AI Response error:", error);
      stopThinking();
    } finally {
      setIsTyping(false);
    }
  };

  const handleSendMessage = (e) => {
    e.preventDefault();
    sendMessage(inputValue);
  };

  // 슬롯필링 중 숫자 프리셋 버튼 클릭 — 입력창에 채우는 대신 바로 전송한다.
  const sendQuickReply = (value) => {
    if (isTyping) return;
    sendMessage(String(value));
  };

  // 할 일 완료 토글 (로컬 상태만 — 서버 저장 없음, 데모 세션 동안만 유지)
  const toggleTask = (date, taskId) => {
    setDraftChecklist((prev) => {
      if (!prev) return prev;
      const dayTasks = prev.tasks?.[date];
      if (!Array.isArray(dayTasks)) return prev;
      return {
        ...prev,
        tasks: {
          ...prev.tasks,
          [date]: dayTasks.map((t) => (t.id === taskId ? { ...t, completed: !t.completed } : t))
        }
      };
    });
  };

  const handleCopyPlan = async () => {
    const text = formatChecklistAsText(draftChecklist);
    const ok = await copyTextToClipboard(text);
    setCopyFeedback(ok ? 'ok' : 'error');
    setTimeout(() => setCopyFeedback(null), 1800);
  };

  const handleDownloadPlan = () => {
    const text = formatChecklistAsText(draftChecklist);
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const safeName = (draftChecklist?.goalName || 'plan').replace(/[\\/:*?"<>|]/g, '').slice(0, 30);
    a.href = url;
    a.download = `delaynomore-${safeName}.txt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const [saveFeedback, setSaveFeedback] = useState(null); // null | 'ok' | 'error'

  const handleSavePlan = () => {
    const ok = saveSavedPlan(slots, draftChecklist);
    setSaveFeedback(ok ? 'ok' : 'error');
    setTimeout(() => setSaveFeedback(null), 1800);
  };

  // 처음부터 다시 만들기 — 저장된 계획도 함께 지워 다음 방문 시 되살아나지 않게 한다.
  // 요청이 진행 중일 때 리셋하면 나중에 도착하는 응답이 새 상태를 덮어써버릴 수 있어 막는다.
  const handleResetPlan = () => {
    if (isTyping) return;
    clearSavedPlan();
    setSlots({ ...INITIAL_SLOTS });
    setDraftChecklist(null);
    setInputValue('');
    const nextSlot = getNextEmptySlot(INITIAL_SLOTS);
    setCurrentSlot(nextSlot);
    setMessages([
      { id: generateUniqueId('bot-reset'), sender: 'bot', text: getNextQuestion(nextSlot) }
    ]);
  };

  // 전체 기간 늘리기 — 기존 자유 대화 파이프라인(의도 판단/재생성)을 그대로 재사용한다.
  const EXTEND_DAYS = 3;
  const handleExtendDuration = () => {
    if (isTyping) return;
    sendMessage(`전체 기간을 ${EXTEND_DAYS}일 더 늘려줘`);
  };

  // 현재 질문의 빠른 답변 선택지 — 하단 구석이 아니라 대화 흐름 안(마지막 말풍선
  // 바로 아래)에 렌더링해, 질문을 읽는 시선 위치에서 바로 선택할 수 있게 한다.
  const slotQuickReplies =
    draftChecklist || isTyping ? [] :
    currentSlot === REQUIRED_SLOTS.DURATION ? DURATION_PRESETS :
    currentSlot === REQUIRED_SLOTS.DAILY_HOURS ? DAILY_HOURS_PRESETS :
    currentSlot === REQUIRED_SLOTS.CURRENT_LEVEL ? LEVEL_PRESETS :
    [];

  // === 왼쪽: 대화 패널 ===
  const chatPanel = (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%' }}>
      <div style={{ padding: '10px 16px', borderBottom: '1px solid var(--border)', fontWeight: 600, fontSize: '14px', flexShrink: 0 }}>
        AI 코치와 대화
      </div>

      {/* 대화 영역 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px', display: 'flex', flexDirection: 'column', gap: '10px', minHeight: 0 }}>
        {messages.map((msg) => (
          <div
            key={msg.id}
            className="animate-fade-in"
            style={{
              display: 'flex',
              justifyContent: msg.sender === 'user' ? 'flex-end' : 'flex-start'
            }}
          >
            <div
              style={{
                maxWidth: '80%',
                padding: '9px 13px',
                borderRadius: '12px',
                lineHeight: '1.5',
                whiteSpace: 'pre-wrap',
                fontSize: '14px',
                background: msg.sender === 'user' ? 'var(--bubble-user)' : 'var(--bubble-bot)',
                color: msg.sender === 'user' ? '#ffffff' : 'var(--text-main)'
              }}
            >
              {msg.sender === 'bot' ? <TypedBotText key={msg.text} text={msg.text} /> : msg.text}
            </div>
          </div>
        ))}

        {/* AI 생각 중 표시 */}
        {isThinking && (
          <div className="animate-fade-in" style={{ display: 'flex' }}>
            <div style={{
              padding: '9px 13px',
              borderRadius: '12px',
              background: 'var(--bubble-bot)',
              fontSize: '13px',
              color: 'var(--text-muted)'
            }}>
              분석 중입니다... ({elapsedTime}초) · {thinkingStatus}
            </div>
          </div>
        )}

        {/* 입력 대기 표시 */}
        {isTyping && !isThinking && (
          <div style={{ display: 'flex' }}>
            <div style={{ padding: '9px 13px', borderRadius: '12px', background: 'var(--bubble-bot)', display: 'flex', gap: '4px' }}>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'blink 1.2s infinite' }}></span>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'blink 1.2s infinite', animationDelay: '0.2s' }}></span>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'blink 1.2s infinite', animationDelay: '0.4s' }}></span>
            </div>
          </div>
        )}

        {/* 현재 질문의 빠른 답변 — 마지막 봇 말풍선 아래(대화 흐름 안)에 표시 */}
        {slotQuickReplies.length > 0 && (
          <div className="animate-fade-in" style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
            {slotQuickReplies.map((label) => (
              <button
                key={label}
                type="button"
                onClick={() => sendQuickReply(label)}
                style={quickReplyButtonStyle}
              >
                {label}
              </button>
            ))}
          </div>
        )}

        <div ref={chatEndRef} />
      </div>

      {/* 계획 생성 후 빠른 동작 — 저장 / 처음부터 다시 만들기 / 전체 기간 늘리기 */}
      {draftChecklist && (
        <div style={{ padding: '0 16px 10px', display: 'flex', gap: '6px', flexWrap: 'wrap', flexShrink: 0 }}>
          <button type="button" onClick={handleSavePlan} style={quickReplyButtonStyle}>
            <Save size={12} />
            {saveFeedback === 'ok' ? '저장됨' : saveFeedback === 'error' ? '저장 실패' : '계획 저장'}
          </button>
          <button type="button" onClick={handleExtendDuration} disabled={isTyping} style={quickReplyButtonStyle}>
            <CalendarPlus size={12} />
            기간 +{EXTEND_DAYS}일
          </button>
          <button type="button" onClick={handleResetPlan} disabled={isTyping} style={quickReplyButtonStyle}>
            <RotateCcw size={12} />
            처음부터 다시 만들기
          </button>
        </div>
      )}

      {/* 입력바 */}
      <form
        onSubmit={handleSendMessage}
        style={{
          padding: '12px 16px',
          borderTop: '1px solid var(--border)',
          display: 'flex',
          gap: '8px',
          flexShrink: 0
        }}
      >
        <input
          type="text"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder={draftChecklist ? "수정 요청이나 질문을 입력해 주세요..." : "대답을 입력해 주세요..."}
          style={{
            flex: 1,
            padding: '10px 12px',
            background: 'var(--bg-card)',
            border: '1px solid var(--border)',
            borderRadius: '8px',
            color: 'var(--text-main)',
            outline: 'none',
            fontSize: '14px'
          }}
        />
        <button
          type="submit"
          style={{
            width: '42px',
            height: '42px',
            border: 'none',
            borderRadius: '8px',
            background: 'var(--primary)',
            color: '#ffffff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: 'pointer',
            flexShrink: 0
          }}
        >
          <Send size={16} />
        </button>
      </form>
    </div>
  );

  // 전체 진행률(완료/전체 개수) — 헤더 요약과 진행 바에 함께 쓴다.
  const allTasks = draftChecklist
    ? Object.values(draftChecklist.tasks || {}).flatMap((list) => (Array.isArray(list) ? list : []))
    : [];
  const completedCount = allTasks.filter((t) => t.completed).length;
  const totalCount = allTasks.length;

  // === 오른쪽: 체크리스트 패널 ===
  const checklistPanel = (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%', background: 'var(--bg-panel)' }}>
      <div style={{
        padding: '10px 16px',
        borderBottom: '1px solid var(--border)',
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between'
      }}>
        <span style={{ fontWeight: 600, fontSize: '14px' }}>생성된 체크리스트</span>
        {draftChecklist && (
          <div style={{ display: 'flex', gap: '6px' }}>
            <button
              type="button"
              onClick={handleCopyPlan}
              title="계획을 텍스트로 복사"
              style={{
                ...exportButtonStyle,
                ...(copyFeedback === 'error' ? { color: 'var(--warning)', borderColor: 'var(--warning)' } : {})
              }}
            >
              {copyFeedback === 'ok' && <Check size={13} />}
              {copyFeedback !== 'ok' && <Copy size={13} />}
              {copyFeedback === 'ok' ? '복사됨' : copyFeedback === 'error' ? '복사 실패' : '복사'}
            </button>
            <button
              type="button"
              onClick={handleDownloadPlan}
              title="계획을 .txt 파일로 다운로드"
              style={exportButtonStyle}
            >
              <Download size={13} />
              다운로드
            </button>
          </div>
        )}
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '16px', minHeight: 0 }}>
        {!draftChecklist ? (
          <div style={{ color: 'var(--text-muted)', fontSize: '14px', textAlign: 'center', marginTop: '40px', lineHeight: 1.6 }}>
            왼쪽 대화에서 목표 · 기간 · 하루 투자 시간 · 현재 수준을<br />
            입력하면 이곳에 계획표가 생성됩니다.
          </div>
        ) : (
          <div className="animate-fade-in" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            {/* 요약 헤더 */}
            <div style={{ borderBottom: '1px solid var(--border)', paddingBottom: '12px' }}>
              <h2 style={{ fontSize: '18px', fontWeight: 700, marginBottom: '6px' }}>{draftChecklist.goalName}</h2>
              <div style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '8px' }}>
                기간 {draftChecklist.duration}일 · 하루 {draftChecklist.dailyHours}시간 · {draftChecklist.currentLevel}
              </div>
              {totalCount > 0 && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <div style={{ flex: 1, height: '6px', borderRadius: '3px', background: 'var(--border)', overflow: 'hidden' }}>
                    <div
                      style={{
                        height: '100%',
                        borderRadius: '3px',
                        background: 'var(--primary)',
                        width: `${Math.round((completedCount / totalCount) * 100)}%`,
                        transition: 'width 0.3s ease'
                      }}
                    />
                  </div>
                  <span style={{ fontSize: '12px', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                    {completedCount}/{totalCount} 완료
                  </span>
                </div>
              )}
            </div>

            {/* 일차별 미션 (tasks가 비정상이어도 화면이 죽지 않게 방어) — Day마다 살짝 늦게 나타나 순차 생성 느낌을 준다 */}
            {Object.entries(draftChecklist.tasks || {}).map(([date, taskList], idx) => (
              <div
                key={date}
                className="animate-fade-in"
                style={{
                  background: 'var(--bg-card)',
                  border: '1px solid var(--border)',
                  borderRadius: '8px',
                  padding: '12px 14px',
                  animationDelay: `${Math.min(idx, 8) * 70}ms`,
                  animationFillMode: 'backwards'
                }}
              >
                <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--primary)', marginBottom: '8px' }}>
                  Day {idx + 1} · {date}
                </div>
                <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                  {(Array.isArray(taskList) ? taskList : []).map((task) => (
                    <li
                      key={task.id}
                      onClick={() => toggleTask(date, task.id)}
                      style={{
                        fontSize: '14px',
                        display: 'flex',
                        gap: '8px',
                        alignItems: 'flex-start',
                        cursor: 'pointer',
                        userSelect: 'none'
                      }}
                    >
                      <span style={{ color: task.completed ? 'var(--primary)' : 'var(--text-muted)', flexShrink: 0 }}>
                        {task.completed ? '☑' : '☐'}
                      </span>
                      <span
                        style={{
                          textDecoration: task.completed ? 'line-through' : 'none',
                          color: task.completed ? 'var(--text-muted)' : 'var(--text-main)'
                        }}
                      >
                        {task.content}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}

            <div style={{ fontSize: '12px', color: 'var(--text-muted)', textAlign: 'center', paddingTop: '4px' }}>
              수정하려면 왼쪽 대화에 요청을 입력하세요. (예: "주말은 빼줘", "일정을 늘려줘")<br />
              할 일을 클릭하면 완료 표시가 됩니다.
            </div>
          </div>
        )}
      </div>
    </div>
  );

  return (
    <div className="split-layout">
      <div className="split-pane split-pane--left">{chatPanel}</div>
      <div className="split-pane split-pane--right">{checklistPanel}</div>

      <style>{`
        .split-layout {
          flex: 1;
          min-height: 0;
          display: grid;
          grid-template-columns: 1fr 1fr;
        }
        .split-pane {
          min-height: 0;
          overflow: hidden;
        }
        .split-pane--left {
          border-right: 1px solid var(--border);
        }
        @media (max-width: 760px) {
          .split-layout {
            grid-template-columns: 1fr;
            grid-template-rows: 1fr 1fr;
          }
          .split-pane--left {
            border-right: none;
            border-bottom: 1px solid var(--border);
          }
        }
      `}</style>
    </div>
  );
}
