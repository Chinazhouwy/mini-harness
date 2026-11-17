package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;

import java.util.Map;

/**
 * 节点声明：类型 + 参数 + 超时/重试。类型由 NodeTypeRegistry 绑定到确定性处理器；
 * 版本演化（插入/删除节点）只作用于节点声明，不修改处理器实现。
 */
public record NodeSpec(
        NodeId id,
        String type,
        Map<String, Object> params,
        int timeoutMs,
        int maxAttempts,
        long retryBackoffMs
) {
    public NodeSpec {
        if (params == null) {
            params = Map.of();
        }
        if (timeoutMs <= 0) {
            timeoutMs = 10_000;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 1;
        }
    }

    public static NodeSpec of(String id, String type) {
        return new NodeSpec(new NodeId(id), type, Map.of(), 10_000, 1, 0);
    }

    public static NodeSpec of(String id, String type, Map<String, Object> params) {
        return new NodeSpec(new NodeId(id), type, params, 10_000, 1, 0);
    }

    public static NodeSpec of(String id, String type, Map<String, Object> params, int timeoutMs, int maxAttempts) {
        return new NodeSpec(new NodeId(id), type, params, timeoutMs, maxAttempts, 100);
    }
}
