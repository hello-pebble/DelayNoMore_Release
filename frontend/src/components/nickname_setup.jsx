import React, { useState } from 'react';
import { validateNickname } from '../nickname';

// 닉네임 설정 화면 — 최초 진입 시 전체 화면 게이트로, "변경" 시 오버레이로 뜬다(App.jsx).
// 닉네임은 화면에 보이는 표시 이름일 뿐 데이터 소유 키가 아니므로, 그 의미(데이터는 이 브라우저에
// 귀속·다른 브라우저에선 별도 보관함)를 화면에서 명확히 안내한다.
export default function NicknameSetup({ initialValue = '', onSubmit, onCancel }) {
  const [value, setValue] = useState(initialValue);
  const [error, setError] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    const result = validateNickname(value);
    if (!result.isValid) {
      setError(result.error);
      return;
    }
    onSubmit(result.value);
  };

  return (
    <div
      style={{
        flex: 1,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--bg-panel)',
        padding: '16px'
      }}
    >
      <form
        onSubmit={handleSubmit}
        className="animate-fade-in"
        style={{
          width: '100%',
          maxWidth: '400px',
          background: 'var(--bg-card)',
          border: '1px solid var(--border)',
          borderRadius: '12px',
          padding: '28px 24px',
          display: 'flex',
          flexDirection: 'column',
          gap: '14px'
        }}
      >
        <div style={{ fontSize: '18px', fontWeight: 700 }}>닉네임 설정</div>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
          닉네임은 화면에 표시되는 이름이에요. 계획·회고 데이터는 이 브라우저에
          귀속되어, 닉네임을 바꿔도 데이터는 그대로 유지됩니다. 다른 브라우저에서는
          같은 닉네임을 써도 별도의 보관함이 됩니다 — 로그인 도입 전 임시 방식이라,
          브라우저 데이터를 지우면 복구할 수 없어요.
        </div>
        <input
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            if (error) setError('');
          }}
          placeholder="한글·영문·숫자 2~20자"
          autoFocus
          style={{
            padding: '10px 12px',
            border: `1px solid ${error ? 'var(--danger)' : 'var(--border)'}`,
            borderRadius: '8px',
            fontSize: '15px',
            outline: 'none'
          }}
        />
        {error && (
          <div style={{ fontSize: '12px', color: 'var(--danger)' }}>{error}</div>
        )}
        <div style={{ display: 'flex', gap: '8px' }}>
          <button
            type="submit"
            style={{
              flex: 1,
              padding: '10px 0',
              background: 'var(--primary)',
              color: '#fff',
              border: 'none',
              borderRadius: '8px',
              fontSize: '15px',
              fontWeight: 600,
              cursor: 'pointer'
            }}
          >
            시작하기
          </button>
          {onCancel && (
            <button
              type="button"
              onClick={onCancel}
              style={{
                padding: '10px 16px',
                background: 'var(--bg-card)',
                color: 'var(--text-muted)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                fontSize: '15px',
                cursor: 'pointer'
              }}
            >
              취소
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
