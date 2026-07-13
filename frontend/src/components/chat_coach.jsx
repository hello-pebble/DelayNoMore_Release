import React, { useState, useEffect, useRef } from 'react';
import { Send } from 'lucide-react';
import {
  getNextEmptySlot,
  getNextQuestion,
  parseUserMessage,
  generateChecklistDraft,
  chatWithCoach,
  INITIAL_SLOTS
} from '../ai_engine';

// 고유 ID 생성 유틸리티 (중복 Key 방지)
const generateUniqueId = (prefix = 'msg') => {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
};

export default function ChatCoach() {
  const [slots, setSlots] = useState({ ...INITIAL_SLOTS });
  const [messages, setMessages] = useState([]);
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const [draftChecklist, setDraftChecklist] = useState(null);
  const [currentSlot, setCurrentSlot] = useState(null);
  const [isThinking, setIsThinking] = useState(false);
  const [elapsedTime, setElapsedTime] = useState(0);
  const [thinkingStatus, setThinkingStatus] = useState('');

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

  // 컴포넌트 마운트 시 첫 질문 발송 (StrictMode 이중 실행 가드 탑재)
  const initedRef = useRef(false);
  useEffect(() => {
    if (initedRef.current) return;
    initedRef.current = true;

    const nextSlot = getNextEmptySlot(slots);
    setCurrentSlot(nextSlot);
    setMessages([
      {
        id: 'bot-init-first',
        sender: 'bot',
        text: getNextQuestion(nextSlot)
      }
    ]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSendMessage = async (e) => {
    e.preventDefault();
    if (!inputValue.trim()) return;

    const userText = inputValue.trim();
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
              {msg.text}
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

        <div ref={chatEndRef} />
      </div>

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

  // === 오른쪽: 체크리스트 패널 ===
  const checklistPanel = (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%', background: 'var(--bg-panel)' }}>
      <div style={{ padding: '10px 16px', borderBottom: '1px solid var(--border)', fontWeight: 600, fontSize: '14px', flexShrink: 0 }}>
        생성된 체크리스트
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
              <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
                기간 {draftChecklist.duration}일 · 하루 {draftChecklist.dailyHours}시간 · {draftChecklist.currentLevel}
              </div>
            </div>

            {/* 일차별 미션 */}
            {Object.entries(draftChecklist.tasks).map(([date, taskList], idx) => (
              <div
                key={date}
                style={{
                  background: 'var(--bg-card)',
                  border: '1px solid var(--border)',
                  borderRadius: '8px',
                  padding: '12px 14px'
                }}
              >
                <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--primary)', marginBottom: '8px' }}>
                  Day {idx + 1} · {date}
                </div>
                <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                  {taskList.map((task) => (
                    <li key={task.id} style={{ fontSize: '14px', display: 'flex', gap: '8px', alignItems: 'flex-start' }}>
                      <span style={{ color: 'var(--text-muted)' }}>☐</span>
                      <span>{task.content}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}

            <div style={{ fontSize: '12px', color: 'var(--text-muted)', textAlign: 'center', paddingTop: '4px' }}>
              수정하려면 왼쪽 대화에 요청을 입력하세요. (예: "주말은 빼줘", "일정을 늘려줘")
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
