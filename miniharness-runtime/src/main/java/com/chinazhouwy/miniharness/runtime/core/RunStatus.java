package com.chinazhouwy.miniharness.runtime.core;

import java.util.Set;

/**
 * 一次执行（Run）的生命周期状态机。
 * QUEUED → RUNNING → { SUCCEEDED | FAILED | TIMED_OUT }；QUEUED/RUNNING → CANCELLED。
 * SUCCEEDED 不代表业务结果正确——结果正确性由评测与用户反馈另行判断。
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    private static final Set<RunStatus> QUEUED_NEXT = Set.of(RUNNING, CANCELLED);
    private static final Set<RunStatus> RUNNING_NEXT =
            Set.of(SUCCEEDED, FAILED, TIMED_OUT, CANCELLED);
    private static final Set<RunStatus> TERMINAL_NEXT = Set.of();

    public boolean canTransitionTo(RunStatus target) {
        return switch (this) {
            case QUEUED -> QUEUED_NEXT.contains(target);
            case RUNNING -> RUNNING_NEXT.contains(target);
            case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> TERMINAL_NEXT.contains(target);
        };
    }

    public void requireTransitionTo(RunStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalTransitionException("RunStatus 不允许从 " + this + " 转换到 " + target);
        }
    }

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> true;
            default -> false;
        };
    }
}
