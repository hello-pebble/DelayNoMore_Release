// 계획의 서버 동기화 — 보관함 목록, 자동 저장(디바운스 PUT), 최초 보관, 서버 응답 반영,
// 언마운트 이후 쓰기 차단을 한곳에 모은다. 화면(chat_coach.jsx)은 여기서 돌려주는 함수만 부르고
// 타이머·플래그(dirty/lastSynced/archivePending)를 직접 만지지 않는다.
import { useEffect, useRef, useState } from 'react';
import { createPlan, updatePlan, fetchPlan, fetchPlans } from './db_service';
import {
  toPlanPayload, fromPlanResponse,
  readLastViewedPlanId, writeLastViewedPlanId, clearLastViewedPlanId
} from './plan_view';

// 서버 자동 동기화 디바운스 — 완료 토글 연타나 스트리밍 수정이 요청 폭주로 이어지지 않게 한다.
const SYNC_DEBOUNCE_MILLIS = 600;

/**
 * @param draftChecklist  현재 화면의 계획(라이브 상태) — 이 값이 바뀌면 디바운스 후 서버로 보낸다
 * @param activePlanId    현재 계획의 서버 ID (null = 아직 미보관)
 * @param notify          안내 말풍선 추가(보관 한도 초과 등 사용자에게 알릴 실패)
 * @param onRestore       마운트 시 "마지막으로 보던 계획"을 찾았을 때 호출 — 복원 여부는 화면이 결정
 */
