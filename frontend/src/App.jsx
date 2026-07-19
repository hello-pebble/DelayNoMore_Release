import React, { useState, useEffect } from 'react';
import ChatCoach from './components/chat_coach';
import { checkOpenRouterConnection } from './ai_engine';

export default function App() {
  // AI 연결 상태: 'checking' | 'connected' | 'error'
  const [apiStatus, setApiStatus] = useState('checking');
  const [apiReason, setApiReason] = useState('');

  // 마운트 시 백엔드 AI 프록시 헬스체크 (상태 표시용)
  useEffect(() => {
    let active = true;
    checkOpenRouterConnection().then((result) => {
      if (!active) return;
      setApiStatus(result?.success ? 'connected' : 'error');
      setApiReason(result?.reason || '');
    });
    return () => {
      active = false;
    };
  }, []);

  const ledColor =
    apiStatus === 'connected' ? 'var(--success)' :
    apiStatus === 'error' ? 'var(--warning)' :
    'var(--text-muted)';

  const ledLabel =
    apiStatus === 'connected' ? 'AI 연결됨' :
    apiStatus === 'error' ? (apiReason || 'AI 미연결 (mock 사용)') :
    '연결 확인 중...';

  return (
    <>
      <header
        style={{
          height: '52px',
          flexShrink: 0,
          padding: '0 16px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-card)'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '12px', minWidth: 0 }}>
          <div style={{ fontSize: '17px', fontWeight: 700, flexShrink: 0 }}>DelayNoMore</div>
          {/* 데모 사용 안내 — 체크리스트 하단에 있던 문구를 간소화해 첫 시선이 닿는 제목 옆으로 올렸다. */}
          <div className="header-guide" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            대화로 계획 생성·수정 · 할 일 클릭으로 완료 체크 · "계획 저장"을 누르면 고정
          </div>
        </div>
        <div
          title={ledLabel}
          style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-muted)' }}
        >
          <span
            style={{
              width: '8px',
              height: '8px',
              borderRadius: '50%',
              background: ledColor,
              display: 'inline-block'
            }}
          />
          {ledLabel}
        </div>
      </header>

      <ChatCoach />
    </>
  );
}
