package com.chinazhouwy.miniharness.runtime.core;

import java.util.UUID;

/** 任务 ID：长期任务的稳定身份，跨 Run 复用。 */
public record TaskId(String value) {
    public static TaskId random() {
        return new TaskId("task-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public String toString() {
        return value;
    }
}
