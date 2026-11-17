package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次 Workflow 执行的结果：整体结局 + 各节点结果 + 失败摘要 + 耗时。
 * outcome 直接映射 RunStatus（SUCCEEDED/FAILED/TIMED_OUT/CANCELLED），便于 Run 状态机落账。
 */
public record WorkflowExecutionResult(
        RunStatus outcome,
        List<NodeResult> nodeResults,
        String errorCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        long durationMs
) {
    public WorkflowExecutionResult {
        nodeResults = List.copyOf(nodeResults);
    }

    public Map<NodeId, NodeResult> byNode() {
        Map<NodeId, NodeResult> map = new java.util.LinkedHashMap<>();
        for (NodeResult r : nodeResults) {
            map.put(r.nodeId(), r);
        }
        return map;
    }

    public NodeResult firstFailed() {
        for (NodeResult r : nodeResults) {
            if (r.status() == com.chinazhouwy.miniharness.runtime.core.NodeStatus.FAILED) {
                return r;
            }
        }
        return null;
    }
}
