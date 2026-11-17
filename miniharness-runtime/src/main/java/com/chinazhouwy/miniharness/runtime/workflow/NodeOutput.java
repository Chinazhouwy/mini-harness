package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeStatus;

import java.util.Map;

/**
 * 节点输出：成功携带数据；SKIPPED 表示该节点未执行且下游也应跳过；
 * FAILED 携带结构化 errorCode，供事件、评测与轨迹分析消费。
 */
public record NodeOutput(NodeStatus status, Map<String, Object> data, String errorCode, String errorMessage) {

    public NodeOutput {
        if (data == null) {
            data = Map.of();
        }
    }

    public static NodeOutput success(Map<String, Object> data) {
        return new NodeOutput(NodeStatus.SUCCEEDED, data, null, null);
    }

    public static NodeOutput skipped(String reason) {
        return new NodeOutput(NodeStatus.SKIPPED, Map.of("reason", reason), "SKIPPED", reason);
    }

    public static NodeOutput failed(String errorCode, String errorMessage) {
        return new NodeOutput(NodeStatus.FAILED, Map.of(), errorCode, errorMessage);
    }
}
