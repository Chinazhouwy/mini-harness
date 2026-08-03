package com.chinazhouwy.miniharness.harness;

public enum TaskStatus {
    PENDING,
    READY,
    RUNNING,
    SUBMITTED,
    ACCEPTED,
    RETRYABLE,
    BLOCKED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == ACCEPTED || this == BLOCKED || this == FAILED || this == CANCELLED;
    }
}
