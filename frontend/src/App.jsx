import React, { useState, useEffect } from 'react';
import { Sun, Moon } from 'lucide-react';
import ChatCoach from './components/chat_coach';
import { checkOpenRouterConnection } from './ai_engine';

export default function App() {
  const [isDark, setIsDark] = useState(false);
  // AI 연결 상태 LED: 'checking' | 'connected' | 'error'
  const [apiStatus, setApiStatus] = useState('checking');
  const [apiReason, setApiReason] = useState('');

  // 테마 클래스를 body에 반영 (index.css의 CSS 변수는 body.light-mode / body.dark-mode에서 정의됨)
  useEffect(() => {
    if (isDark) {
      document.body.classList.add('dark-mode');
      document.body.classList.remove('light-mode');
    } else {
      document.body.classList.add('light-mode');
      document.body.classList.remove('dark-mode');
    }
  }, [isDark]);

  // 마운트 시 백엔드 AI 프록시 헬스체크 (헤더 LED 표시용)
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
    apiStatus === 'error' ? 'var(--danger)' :
    'var(--warning)';

  const ledLabel =
    apiStatus === 'connected' ? 'AI 연결됨' :
    apiStatus === 'error' ? (apiReason || 'AI 미연결 (mock 사용)') :
    '연결 확인 중...';

  return (
    <>
      <header className="app-header">
        <div className="app-title">
          <span>🔥</span>
          <span>DelayNoMore</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
          <div
            title={ledLabel}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-muted)' }}
          >
            <span
              style={{
                width: '9px',
                height: '9px',
                borderRadius: '50%',
                background: ledColor,
                boxShadow: `0 0 8px ${ledColor}`,
                display: 'inline-block'
              }}
            />
            {ledLabel}
          </div>
          <button
            onClick={() => setIsDark((v) => !v)}
            aria-label="테마 전환"
            style={{
              background: 'none',
              border: '1px solid var(--border-light)',
              borderRadius: '10px',
              width: '38px',
              height: '38px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
              color: 'var(--text-main)'
            }}
          >
            {isDark ? <Sun size={18} /> : <Moon size={18} />}
          </button>
        </div>
      </header>

      <ChatCoach />
    </>
  );
}
