package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.ai.service.ChatPatchMerger;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계획 내용을 고치는 도구 — 예전 {@code ===PLAN===} 센티널이 하던 일의 정식 후계다.
 * 모델은 변경된 날짜만 담은 sparse patch를 내고(출력 토큰 절약은 그대로 유지),
 * 병합은 {@link ChatPatchMerger}가 한다 — v0.9.2에서 프론트→서버로 옮긴 병합 소유권을
 * 도구 경로에서도 그대로 재사용하는 것이라, 두 경로의 결과가 어긋날 수 없다.
 *
 * <b>노출 규칙이 이 클래스의 핵심이다.</b> {@link #isAvailableFor}가 PlanStatus의
 * allowsStructuralEdit()를 그대로 참조하므로, 고정(CONFIRMED)·종결 계획에서는 이 도구가
 * 프롬프트에 실리지 않는다. 모델에게 "고치지 마세요"라고 부탁하는 대신 고칠 함수를 주지 않는
 * 방식이라, 예전 프론트의 키워드 휴리스틱 차단(isPlanModificationRequest)이 필요 없어진다.
 *
 * 저장은 하지 않는다 — 병합된 계획을 plan 이벤트로 내보내면 프론트가 초안으로 채택하고
 * 기존 디바운스 PUT이 영속화한다(기존 /chats/stream과 동일한 경로).
 */
@Component
public class UpdatePlanTasksTool implements AgentTool {

    @Override
    public String name() {
        return "update_plan_tasks";
    }

    @Override
    public String description() {
        return "Modify the current plan. Provide a sparse patch: an object mapping ONLY the dates you "
                + "change to their full new task list (array of plain Korean strings). Map a date to null "
                + "to delete that day (shortening the plan). Add new consecutive date keys to extend it. "
                + "Do not include unchanged dates.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("patch", Map.of(
                        "type", "object",
                        "description", "Changed dates only. {\"2026-07-16\": [\"할 일 1\", \"할 일 2\"], "
                                + "\"2026-07-19\": null}. Tasks are plain Korean strings — no ids, no status fields.")),
                "required", List.of("patch"));
    }

    // 계획 내용 수정은 초안(DRAFT)에서만 — 판정 기준은 PlanStatus가 소유하고 여기서는 참조만 한다.
    @Override
    public boolean isAvailableFor(PlanStatus status) {
        return status.allowsStructuralEdit();
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        Map<String, Object> patch = ToolArgs.object(args, "patch");
        if (patch == null || patch.isEmpty()) {
            return ToolResult.fail("patch가 비어 있습니다. 변경할 날짜를 담은 객체를 주세요.");
        }

        Map<String, Object> merged = ChatPatchMerger.merge(context.currentTasks(), patch);
        if (merged == null || merged.isEmpty()) {
            // 병합 결과가 빈 계획 — 모든 날짜를 null로 지운 경우 등. 계획을 통째로 없애는 것은
            // 대화의 수정 범위를 벗어나므로 반영하지 않고 사유를 돌려준다.
            return ToolResult.fail("병합 결과가 빈 계획이라 반영하지 않았습니다. 계획 전체를 지우려면 '처음부터 다시 만들기'를 안내하세요.");
        }

        context.applyTasks(merged);

        // 이 payload의 유일한 독자는 모델이다. 그래서 두 수를 이름으로 갈라놓는다 —
        // 예전의 단일 dayCount는 값이 merged.size()(병합 후 계획 전체 길이)인데 changedDates
        // 옆에 놓여 "바뀐 날짜 수"로 읽혔고, 두 수가 다를 때(2일만 고친 계획 등) 모델이
        // 사용자에게 틀린 개수를 말할 여지가 있었다. carry_over의 movedCount와 결도 맞춘다.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changedDates", List.copyOf(patch.keySet()));
        payload.put("changedCount", patch.size());
        payload.put("totalDayCount", merged.size());
        // 날짜별 할 일을 전부 나열시키면 바로 아래 계획 UI가 이미 보여주는 것을 산문으로 중복한다.
        // 지켜지지 않을 지시를 넣어두면 정말 지켜져야 할 노트(carry_over 등)의 무게까지 같이
        // 떨어지므로, 모델이 실제로 따를 수 있는 선까지만 요구한다.
        payload.put("note", "계획이 갱신됐습니다. 바뀐 날짜를 짚어 알려주고, 할 일 전체를 나열하지는 마세요.");
        return ToolResult.ok(payload);
    }
}
