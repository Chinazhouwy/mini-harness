package com.chinazhouwy.miniharness.runtime.core;

import java.util.Set;

/**
 * 任务生命周期状态机。
 * OPEN → ACTIVE → COMPLETED；OPEN/ACTIVE → AWAITING_INPUT（歧义关联、待用户确认）；任何状态 → CANCELLED。
 * 状态转换集中在枚举上维护，任何绕过状态机的写入都应被视为缺陷。
 */
public enum TaskStatus {
    OPEN,
    ACTIVE,
    AWAITING_INPUT,
    COMPLETED,
    CANCELLED;

    private static final Set<TaskStatus> OPEN_NEXT = Set.of(ACTIVE, AWAITING_INPUT, CANCELLED);
    private static final Set<TaskStatus> ACTIVE_NEXT = Set.of(ACTIVE, AWAITING_INPUT, COMPLETED, CANCELLED);
    private static final Set<TaskStatus> AWAITING_NEXT = Set.of(ACTIVE, COMPLETED, CANCELLED);
    private static final Set<TaskStatus> TERMINAL_NEXT = Set.of();

    /** 是否允许从当前状态转换到目标状态。 */
    public boolean canTransitionTo(TaskStatus target) {
        return switch (this) {
            case OPEN -> OPEN_NEXT.contains(target);
            case ACTIVE -> ACTIVE_NEXT.contains(target);
            case AWAITING_INPUT -> AWAITING_NEXT.contains(target);
            case COMPLETED, CANCELLED -> TERMINAL_NEXT.contains(target);
        };
    }

    /** 强制状态转换，非法转换抛出异常（可验证的失败边界）。 */
    public void requireTransitionTo(TaskStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalTransitionException("TaskStatus 不允许从 " + this + " 转换到 " + target);
        }
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
