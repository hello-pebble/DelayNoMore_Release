import React, { useState, useEffect } from 'react';
import ChatCoach from './components/chat_coach';
import NicknameSetup from './components/nickname_setup';
import { checkOpenRouterConnection } from './ai_engine';
import { getNickname, setNickname } from './nickname';
import { getGuestId, isGuestIdPersisted } from './guest_id';

export default function App() {
  // AI 연결 상태: 'checking' | 'connected' | 'error'
  const [apiStatus, setApiStatus] = useState('checking');
  const [apiReason, setApiReason] = useState('');

  // 닉네임(표시 이름) 게이트 — 없으면 설정 화면을 먼저 보여준다. 데이터 소유는 게스트 ID이며
  // 닉네임은 라벨일 뿐이라, 닉네임 변경은 데이터 스코프를 바꾸지 않는다(ChatCoach 리마운트 없음).
  const [nickname, setNicknameState] = useState(() => getNickname());
  const [editingNickname, setEditingNickname] = useState(false);

  // localStorage 저장 실패(프라이빗 모드 등) 감지 — 게스트 ID가 이 탭 메모리에만 있어,
  // 새로고침·탭 종료 시 보관함 접근을 잃는다. 사용자에게 미리 안내한다.
  const [storageEphemeral, setStorageEphemeral] = useState(false);

  const handleNicknameSubmit = (value) => {
    setNickname(value); // 표시 이름 localStorage 보관
    getGuestId(); // 첫 API 호출 전에 게스트 ID를 확정(생성)해 둔다
    setStorageEphemeral(!isGuestIdPersisted());
    setNicknameState(value);
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

  // 이미 닉네임이 있어 게이트를 건너뛴 경우(새로고침 복원)에도 저장 영속성을 확인한다.
  useEffect(() => {
    if (nickname) {
      getGuestId();
      setStorageEphemeral(!isGuestIdPersisted());
    }
  }, [nickname]);

  const ledColor =
    apiStatus === 'connected' ? 'var(--success)' :
    apiStatus === 'error' ? 'var(--warning)' :
    'var(--text-muted)';

  const ledLabel =
    apiStatus === 'connected' ? 'AI 연결됨' :
    apiStatus === 'error' ? (apiReason || 'AI 미연결 (mock 사용)') :
    '연결 확인 중...';

  // 최초 진입(닉네임 없음)에만 전체 화면 게이트를 렌더한다. "변경"은 오버레이(아래)로 처리해
  // ChatCoach를 언마운트하지 않는다 — 표시 이름만 바꾸는데 대화·계획이 초기화되면 안 되므로.
  if (!nickname) {
    return <NicknameSetup initialValue="" onSubmit={handleNicknameSubmit} />;
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
          {/* 데모 특성 안내 — 방문자가 미리 알아야 할 특성(브라우저 단위 보관함·로그인 전이라
              브라우저 데이터를 지우면 복구 불가)만 알린다. 서버 데이터는 DB에 영속된다(v0.12.0). */}
          <div className="header-guide" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            데모 페이지 — 계획은 이 브라우저 보관함에 저장됩니다 · 브라우저 데이터를 지우면 복구할 수 없어요(로그인 전)
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px', fontSize: '12px', color: 'var(--text-muted)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <span style={{ fontWeight: 600, color: 'var(--text-main)' }}>{nickname}</span>
            <button
              onClick={() => setEditingNickname(true)}
              title="표시 이름만 바뀝니다 — 보관함 데이터는 그대로 유지됩니다"
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

      {storageEphemeral && (
        // 저장소 차단 경고 — 게스트 ID가 이 탭에만 있어 새로고침 시 보관함을 잃는다.
        <div
          role="alert"
          style={{
            flexShrink: 0,
            padding: '8px 16px',
            fontSize: '12px',
            color: '#7c2d12',
            background: '#fef3c7',
            borderBottom: '1px solid var(--border)'
          }}
        >
          브라우저 저장소가 차단되어 있어요(프라이빗 모드 등). 새로고침하거나 탭을 닫으면 지금
          보관함에 다시 접근할 수 없습니다.
        </div>
      )}

      {/* 데이터 스코프는 게스트 ID(안정)라 닉네임이 바뀌어도 ChatCoach를 리마운트하지 않는다. */}
      <ChatCoach />

      {/* "변경"은 오버레이로 — ChatCoach가 마운트된 채 위에 얹혀, 대화·계획 상태가 유지된다. */}
      {editingNickname && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0, 0, 0, 0.4)',
            display: 'flex',
            zIndex: 50
          }}
        >
          <NicknameSetup
            initialValue={nickname}
            onSubmit={handleNicknameSubmit}
            onCancel={() => setEditingNickname(false)}
          />
        </div>
      )}
    </>
  );
}
