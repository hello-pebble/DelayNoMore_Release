import React, { useState } from 'react';
import { validateNickname } from '../nickname';

// 닉네임 설정 게이트 — 닉네임이 없으면 앱(ChatCoach) 대신 이 화면이 먼저 뜬다.
// 헤더의 "변경" 버튼으로도 열린다(initialValue 프리필 + onCancel 취소 버튼).
// 닉네임은 로그인 전 간이 계정 키라, 화면에서 그 의미(같은 닉네임 = 같은 보관함)를 명시한다.
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
          계획·회고는 닉네임별로 구분됩니다. 같은 닉네임을 입력하면 어느
          브라우저에서든 같은 보관함을 보게 됩니다 — 로그인 도입 전 임시
          방식이라 비밀번호는 없어요.
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
