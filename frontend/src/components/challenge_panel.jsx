import React, { useCallback, useEffect, useState } from 'react';
import { Users, Coins } from 'lucide-react';
import { fetchChallenges, joinChallenge } from '../db_service';

// Goal Challenge 패널 — 정원이 한정된 목표 챌린지의 목록·참가(v0.21.0).
// 개설 폼은 없다(v0.23.0): 챌린지는 사용자가 만드는 것이 아니라, 비슷한 조건(기간 + 목적)의
// 체크리스트가 모이면 서버가 계획 고정 시점에 자동으로 연다. 화면이 하는 일은 참가뿐이다.
//
// 이 화면은 판정을 하지 않는다: "정원이 찼는가"를 프론트에서 미리 막지 않고 항상 서버에 요청한 뒤
// err.code로 결과를 읽는다. 화면이 본 인원수는 이미 낡았을 수 있고(다른 사람이 방금 참가),
// 정원 판정의 소유권은 서버의 원자 구간에 있기 때문이다(docs/CONCURRENCY.md).
// 마감된 챌린지의 버튼을 비활성화하는 것은 어디까지나 표시상의 편의일 뿐 방어가 아니다.

const JOIN_ERROR_LABEL = {
  CHALLENGE_FULL: '모집이 마감되었어요. 다른 참가자가 마지막 자리를 가져갔습니다.',
  CHALLENGE_ALREADY_JOINED: '이미 참가한 챌린지예요.',
  POINTS_INSUFFICIENT: '포인트가 부족해 참가할 수 없어요.',
  CHALLENGE_NOT_FOUND: '챌린지를 찾을 수 없어요. 이미 삭제되었을 수 있습니다.'
};

export default function ChallengePanel() {
  const [balance, setBalance] = useState(null);
  const [challenges, setChallenges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [joiningId, setJoiningId] = useState(null);
  const [notice, setNotice] = useState('');

  const reload = useCallback(async () => {
    try {
      const data = await fetchChallenges();
      setBalance(data.balance);
      setChallenges(data.challenges);
    } catch (err) {
      setNotice(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const handleJoin = async (id) => {
    setJoiningId(id);
    setNotice('');
    try {
      const result = await joinChallenge(id);
      setBalance(result.balance);
      setNotice(`참가 완료 — 참가비 ${result.challenge.entryFee}P가 차감되었어요.`);
    } catch (err) {
      setNotice(JOIN_ERROR_LABEL[err.code] || err.message);
    } finally {
      setJoiningId(null);
      // 성공이든 실패든 서버 상태를 다시 읽는다 — 실패 사유가 "남이 먼저 채웠다"인 경우
      // 화면의 인원수도 함께 낡아 있기 때문이다.
      reload();
    }
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, background: 'var(--bg-panel)' }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '14px 16px', borderBottom: '1px solid var(--border)', background: 'var(--bg-card)'
      }}>
        <div style={{ fontSize: '16px', fontWeight: 700 }}>챌린지</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '14px', fontWeight: 600 }}>
          <Coins size={16} />
          {balance == null ? '—' : `${balance.toLocaleString()}P`}
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '12px 16px 24px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
        {notice && (
          <div style={{
            padding: '10px 12px', fontSize: '13px', lineHeight: 1.5, borderRadius: '8px',
            background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-muted)'
          }}>
            {notice}
          </div>
        )}

        {loading && <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>불러오는 중…</div>}

        {!loading && challenges.length === 0 && (
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', lineHeight: 1.6, padding: '24px 0', textAlign: 'center' }}>
            아직 열린 챌린지가 없어요.<br />비슷한 목표·기간의 체크리스트가 모이면<br />챌린지가 자동으로 열려요.
          </div>
        )}

        {challenges.map((c) => (
          <div key={c.id} style={{
            padding: '14px', background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: '10px', display: 'flex', flexDirection: 'column', gap: '10px'
          }}>
            <div style={{ fontSize: '15px', fontWeight: 600 }}>{c.title}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px', fontSize: '13px', color: 'var(--text-muted)' }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                <Users size={14} />
                {c.participantCount}/{c.capacity}명
              </span>
              <span>{c.durationDays}일</span>
              <span>참가비 {c.entryFee}P</span>
            </div>
            <button
              type="button"
              disabled={c.joined || c.full || joiningId === c.id}
              onClick={() => handleJoin(c.id)}
              style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px',
                padding: '9px 0', borderRadius: '8px', border: 'none', fontSize: '14px', fontWeight: 600,
                cursor: c.joined || c.full ? 'default' : 'pointer',
                background: c.joined || c.full ? 'var(--bg-panel)' : 'var(--primary)',
                color: c.joined || c.full ? 'var(--text-muted)' : '#fff'
              }}
            >
              {joiningId === c.id
                ? '참가 요청 중…'
                : c.joined ? '참가 중' : c.full ? '모집 마감' : `참가하기 (남은 자리 ${c.remainingSeats})`}
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
