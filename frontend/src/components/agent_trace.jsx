import { Wrench, ChevronDown, ChevronUp } from 'lucide-react';
import { agentToolLabel } from '../plan_view';

/**
 * 에이전트 실행 추적 패널 — "누가, 무엇을 근거로 답했는가"를 보여준다.
 * 기본은 접힌 한 줄 요약이고, 펼치면 도구별 인자와 서버가 돌려준 결과 요약을 볼 수 있다.
 * 실행 중(running)에는 결과가 아직 없으므로 상태 점만 다르게 찍는다.
 *
 * profile(v0.17.0)은 서버가 profile 이벤트로 내려준 값 — 이번 실행이 실제로 쓴 페르소나의
 * 증빙이라, 헤더의 로컬 추측 라벨과 어긋나면 이쪽이 맞다.
 */
export default function AgentTrace({ steps, profile, expanded, onToggle }) {
  if (!steps || steps.length === 0) return null;

  const running = steps.some((s) => s.status === 'running');
  const failed = steps.filter((s) => s.status === 'error').length;
  const toolSummary = running
    ? `도구 실행 중… (${steps.length})`
    : `도구 ${steps.length}개 실행${failed > 0 ? ` · ${failed}개 거부됨` : ''}`;
  const summary = profile?.label ? `${profile.label} · ${toolSummary}` : toolSummary;

  return (
    <div style={{
      marginBottom: '6px',
      border: '1px solid var(--border)',
      borderRadius: '10px',
      background: 'var(--bg-card)',
      fontSize: '12px',
      overflow: 'hidden'
    }}>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          padding: '6px 10px',
          background: 'transparent',
          border: 'none',
          color: 'var(--text-muted)',
          cursor: 'pointer',
          textAlign: 'left'
        }}
      >
        <Wrench size={13} />
        <span style={{ flex: 1 }}>{summary}</span>
        {expanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
      </button>

      {expanded && (
        <div style={{ padding: '0 10px 8px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {steps.map((step, index) => (
            <div key={step.id || index} style={{ borderTop: '1px solid var(--border)', paddingTop: '6px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontWeight: 600, color: 'var(--text-main)' }}>
                <span aria-hidden="true">
                  {step.status === 'running' ? '⏳' : step.status === 'ok' ? '✅' : '⛔'}
                </span>
                {agentToolLabel(step.name)}
                <code style={{ fontWeight: 400, color: 'var(--text-muted)', fontSize: '11px' }}>{step.name}</code>
              </div>
              {step.args && Object.keys(step.args).length > 0 && (
                <pre style={traceCodeStyle}>인자 {JSON.stringify(step.args)}</pre>
              )}
              {step.summary && <pre style={traceCodeStyle}>{step.summary}</pre>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const traceCodeStyle = {
  margin: '4px 0 0',
  padding: '5px 7px',
  background: 'var(--bubble-bot)',
  borderRadius: '6px',
  fontSize: '11px',
  lineHeight: '1.45',
  color: 'var(--text-muted)',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all'
};
