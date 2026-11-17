package com.chinazhouwy.miniharness.runtime.capability;

import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;

import java.util.List;

/**
 * 路由决策：Harness 对一条用户输入的处置决定。
 * confidence 是路由器自报值，不代表模型概率已校准；needsUserConfirmation 的决策必须等待确认。
 */
public record RoutingDecision(
        RoutingAction action,
        String intent,
        String company,
        TaskId taskId,
        WorkflowRef workflow,
        String skillId,
        boolean needsUserConfirmation,
        String question,
        String reason,
        String resolvedBy,
        List<TaskId> clarificationCandidates
) {
    public static RoutingDecision unmatched(String reason) {
        return new RoutingDecision(RoutingAction.AGENT_EXPLORE, null, null, null, null, null,
                false, null, reason, "fallback", List.of());
    }
}
