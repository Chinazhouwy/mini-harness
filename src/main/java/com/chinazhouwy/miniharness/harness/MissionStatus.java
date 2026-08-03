package com.chinazhouwy.miniharness.harness;

/** 生命周期属于任务编排，不属于 ChatMemory。 */
public enum MissionStatus {
    CREATED,
    READY,
    RUNNING,
    WAITING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == BLOCKED || this == CANCELLED;
    }
}
