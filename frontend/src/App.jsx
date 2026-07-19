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
          {/* 데모 특성 안내 — 사용법은 UI가 직관적이라 생략하고, 방문자가 미리 알아야 할
              데모 저장소 특성(모든 방문자 공유·서버 재시작 시 초기화)만 알린다. */}
          <div className="header-guide" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            데모 페이지 — 계획은 모든 방문자와 공유되며, 서버 재시작 시 사라집니다
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
