import React, { useState, useEffect, useRef } from 'react';
import ChatCoach from './components/chat_coach';
import NicknameSetup from './components/nickname_setup';
import { checkOpenRouterConnection } from './ai_engine';
import { getNickname, setNickname } from './nickname';
import { getGuestId, isGuestIdPersisted } from './guest_id';
import { getAuth, setAuth, clearAuth } from './auth';
import { fetchAuthConfig, postGoogleLogin, postLogout } from './db_service';

export default function App() {
  // AI 연결 상태: 'checking' | 'connected' | 'error'
  const [apiStatus, setApiStatus] = useState('checking');
  const [apiReason, setApiReason] = useState('');
  // 에이전트(도구 호출) 경로 가용 여부 — 서버가 헬스체크에 함께 내려준다. 도구를 지원하지 않는
  // 모델로 배포를 바꾸면 false가 되고, 대화는 기존 자유 대화 경로로만 동작한다.
  const [agentEnabled, setAgentEnabled] = useState(false);

  // 닉네임(표시 이름) 게이트 — 없으면 설정 화면을 먼저 보여준다. 데이터 소유는 게스트 ID이며
  // 닉네임은 라벨일 뿐이라, 닉네임 변경은 데이터 스코프를 바꾸지 않는다(ChatCoach 리마운트 없음).
  const [nickname, setNicknameState] = useState(() => getNickname());
  const [editingNickname, setEditingNickname] = useState(false);

  // localStorage 저장 실패(프라이빗 모드 등) 감지 — 게스트 ID가 이 탭 메모리에만 있어,
  // 새로고침·탭 종료 시 보관함 접근을 잃는다. 사용자에게 미리 안내한다.
  const [storageEphemeral, setStorageEphemeral] = useState(false);

  // 모바일 폭에서는 헤더에 긴 문구를 둘 자리가 없다. 데모 안내와 AI 상태 문구는 헤더 아래
  // 한 줄 배너로 접어 두고, 상태 LED를 누르면 펼친다(정보는 그대로 두고 자리만 옮긴 것).
  const [infoOpen, setInfoOpen] = useState(false);

  // 로그인 상태(v0.22.0) — {token, nickname, email} 또는 null. 데이터 소유자 전환은 서버가
  // Authorization 헤더로 판정하므로(db_service.js), 여기서는 상태 보관과 버튼 표시만 한다.
  const [auth, setAuthState] = useState(() => getAuth());
  const [authConfig, setAuthConfig] = useState(null);
  const googleBtnRef = useRef(null);

  const handleGoogleCredential = async (response) => {
    try {
      // 현재 localStorage 닉네임을 함께 보낸다 — 최초 가입 시 서버 닉네임으로 이관된다.
      const data = await postGoogleLogin(response.credential, getNickname());
      setAuth(data);
      setAuthState(data);
    } catch (err) {
      alert(err?.message || '로그인에 실패했습니다. 다시 시도해주세요.');
    }
  };

  const handleLogout = () => {
    postLogout().catch(() => {}); // 서버 세션 삭제가 실패해도 로컬 로그아웃은 진행한다
    clearAuth();
    setAuthState(null);
  };

  // 로그인 설정 조회 — 서버에 클라이언트 ID가 없으면 enabled=false라 버튼 자체가 안 그려진다.
  useEffect(() => {
    let active = true;
    fetchAuthConfig().then((cfg) => {
      if (active) setAuthConfig(cfg);
    });
    return () => {
      active = false;
    };
  }, []);

  // GIS 버튼 렌더 — gsi/client 스크립트(index.html)가 async 로드라 준비될 때까지 짧게 재시도한다.
  useEffect(() => {
    if (!authConfig?.enabled || auth || !googleBtnRef.current) return undefined;
    let cancelled = false;
    let tries = 0;
    const tryRender = () => {
      if (cancelled) return;
      const gis = window.google?.accounts?.id;
      if (!gis) {
        if (tries++ < 50) setTimeout(tryRender, 200); // 최대 ~10초 대기 후 조용히 포기(게스트 흐름 유지)
        return;
      }
      gis.initialize({ client_id: authConfig.clientId, callback: handleGoogleCredential });
      // 헤더가 좁아(모바일 480px) 아이콘형 버튼만 그린다.
      gis.renderButton(googleBtnRef.current, { type: 'icon', shape: 'circle', size: 'medium' });
    };
    tryRender();
    return () => {
      cancelled = true;
    };
  }, [authConfig, auth]);

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
      setAgentEnabled(result?.success === true && result?.toolCalling === true);
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

  // 연결됐을 때는 대화가 어느 경로로 도는지까지 알린다 — 에이전트 모드면 코치가 도구를 호출해
  // 서버 데이터를 읽고 계획을 고친다(추적 패널이 그 과정을 보여준다).
  const ledLabel =
    apiStatus === 'connected' ? (agentEnabled ? 'AI 연결됨 · 에이전트 모드' : 'AI 연결됨') :
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
          padding: '0 12px',
          gap: '8px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-card)'
        }}
      >
        <div style={{ fontSize: '17px', fontWeight: 700, flexShrink: 0 }}>DelayNoMore</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', color: 'var(--text-muted)', minWidth: 0 }}>
          <span
            title={auth?.email || undefined}
            style={{
              fontWeight: 600,
              color: 'var(--text-main)',
              maxWidth: '84px',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap'
            }}
          >
            {auth?.nickname || nickname}
          </span>
          {/* 로그인(v0.22.0) — 비로그인 + 기능 활성일 때만 GIS 아이콘 버튼 컨테이너를 그린다.
              로그인 상태면 로그아웃 버튼으로 교체된다. */}
          {!auth && authConfig?.enabled && (
            <div ref={googleBtnRef} style={{ flexShrink: 0, height: '32px' }} />
          )}
          {auth && (
            <button
              onClick={handleLogout}
              title="로그아웃 — 이 브라우저의 게스트 보관함으로 돌아갑니다"
              style={{
                padding: '0 10px',
                minHeight: '32px',
                flexShrink: 0,
                background: 'var(--bg-card)',
                color: 'var(--text-muted)',
                border: '1px solid var(--border)',
                borderRadius: '6px',
                fontSize: '12px',
                cursor: 'pointer'
              }}
            >
              로그아웃
            </button>
          )}
          <button
            onClick={() => setEditingNickname(true)}
            title="표시 이름만 바뀝니다 — 보관함 데이터는 그대로 유지됩니다"
            style={{
              padding: '0 10px',
              minHeight: '32px',
              flexShrink: 0,
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
          {/* 상태 LED — 좁은 폭에서는 점만 두고, 누르면 아래 배너에 전체 문구가 펼쳐진다. */}
          <button
            type="button"
            onClick={() => setInfoOpen((v) => !v)}
            aria-expanded={infoOpen}
            aria-label={ledLabel}
            title={ledLabel}
            style={{
              width: '32px',
              minHeight: '32px',
              flexShrink: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'transparent',
              border: 'none',
              cursor: 'pointer'
            }}
          >
            <span
              style={{
                width: '10px',
                height: '10px',
                borderRadius: '50%',
                background: ledColor,
                display: 'inline-block'
              }}
            />
          </button>
        </div>
      </header>

      {/* 데모 특성 안내 + AI 상태 — 방문자가 미리 알아야 할 특성(브라우저 단위 보관함·로그인 전이라
          브라우저 데이터를 지우면 복구 불가)만 알린다. 서버 데이터는 DB에 영속된다(v0.12.0). */}
      {infoOpen && (
        <div
          style={{
            flexShrink: 0,
            padding: '8px 12px',
            fontSize: '12px',
            lineHeight: 1.5,
            color: 'var(--text-muted)',
            background: 'var(--bg-panel)',
            borderBottom: '1px solid var(--border)'
          }}
        >
          <div style={{ color: 'var(--text-main)', fontWeight: 600 }}>{ledLabel}</div>
          데모 페이지 — 계획은 이 브라우저 보관함에 저장됩니다 · 브라우저 데이터를 지우면 복구할 수 없어요(로그인 전)
        </div>
      )}

      {storageEphemeral && (
        // 저장소 차단 경고 — 게스트 ID가 이 탭에만 있어 새로고침 시 보관함을 잃는다.
        <div
          role="alert"
          style={{
            flexShrink: 0,
            padding: '8px 12px',
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
      <ChatCoach agentEnabled={agentEnabled} />

      {/* "변경"은 오버레이로 — ChatCoach가 마운트된 채 위에 얹혀, 대화·계획 상태가 유지된다.
          모바일 전용 컬럼(#root 최대 480px) 안에 맞춰 가운데 정렬한다. */}
      {editingNickname && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0, 0, 0, 0.4)',
            display: 'flex',
            justifyContent: 'center',
            zIndex: 50
          }}
        >
          <div style={{ width: '100%', maxWidth: '480px', display: 'flex' }}>
            <NicknameSetup
              initialValue={nickname}
              onSubmit={handleNicknameSubmit}
              onCancel={() => setEditingNickname(false)}
            />
          </div>
        </div>
      )}
    </>
  );
}
