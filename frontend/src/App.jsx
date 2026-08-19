import React, { useState, useEffect, useRef } from 'react';
import ChatCoach from './components/chat_coach';
import NicknameSetup from './components/nickname_setup';
import { checkOpenRouterConnection } from './ai_engine';
import { getNickname, setNickname, randomGuestNickname } from './nickname';
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

  // 마이페이지 — 헤더의 닉네임을 누르면 열린다. 계정 관련 조작(닉네임 변경·로그인·로그아웃)을
  // 한곳에 모아 헤더에서 버튼 3개를 덜어냈다(모바일 폭 480px에 이름·버튼들이 다 들어가지 않음).
  const [myPageOpen, setMyPageOpen] = useState(false);

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
  // 버튼 컨테이너(googleBtnRef)는 두 곳에만 마운트된다: 시작 화면(첫 방문)과 마이페이지.
  // deps에 nickname·myPageOpen이 있는 이유 — 화면이 갈리면 컨테이너 div가 새로 생기므로
  // 그 새 컨테이너에 다시 그려야 한다(이전 컨테이너에 그린 버튼은 언마운트와 함께 사라짐).
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
      gis.renderButton(googleBtnRef.current,
        { type: 'standard', text: 'continue_with', shape: 'pill', size: 'large' });
    };
    tryRender();
    return () => {
      cancelled = true;
    };
  }, [authConfig, auth, nickname, myPageOpen]);

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

  // 최초 진입(닉네임도 로그인도 없음) 시작 화면 — 두 갈래뿐이다:
  //   ① Google로 바로 시작(로그인 기능이 켜져 있을 때만) ② 게스트로 시작(닉네임 랜덤 자동생성).
  // 닉네임 수동 입력 게이트(NicknameSetup)는 시작 흐름에서 빠졌다 — 이름은 시작을 막을 이유가
  // 없고, 바꾸고 싶으면 헤더의 "변경" 오버레이가 그대로 있다. 로그인 상태면 이 화면 자체를
  // 건너뛴다(표시는 로컬 닉네임 → 서버 닉네임 → 이메일 순 폴백).
  if (!nickname && !auth) {
    return (
      <div
        style={{
          flex: 1,
          minHeight: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: '12px',
          padding: '24px',
          textAlign: 'center'
        }}
      >
        <div style={{ fontSize: '24px', fontWeight: 700 }}>DelayNoMore</div>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)', lineHeight: 1.6, marginBottom: '12px' }}>
          AI 코치와 대화해 하루 단위 계획을 만들고,
          <br />
          매일 체크하며 미루기 습관을 끊습니다.
        </div>

        {authConfig?.enabled && (
          <>
            {/* Google 시작 — 어느 기기에서든 같은 계정이면 같은 보관함이 열린다. */}
            <div ref={googleBtnRef} style={{ minHeight: '44px' }} />
            <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>또는</div>
          </>
        )}

        <button
          onClick={() => handleNicknameSubmit(randomGuestNickname())}
          style={{
            padding: '12px 24px',
            background: 'var(--bg-card)',
            color: 'var(--text-main)',
            border: '1px solid var(--border)',
            borderRadius: '999px',
            fontSize: '14px',
            fontWeight: 600,
            cursor: 'pointer'
          }}
        >
          게스트로 시작하기
        </button>
        <div style={{ fontSize: '11px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
          게스트 데이터는 이 브라우저에만 연결됩니다.
          <br />
          닉네임은 자동 생성되며 나중에 바꿀 수 있어요.
        </div>
      </div>
    );
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
          {/* 닉네임 = 마이페이지 진입점. 계정 조작(변경·로그인·로그아웃)은 전부 그 안에 있다.
              표시 우선순위: 이 브라우저에서 정한 닉네임 → 서버 닉네임(다른 기기 가입) → 이메일. */}
          <button
            type="button"
            onClick={() => setMyPageOpen(true)}
            title="마이페이지 — 닉네임 변경·로그인"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              maxWidth: '150px',
              minHeight: '32px',
              padding: '0 10px',
              background: 'var(--bg-card)',
              border: '1px solid var(--border)',
              borderRadius: '999px',
              cursor: 'pointer'
            }}
          >
            <span
              style={{
                fontSize: '12px',
                fontWeight: 600,
                color: 'var(--text-main)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              }}
            >
              {nickname || auth?.nickname || auth?.email || '사용자'}
            </span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)', flexShrink: 0 }}>⚙</span>
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

      {/* 마이페이지 — 헤더 닉네임으로 연다. 오버레이라 ChatCoach가 마운트된 채 위에 얹혀
          대화·계획 상태가 유지된다(닉네임 변경 오버레이와 같은 이유). */}
      {myPageOpen && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0, 0, 0, 0.4)',
            display: 'flex',
            justifyContent: 'center',
            zIndex: 40
          }}
        >
          <div
            style={{
              width: '100%',
              maxWidth: '480px',
              background: 'var(--bg-card)',
              display: 'flex',
              flexDirection: 'column'
            }}
          >
            <div
              style={{
                height: '52px',
                flexShrink: 0,
                padding: '0 12px',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                borderBottom: '1px solid var(--border)'
              }}
            >
              <button
                type="button"
                onClick={() => setMyPageOpen(false)}
                aria-label="닫기"
                style={{
                  width: '32px',
                  minHeight: '32px',
                  background: 'transparent',
                  border: 'none',
                  fontSize: '18px',
                  cursor: 'pointer',
                  color: 'var(--text-muted)'
                }}
              >
                ←
              </button>
              <div style={{ fontSize: '16px', fontWeight: 700 }}>마이페이지</div>
            </div>

            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '16px 16px 28px' }}>
              {/* 프로필 — 표시 이름과 계정 상태(게스트/로그인)를 한눈에. */}
              <div
                style={{
                  padding: '16px',
                  border: '1px solid var(--border)',
                  borderRadius: '12px',
                  background: 'var(--bg-panel)'
                }}
              >
                <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '4px' }}>표시 이름</div>
                <div style={{ fontSize: '18px', fontWeight: 700, wordBreak: 'break-all' }}>
                  {nickname || auth?.nickname || auth?.email || '사용자'}
                </div>
                <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '8px', lineHeight: 1.5 }}>
                  {auth
                    ? `Google 계정으로 로그인됨${auth.email ? ` · ${auth.email}` : ''}`
                    : '게스트 — 이 브라우저에만 데이터가 연결됩니다'}
                </div>
              </div>

              <button
                onClick={() => setEditingNickname(true)}
                style={{
                  width: '100%',
                  marginTop: '12px',
                  padding: '12px',
                  background: 'var(--bg-card)',
                  color: 'var(--text-main)',
                  border: '1px solid var(--border)',
                  borderRadius: '8px',
                  fontSize: '14px',
                  cursor: 'pointer'
                }}
              >
                닉네임 변경
              </button>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '6px', lineHeight: 1.5 }}>
                표시 이름만 바뀝니다 — 계획·회고 데이터는 그대로 유지됩니다.
              </div>

              {/* 계정 — 비로그인이면 Google 로그인, 로그인 상태면 로그아웃. */}
              <div style={{ marginTop: '24px', borderTop: '1px solid var(--border)', paddingTop: '20px' }}>
                {!auth && authConfig?.enabled && (
                  <>
                    <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '6px' }}>계정으로 이어서 쓰기</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-muted)', lineHeight: 1.6, marginBottom: '12px' }}>
                      로그인하면 지금 이 브라우저의 계획·회고·포인트가 계정으로 옮겨지고,
                      다른 기기에서도 같은 보관함이 열립니다.
                    </div>
                    <div ref={googleBtnRef} style={{ display: 'flex', justifyContent: 'center' }} />
                  </>
                )}
                {auth && (
                  <button
                    onClick={handleLogout}
                    style={{
                      width: '100%',
                      padding: '12px',
                      background: 'var(--bg-card)',
                      color: 'var(--text-muted)',
                      border: '1px solid var(--border)',
                      borderRadius: '8px',
                      fontSize: '14px',
                      cursor: 'pointer'
                    }}
                  >
                    로그아웃
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 닉네임 변경 오버레이 — 마이페이지 위에 겹쳐 뜬다(zIndex가 더 높음). */}
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
