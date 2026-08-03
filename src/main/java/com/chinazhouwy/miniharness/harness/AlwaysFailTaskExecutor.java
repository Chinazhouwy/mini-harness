package com.chinazhouwy.miniharness.harness;

import java.util.List;

/** 测试替身：始终失败，用于验证 maxAttempts 会把任务阻塞。 */
public final class AlwaysFailTaskExecutor implements TaskExecutor {

    private final String reason;

    public AlwaysFailTaskExecutor() {
        this("AlwaysFailTaskExecutor failure");
    }

    public AlwaysFailTaskExecutor(String reason) {
        this.reason = reason;
    }

    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        return TaskExecutionResult.failure(
                "任务执行失败",
                reason,
                List.of("task=" + context.task().id())
        );
    }
}
