package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.NodeStatus;

import java.time.Instant;
import java.util.Map;

/** 单个节点的一次执行结果：状态、输出数据、错误信息与耗时。 */
public record NodeResult(
        NodeId nodeId,
        NodeStatus status,
        Map<String, Object> output,
        String errorCode,
        String errorMessage,
        int attempts,
        Instant startedAt,
        Instant finishedAt,
        long durationMs
) {
    public NodeResult {
        if (output == null) {
            output = Map.of();
        }
    }
}