export function usePlanSync({
  draftChecklist, activePlanId,
  setSlots, setDraftChecklist, setActivePlanId, setTodayDashboard,
  notify, onRestore
}) {
  const [savedPlans, setSavedPlans] = useState([]); // GET /plans 결과 (최근 저장순)
  const [plansStatus, setPlansStatus] = useState('idle'); // 'idle' | 'loading' | 'ready' | 'error'

  const syncTimerRef = useRef(null); // 서버 자동 동기화 디바운스 타이머
  const dirtyRef = useRef(null); // 아직 서버에 반영 안 된 최신 변경 { id, payload }
  const lastSyncedRef = useRef(null); // 서버에 있는 것으로 아는 payload의 JSON — 불필요한 재전송(no-op PUT) 억제
  const archivePendingRef = useRef(false); // 초안이 아직 보관되지 못해(서버 미가용) 재시도가 필요한 상태
  const aliveRef = useRef(true); // 언마운트 후 서버 쓰기·상태 갱신 차단 — 긴 비동기 흐름(스트리밍·재시도)이
                                 // 뒤늦게 데이터를 생성/부활시키지 않게. (향후 로그인 시 소유자 전환 대비:
                                 // 지금은 게스트 ID가 안정이라 요청 도중 소유자가 바뀌지 않지만, memberId
                                 // 전환을 도입하면 이 가드가 전환 경계도 지킨다. AbortController를 db_service에
                                 // 스레딩하는 방식은 이 코드베이스엔 과하다고 판단해 두지 않는다.)

  // 언마운트 이후 서버 쓰기·상태 갱신 차단. 마운트마다 플래그를 되올리는 이유는 StrictMode의
  // 마운트→cleanup→재마운트 사이클 뒤에도 false로 남으면 모든 갱신이 영구히 막히기 때문이다.
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
    };
  }, []);

  // 서버와 같은 내용임을 기록한다 — 복원·보관 직후의 no-op PUT과, 낡은 대기 변경의 재전송을 막는다.
  const markSynced = (checklist) => {
    lastSyncedRef.current = JSON.stringify(toPlanPayload(checklist));
    dirtyRef.current = null;
    archivePendingRef.current = false;
  };

  // 보관함 목록 갱신 — 실패해도 앱은 계속 동작한다(목록만 error 표시, 생성/대화는 mock 폴백).
  const refreshPlans = async () => {
    setPlansStatus('loading');
    try {
      setSavedPlans(await fetchPlans());
      setPlansStatus('ready');
    } catch {
      setPlansStatus('error');
    }
  };

  // 대기 중인 자동 동기화(디바운스 타이머·미반영 변경·재보관 대기)를 모두 취소한다.
  // 활성 계획을 삭제할 때 호출해, 방금 지운 계획이 뒤늦은 PUT의 404→재생성으로 되살아나지 않게 한다.
  const cancelPendingSync = () => {
    if (syncTimerRef.current) {
      clearTimeout(syncTimerRef.current);
      syncTimerRef.current = null;
    }
    dirtyRef.current = null;
    archivePendingRef.current = false;
  };

  // 서버 도메인 액션 응답(PlanResponse)을 화면 상태에 반영한다 — 전이(confirm/complete/cancel)와
  // 이월(carry-over), 지난 날짜 잠금 되돌리기(syncActivePlan)가 공유한다. setState 전에 "이미
  // 동기화됨"(lastSyncedRef)으로 기록해, 낡은 디바운스 PUT이 서버 결과를 되돌리는 경합을
  // 차단한다(restorePlan의 "방금 서버에서 읽은 상태" 처리와 같은 패턴). 활성 계획이 아니면
  // 목록 스냅샷만 갱신한다.
  const applyServerPlan = (plan) => {
    setTodayDashboard(null);
    if (plan.id === activePlanId) {
      const { slots: nextSlots, draftChecklist: nextDraft } = fromPlanResponse(plan);
      lastSyncedRef.current = JSON.stringify(toPlanPayload(nextDraft));
      dirtyRef.current = null;
      setDraftChecklist(nextDraft);
      // 요약 헤더·AI 슬롯의 "기간 N일"이 어긋나지 않게 함께 갱신(채팅 기간 수정과 동일 처리).
      setSlots(nextSlots);
    }
    // 목록 스냅샷에도 반영 — 응답 plan은 목록 항목(PlanResponse)과 같은 구조다.
    setSavedPlans((prev) => prev.map((p) => (p.id === plan.id ? plan : p)));
  };

  // 대기 중인 변경을 즉시 서버에 반영한다(디바운스를 기다리지 않고). 계획 전환·리셋 직전에
  // 호출해, 아직 PUT되지 않은 완료 토글/수정이 유실되지 않게 한다.
  // recreateIfMissing=false: 떠나는 계획이 이미 삭제됐어도 새로 만들지 않는다(orphan 방지).
  const syncActivePlan = async ({ recreateIfMissing }) => {
    if (syncTimerRef.current) {
      clearTimeout(syncTimerRef.current);
      syncTimerRef.current = null;
    }
    const pending = dirtyRef.current;
    if (!pending) return;
    dirtyRef.current = null;
    try {
      await updatePlan(pending.id, pending.payload);
      lastSyncedRef.current = JSON.stringify(pending.payload);
    } catch (err) {
      if (err.code === 'PLAN_LOCKED') {
        // 서버 가드 거부 — 다른 세션에서 먼저 고정(CONFIRMED)한 계획에 구조 변경 PUT이 겹친
        // 경우다. 재시도해도 계속 409이므로 대기 변경을 버린다(서버가 진실 원천 — 계획을
        // 다시 불러오면 고정 상태로 재동기화된다).
        console.warn('고정된 계획이라 서버가 수정 반영을 거부했습니다 — 대기 중 변경을 폐기합니다.');
        return;
      }
      if (err.code === 'PAST_TASK_LOCKED') {
        // 지난 날짜 토글 소급 거부 — 체크박스 disabled를 뚫고 온 경우(자정 경계에 열려 있던
        // 화면 등). 재시도해도 계속 409이므로 대기 변경을 버리고, 서버 상태로 화면을 되돌려
        // 낙관 반영된 체크가 남지 않게 한다.
        window.alert('지난 날짜의 완료 체크는 변경할 수 없어요. 화면을 서버 상태로 되돌립니다.');
        try {
          const fresh = await fetchPlan(pending.id);
          if (aliveRef.current) applyServerPlan(fresh);
        } catch {
          /* 되돌리기 실패 — 다음 조회 때 재동기화된다 */
        }
        return;
      }
      if (err.code !== 'PLAN_NOT_FOUND') {
        console.warn('계획 동기화 실패 — 다음 변경 때 다시 시도합니다:', err);
        dirtyRef.current = pending; // 일시 오류: 되돌려 놔 다음 변경/전환에서 재시도
        return;
      }
      if (!recreateIfMissing || !aliveRef.current) return;
      // 백그라운드 동기화 중 대상이 사라짐(내가 다른 탭에서 삭제·서버 재시작) — 작업을 잃지 않게
      // 새로 보관하되, 언마운트 후라면 만들지 않는다(떠난 화면이 데이터를 되살리지 않게).
      try {
        const saved = await createPlan(pending.payload);
        if (!aliveRef.current) return;
        lastSyncedRef.current = JSON.stringify(pending.payload);
        setActivePlanId(saved.id);
        writeLastViewedPlanId(saved.id);
      } catch {
        setActivePlanId(null);
        clearLastViewedPlanId();
      }
    }
  };

  // 마운트 시 1회 — 보관함 목록을 불러오고, "마지막으로 보던 계획" 포인터가 유효하면 복원한다.
  // 서버가 죽어 있어도 새 계획 생성·대화는 mock 폴백으로 계속 가능해야 하므로 실패는 삼킨다.
  // StrictMode 이중 실행에도 멱등(같은 목록/같은 복원)이고, cancelled 플래그로 늦은 응답을 무시한다.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const plans = await fetchPlans();
        if (cancelled) return;
        setSavedPlans(plans);
        setPlansStatus('ready');
        const lastId = readLastViewedPlanId();
        const found = lastId != null && plans.find((p) => String(p.id) === lastId);
        if (found) {
          onRestore(found); // 복원 여부(사용자가 이미 입력을 시작했는가)는 화면이 판단한다
        } else if (lastId != null) {
          clearLastViewedPlanId(); // 다른 방문자가 지웠거나 서버가 재시작된 경우
        }
      } catch {
        if (!cancelled) setPlansStatus('error');
      }
    })();
    return () => {
      cancelled = true;
    };
    // 마운트 1회만 — 첫 렌더의 onRestore를 쓴다(복원은 한 번뿐이라 최신 참조가 필요 없다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 초안이 완성되면(또는 서버 미가용으로 실패했던 보관을 재시도할 때) 서버 보관함에 등록한다.
  // 성공하면 활성 계획이 되고, 서버가 죽어 있으면 재시도 대기 상태로, 한도 초과면 안내만 한다.
  const archiveNewPlan = async (checklist) => {
    if (!aliveRef.current) return; // 언마운트 후엔 서버에 새 계획을 만들지 않는다(재시도 경로 포함)
    const payload = toPlanPayload(checklist);
    try {
      const saved = await createPlan(payload);
      if (!aliveRef.current) return; // 응답이 늦게 와도 떠난 화면의 상태를 갱신하지 않는다
      archivePendingRef.current = false;
      lastSyncedRef.current = JSON.stringify(payload); // 방금 보관 → no-op PUT 억제
      setActivePlanId(saved.id);
      writeLastViewedPlanId(saved.id);
      refreshPlans();
    } catch (err) {
      if (!aliveRef.current) return;
      if (err.code === 'PLAN_LIMIT_EXCEEDED') {
        archivePendingRef.current = false; // 내 보관함 한도 초과는 재시도해도 소용없으니 포기하고 안내만
        notify('⚠️ 내 보관함이 가득 차서(최대 10개) 이 계획은 저장되지 않았어요. 체크리스트 탭의 "보관된 계획" 목록에서 오래된 계획을 삭제하면 다음 계획부터 다시 보관됩니다.');
      } else if (err.code === 'PLAN_DAILY_LIMIT_EXCEEDED') {
        archivePendingRef.current = false; // 하루 생성 한도 — 오늘은 재시도해도 소용없으니 포기하고 안내만
        notify('⚠️ 오늘 만들 수 있는 계획(5개)을 모두 사용해서 이 계획은 저장되지 않았어요. 내일 다시 만들어 주세요.');
      } else if (err.code === 'PLAN_STORE_FULL') {
        archivePendingRef.current = false; // 서버 전역 상한 — 잠시 후 재시도 안내
        notify('⚠️ 데모 서버 저장 공간이 가득 차서 이 계획은 보관되지 않았어요. 잠시 후 다시 시도해 주세요.');
      } else {
        // 서버 미가용 등 일시 오류 — 재시도 대기로 표시해, 서버 복구 후 다음 변경 때 다시 보관한다.
        archivePendingRef.current = true;
        console.warn('계획 보관 실패(서버 미가용?) — 다음 변경 때 재시도합니다:', err);
      }
    }
  };

  // 자동 동기화 — 계획 변경(대화 수정·완료 토글·고정)을 600ms 디바운스로 서버에 반영한다.
  // 다른 브라우저에서 목록을 열면 진행률·고정 상태가 갱신되어 보인다(원격 시연 핵심).
  // 이미 서버에 있는 내용과 같으면(복원/보관 직후) 아무것도 보내지 않는다(no-op PUT 억제).
  // cleanup에서 타이머를 지우지 않는 이유: dep 변경(전환 등)에 취소되면 대기 중 변경이 유실되기
  // 때문. 전환/리셋은 syncActivePlan으로 먼저 flush하고, 삭제는 cancelPendingSync로 취소한다.
  useEffect(() => {
    if (!draftChecklist) return;
    const payloadStr = JSON.stringify(toPlanPayload(draftChecklist));
    if (payloadStr === lastSyncedRef.current) {
      // 현재 상태가 서버와 동일(복원/보관 직후, 또는 토글을 되돌림) — 대기 중이던 이전 변경도
      // 무의미하니 함께 취소한다(낡은 상태가 뒤늦게 PUT되는 것을 막는다).
      if (syncTimerRef.current) {
        clearTimeout(syncTimerRef.current);
        syncTimerRef.current = null;
      }
      dirtyRef.current = null;
      return;
    }

    if (activePlanId != null) {
      dirtyRef.current = { id: activePlanId, payload: JSON.parse(payloadStr) };
      if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
      syncTimerRef.current = setTimeout(() => {
        syncTimerRef.current = null;
        syncActivePlan({ recreateIfMissing: true });
      }, SYNC_DEBOUNCE_MILLIS);
    } else if (archivePendingRef.current) {
      // 미보관 초안(이전 보관 실패) — 변경이 생기면 서버가 살아났는지 다시 시도한다.
      if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
      const snapshot = draftChecklist;
      syncTimerRef.current = setTimeout(() => {
        syncTimerRef.current = null;
        archiveNewPlan(snapshot);
      }, SYNC_DEBOUNCE_MILLIS);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftChecklist, activePlanId]);

  return {
    savedPlans, setSavedPlans, plansStatus, refreshPlans, aliveRef,
    applyServerPlan, syncActivePlan, cancelPendingSync, archiveNewPlan, markSynced
  };
}
