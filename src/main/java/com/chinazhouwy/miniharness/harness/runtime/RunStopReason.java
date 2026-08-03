package com.chinazhouwy.miniharness.harness.runtime;

public enum RunStopReason {
    COMPLETED,
    FAILED,
    BLOCKED,
    CANCELLED,
    WAITING_FOR_INPUT,
    MAX_STEPS,
    NO_SCHEDULABLE_TASKS
}
