import { useEffect, useState } from 'react';
import { postSlackLinkCode, getSlackLink, deleteSlackLink } from '../db_service';

// 마이페이지의 "슬랙 연결" 카드(v0.26.0) — 로그인 상태에서만 마운트된다(App.jsx가 분기).
// 연결 여부·활동시간 판정은 전부 서버 응답이고, 이 카드는 코드 발급/해제 버튼과 표시만 담당한다.
// 서버가 슬랙 기능 자체를 꺼 둔 경우(503 SLACK_DISABLED)에는 카드를 그리지 않는다.
export default function SlackLinkCard() {
  const [status, setStatus] = useState(null); // null = 로딩, { linked, ... } = 조회 결과
  const [disabled, setDisabled] = useState(false);
  const [issuedCode, setIssuedCode] = useState(null); // { code, expiresInMinutes }
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  // App.jsx의 authConfig 조회와 같은 패턴 — 언마운트 후 도착한 응답은 버린다(active 플래그).
  useEffect(() => {
    let active = true;
    getSlackLink()
      .then((data) => {
        if (active) setStatus(data);
      })
      .catch((err) => {
        if (!active) return;
        if (err.code === 'SLACK_DISABLED') setDisabled(true);
        else setError(err.message);
      });
    return () => {
      active = false;
    };
  }, []);

  const handleIssueCode = async () => {
    setBusy(true);
    setError(null);
    try {
      setIssuedCode(await postSlackLinkCode());
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const handleUnlink = async () => {
    setBusy(true);
    setError(null);
    try {
      await deleteSlackLink();
      setIssuedCode(null);
      setStatus(await getSlackLink());
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  if (disabled) return null;

  return (
    <div style={{ marginTop: '24px', borderTop: '1px solid var(--border)', paddingTop: '20px' }}>
      <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '6px' }}>슬랙으로 오늘 할 일 받기</div>

      {status?.linked ? (
        <>
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', lineHeight: 1.6, marginBottom: '12px' }}>
            연결됨 — 매일 {status.activeStart}에 오늘 할 일 체크리스트를, {status.activeEnd}에
            하루를 닫는 회고 질문을 슬랙 DM으로 보내드립니다. 활동시간은 봇에게
            &ldquo;활동시간 9시부터 21시까지로 바꿔줘&rdquo;라고 말하면 바뀝니다.
          </div>
          <button
            onClick={handleUnlink}
            disabled={busy}
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
            슬랙 연결 해제
          </button>
        </>
      ) : (
        <>
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', lineHeight: 1.6, marginBottom: '12px' }}>
            코드를 발급받아 슬랙 봇 DM에 붙여넣으면 연결됩니다. 연결하면 매일 활동 시작
            시각에 오늘 할 일 체크리스트가 DM으로 도착합니다.
          </div>
          <button
            onClick={handleIssueCode}
            disabled={busy || status === null}
            style={{
              width: '100%',
              padding: '12px',
              background: 'var(--bg-card)',
              color: 'var(--text-main)',
              border: '1px solid var(--border)',
              borderRadius: '8px',
              fontSize: '14px',
              cursor: 'pointer'
            }}
          >
            {issuedCode ? '연결 코드 다시 발급' : '슬랙 연결 코드 발급'}
          </button>
          {issuedCode && (
            <div
              style={{
                marginTop: '10px',
                padding: '12px',
                border: '1px dashed var(--border)',
                borderRadius: '8px',
                textAlign: 'center'
              }}
            >
              <div style={{ fontSize: '22px', fontWeight: 700, letterSpacing: '4px', fontFamily: 'monospace' }}>
                {issuedCode.code}
              </div>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '6px', lineHeight: 1.5 }}>
                {issuedCode.expiresInMinutes}분 안에 슬랙 봇 DM에 이 코드를 보내주세요.
                연결이 끝나면 이 화면을 다시 열어 확인할 수 있어요.
              </div>
            </div>
          )}
        </>
      )}

      {error && (
        <div style={{ fontSize: '12px', color: 'var(--danger, #d33)', marginTop: '8px', lineHeight: 1.5 }}>
          {error}
        </div>
      )}
    </div>
  );
}
