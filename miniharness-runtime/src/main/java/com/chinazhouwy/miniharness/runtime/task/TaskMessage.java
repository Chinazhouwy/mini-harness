package com.chinazhouwy.miniharness.runtime.task;

import com.chinazhouwy.miniharness.runtime.core.RunId;

import java.time.Instant;

/** 任务内消息：用户输入、Harness 答复或追加信息；质疑类消息可关联被核查的 Run。 */
public record TaskMessage(Role role, String content, Instant at, RunId linkedRunId) {

    public enum Role { USER, HARNESS }

    public static TaskMessage user(String content) {
        return new TaskMessage(Role.USER, content, Instant.now(), null);
    }

    public static TaskMessage user(String content, RunId linkedRunId) {
        return new TaskMessage(Role.USER, content, Instant.now(), linkedRunId);
    }

    public static TaskMessage harness(String content) {
        return new TaskMessage(Role.HARNESS, content, Instant.now(), null);
    }
}
