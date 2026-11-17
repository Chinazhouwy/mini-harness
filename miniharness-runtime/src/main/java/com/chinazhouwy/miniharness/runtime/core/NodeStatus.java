package com.chinazhouwy.miniharness.runtime.core;

import java.util.Set;

/**
 * Workflow 节点状态。SKIPPED 表示因上游失败/跳过而未执行（与 FAILED 明确区分，评测统计依赖该区分）。
 */
public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    private static final Set<NodeStatus> PENDING_NEXT = Set.of(RUNNING, SKIPPED);
    private static final Set<NodeStatus> RUNNING_NEXT = Set.of(SUCCEEDED, FAILED, SKIPPED);
    private static final Set<NodeStatus> TERMINAL_NEXT = Set.of();

    public boolean canTransitionTo(NodeStatus target) {
        return switch (this) {
            case PENDING -> PENDING_NEXT.contains(target);
            case RUNNING -> RUNNING_NEXT.contains(target);
            case SUCCEEDED, FAILED, SKIPPED -> TERMINAL_NEXT.contains(target);
        };
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}
