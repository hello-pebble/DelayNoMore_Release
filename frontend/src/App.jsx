import React, { useState, useEffect } from 'react';
import ChatCoach from './components/chat_coach';
import NicknameSetup from './components/nickname_setup';
import { checkOpenRouterConnection } from './ai_engine';
import { getNickname, setNickname } from './nickname';

export default function App() {
  // AI 연결 상태: 'checking' | 'connected' | 'error'
  const [apiStatus, setApiStatus] = useState('checking');
  const [apiReason, setApiReason] = useState('');

  // 닉네임 게이트 — 없으면 ChatCoach 대신 설정 화면을 먼저 보여준다(로그인 전 간이 계정 키).
  const [nickname, setNicknameState] = useState(() => getNickname());
  const [editingNickname, setEditingNickname] = useState(false);

  const handleNicknameSubmit = (value) => {
    setNickname(value); // localStorage 보관
    setNicknameState(value); // key={nickname} 변경으로 ChatCoach가 새 닉네임 스코프로 리마운트
    setEditingNickname(false);
  };

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

  // 최초 진입(닉네임 없음) 또는 "변경" 클릭 시 — 설정 화면만 렌더한다.
  if (!nickname || editingNickname) {
    return (
      <NicknameSetup
        initialValue={nickname || ''}
        onSubmit={handleNicknameSubmit}
        onCancel={nickname ? () => setEditingNickname(false) : undefined}
      />
    );
  }

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
              데모 저장소 특성(닉네임별 구분·서버 재시작 시 초기화)만 알린다. */}
          <div className="header-guide" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            데모 페이지 — 계획은 닉네임별로 구분되며, 서버 재시작 시 사라집니다
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px', fontSize: '12px', color: 'var(--text-muted)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <span style={{ fontWeight: 600, color: 'var(--text-main)' }}>{nickname}</span>
            <button
              onClick={() => setEditingNickname(true)}
              title="닉네임을 바꾸면 그 닉네임의 보관함으로 전환됩니다"
              style={{
                padding: '2px 8px',
                background: 'var(--bg-card)',
                color: 'var(--text-muted)',
                border: '1px solid var(--border)',
                borderRadius: '6px',
                fontSize: '12px',
                cursor: 'pointer'
              }}
            >
              변경
            </button>
          </div>
          <div title={ledLabel} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
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
        </div>
      </header>

      {/* key={nickname}: 닉네임이 바뀌면 리마운트 — 마운트 효과가 새 닉네임 스코프로 목록을
          다시 불러오고, 이전 닉네임의 화면 상태(대화·활성 계획)가 남지 않는다. */}
      <ChatCoach key={nickname} />
    </>
  );
}
