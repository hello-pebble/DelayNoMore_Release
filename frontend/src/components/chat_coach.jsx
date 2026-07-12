import React, { useState, useEffect, useRef } from 'react';
import { Send, Bot, RefreshCw } from 'lucide-react';
import {
  getNextEmptySlot,
  getNextQuestion,
  parseUserMessage,
  generateChecklistDraft,
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
      // 이미 드래프트가 생성되었고, 사용자가 수정 요청 피드백을 주고 있는 상태라면 재수정
      if (draftChecklist) {
        const botMsgId = generateUniqueId('stream');
        setMessages((prev) => [
          ...prev,
          {
            id: botMsgId,
            sender: 'bot',
            text: `🔄 요청하신 사항("${userText}")을 기반으로 계획을 다시 수정하고 있습니다. 잠시만 기다려 주세요.\n\n`
          }
        ]);

        startThinking();

        let hasStartedStreaming = false;
        // 직전 초안(draftChecklist)을 넘겨 백엔드가 assistant 턴으로 이어 재수정하게 한다.
        const refined = await generateChecklistDraft(slots, userText, () => {
          if (!hasStartedStreaming) {
            hasStartedStreaming = true;
            stopThinking();
          }
          setMessages((prev) =>
            prev.map(msg =>
              msg.id === botMsgId
                ? { ...msg, text: `🔄 요청하신 사항("${userText}")을 기반으로 일차별 계획표를 보완 및 재조정하고 있습니다. 잠시만 기다려 주세요...` }
                : msg
            )
          );
        }, draftChecklist);

        stopThinking();
        setDraftChecklist(refined);
        const replyText = `요청하신 사항("${userText}")을 계획에 반영하여 보완했습니다. 새 일정을 아래 카드에서 확인해 보세요!`;
        setMessages((prev) =>
          prev.map(msg =>
            msg.id === botMsgId
              ? { ...msg, text: replyText }
              : msg
          )
        );
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
          text: "🎯 AI 코치가 사용자의 수준과 일정에 맞춤화된 계획표를 조립하고 있습니다. 잠시만 기다려 주세요.\n\n"
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
              ? { ...msg, text: `🎯 AI 코치가 사용자의 수준과 일정에 맞춤화된 계획표를 조립하고 있습니다. 잠시만 기다려 주세요...` }
              : msg
          )
        );
      });

      stopThinking();
      setDraftChecklist(checklist);
      const replyText = "🎉 축하합니다! 완벽한 계획 수립을 위한 핵심 정보가 모두 채워졌습니다. 귀하를 미루지 않게 해줄 일차별 계획 초안을 작성했습니다. 아래 카드를 확인해 주세요.";
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

  const handleRefineRequest = () => {
    const replyText = "계획의 어떤 부분을 변경하고 싶으신가요? 채팅으로 자세히 알려주시면 맞춤 수정해 드리겠습니다. (예: '수요일은 야근이 있어 가볍게 해줘', '주말 일정을 제외해줘', '일정을 늘려줘')";
    setMessages((prev) => [
      ...prev,
      {
        id: generateUniqueId('bot'),
        sender: 'bot',
        text: replyText
      }
    ]);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 64px)', position: 'relative' }}>
      {/* AI 코치 헤더 */}
      <div style={{ padding: '16px 20px', background: 'var(--bg-glass)', borderBottom: '1px solid var(--border-light)', display: 'flex', alignItems: 'center', gap: '10px' }}>
        <div style={{ width: '40px', height: '40px', borderRadius: '50%', background: 'var(--primary-gradient)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Bot size={22} color="white" />
        </div>
        <div>
          <h3 style={{ fontSize: '18px', fontWeight: '700', fontFamily: 'var(--font-title)', color: 'var(--text-main)' }}>지연 제로 AI 코치</h3>
          <span style={{ fontSize: '13px', color: 'var(--text-muted)' }}>1:1 아날로그 극복 일기장</span>
        </div>
      </div>

      {/* 대화 영역 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '20px', display: 'flex', flexDirection: 'column', gap: '18px' }}>
        {messages.map((msg) => (
          <div
            key={msg.id}
            className="animate-fade-in"
            style={{
              display: 'flex',
              justifyContent: msg.sender === 'user' ? 'flex-end' : 'flex-start',
              gap: '8px'
            }}
          >
            {msg.sender === 'bot' && (
              <div style={{ width: '28px', height: '28px', borderRadius: '50%', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1px solid var(--border-light)', flexShrink: 0 }}>
                <Bot size={14} color="var(--primary)" />
              </div>
            )}
            <div
              className={msg.sender === 'bot' ? 'post-it' : ''}
              style={{
                maxWidth: '85%',
                padding: '12px 16px',
                lineHeight: '1.6',
                whiteSpace: 'pre-wrap',
                // 봇은 포스트잇 메모지 스타일
                background: msg.sender === 'bot' ? 'var(--bg-card)' : 'transparent',
                color: msg.sender === 'bot' ? 'var(--text-main)' : 'var(--primary)',
                // 유저는 공책에 직접 만년필로 적은 손글씨 스타일
                fontFamily: msg.sender === 'bot' ? 'var(--font-body)' : 'var(--font-title)',
                fontSize: msg.sender === 'bot' ? '14px' : '18px',
                fontWeight: msg.sender === 'bot' ? '500' : '700',
                border: msg.sender === 'bot' ? '1px solid var(--border-light)' : 'none',
                borderLeft: msg.sender === 'bot' ? '4px solid var(--accent)' : 'none',
                borderRadius: msg.sender === 'bot' ? '4px' : '0px',
                boxShadow: msg.sender === 'bot' ? '2px 2px 10px rgba(0,0,0,0.04)' : 'none',
                textDecoration: msg.sender === 'user' ? 'underline' : 'none',
                textDecorationColor: 'rgba(139, 92, 246, 0.3)',
                paddingRight: msg.sender === 'user' ? '8px' : '16px'
              }}
            >
              {msg.text}
            </div>
          </div>
        ))}

        {/* AI Thinking indicator with dynamic status and elapsed time */}
        {isThinking && (
          <div className="animate-fade-in" style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <div style={{ width: '28px', height: '28px', borderRadius: '50%', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1px solid var(--border-light)', flexShrink: 0 }}>
              <Bot size={14} color="var(--primary)" />
            </div>
            <div className="glass-panel" style={{
              padding: '12px 16px',
              borderRadius: '16px',
              borderBottomLeftRadius: '4px',
              border: '1px solid rgba(139, 92, 246, 0.25)',
              background: 'linear-gradient(135deg, rgba(139, 92, 246, 0.08) 0%, rgba(236, 72, 153, 0.08) 100%)',
              display: 'flex',
              flexDirection: 'column',
              gap: '6px',
              maxWidth: '80%'
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '15px', fontWeight: '600', color: 'var(--text-main)' }}>
                <span style={{ width: '6px', height: '6px', background: 'var(--accent)', borderRadius: '50%', display: 'inline-block', animation: 'pulseGlow 1.2s infinite' }}></span>
                <span>코치가 분석 중입니다... ({elapsedTime}초 경과)</span>
              </div>
              <div style={{ fontSize: '14px', color: 'var(--text-muted)' }}>
                ⚡ {thinkingStatus}
              </div>
            </div>
          </div>
        )}

        {/* AI Typing indicator */}
        {isTyping && (
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <div style={{ width: '28px', height: '28px', borderRadius: '50%', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1px solid var(--border-light)' }}>
              <Bot size={14} color="var(--primary)" />
            </div>
            <div className="glass-panel" style={{ padding: '10px 16px', borderRadius: '16px', borderBottomLeftRadius: '4px', display: 'flex', gap: '4px' }}>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'pulseGlow 1.2s infinite' }}></span>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'pulseGlow 1.2s infinite', animationDelay: '0.2s' }}></span>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'pulseGlow 1.2s infinite', animationDelay: '0.4s' }}></span>
            </div>
          </div>
        )}

        {/* 생성된 체크리스트 카드 시각화 (공책 줄노트 스타일) */}
        {draftChecklist && (
          <div className="glass-panel animate-fade-in" style={{ border: '1px solid var(--border-active)', padding: '20px', display: 'flex', flexDirection: 'column', gap: '16px', marginTop: '14px', position: 'relative', boxShadow: '0 8px 24px rgba(0,0,0,0.05)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '1px solid var(--border-light)', paddingBottom: '12px', marginTop: '6px' }}>
              <div>
                <span style={{ fontSize: '13px', color: 'var(--accent)', fontWeight: '600', textTransform: 'uppercase', letterSpacing: '0.05em' }}>AI 제안 계획 초안</span>
                <h4 style={{ fontSize: '20px', fontWeight: '700', color: 'var(--text-main)', marginTop: '2px', fontFamily: 'var(--font-title)' }}>🎯 {draftChecklist.goalName}</h4>
              </div>
              <div style={{ textAlign: 'right', fontSize: '14px', color: 'var(--text-muted)', fontFamily: 'var(--font-title)' }}>
                기간: <strong style={{ color: 'var(--text-main)' }}>{draftChecklist.duration}일간</strong>
                <br />
                하루: <strong style={{ color: 'var(--text-main)' }}>{draftChecklist.dailyHours}시간</strong>
              </div>
            </div>

            {/* 일차별 미션 요약 (공책 줄눈 적용) */}
            <div className="lined-paper" style={{ display: 'flex', flexDirection: 'column', gap: '12px', maxHeight: '240px', overflowY: 'auto', paddingLeft: '24px', paddingRight: '4px' }}>
              {Object.entries(draftChecklist.tasks).map(([date, taskList], idx) => (
                <div key={date} style={{ background: 'rgba(255,255,255,0.01)', padding: '6px 0', borderBottom: '1px dashed var(--border-light)' }}>
                  <div style={{ fontSize: '15px', fontWeight: '700', color: 'var(--primary)', marginBottom: '4px', fontFamily: 'var(--font-title)' }}>
                    📅 Day {idx + 1} ({date})
                  </div>
                  <ul style={{ paddingLeft: '14px', display: 'flex', flexDirection: 'column', gap: '2px' }}>
                    {taskList.map((task) => (
                      <li key={task.id} style={{ fontSize: '16px', color: 'var(--text-main)', listStyleType: 'circle' }}>
                        {task.content}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>

            {/* 수정 루프 버튼 */}
            <div style={{ display: 'flex', gap: '10px', borderTop: '1px solid var(--border-light)', paddingTop: '16px' }}>
              <button
                className="btn-secondary"
                onClick={handleRefineRequest}
                style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px', fontSize: '15px', fontFamily: 'var(--font-title)', height: '42px' }}
              >
                <RefreshCw size={14} />
                계획 보완/수정
              </button>
            </div>
          </div>
        )}

        <div ref={chatEndRef} />
      </div>

      {/* 입력바 */}
      <form
        onSubmit={handleSendMessage}
        style={{
          padding: '16px 20px',
          background: 'var(--bg-glass)',
          borderTop: '1px solid var(--border-light)',
          display: 'flex',
          gap: '10px'
        }}
      >
        <input
          type="text"
          className="glass-panel"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder={draftChecklist ? "수정 요청 사항을 입력해 주세요..." : "대답을 입력해 주세요..."}
          style={{
            flex: 1,
            padding: '12px 16px',
            background: 'var(--bg-card)',
            border: '1px solid var(--border-light)',
            borderRadius: '12px',
            color: 'var(--text-main)',
            outline: 'none',
            fontFamily: 'var(--font-title)',
            fontSize: '18px'
          }}
        />
        <button
          type="submit"
          className="btn-primary"
          style={{
            padding: '12px',
            width: '46px',
            height: '46px',
            borderRadius: '12px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: 'none'
          }}
        >
          <Send size={16} />
        </button>
      </form>
    </div>
  );
}
