package com.chinazhouwy.miniharness.harness;

import java.util.HashSet;
import java.util.Set;

/** 测试替身：每个目标任务第一次执行失败，之后交给真实替身执行。 */
public final class FailOnceTaskExecutor implements TaskExecutor {

    private final TaskExecutor delegate;
    private final Set<String> onlyTaskIds;
    private final Set<String> failedTaskIds = new HashSet<>();

    public FailOnceTaskExecutor() {
        this(new DeterministicTaskExecutor(), Set.of());
    }

    public FailOnceTaskExecutor(TaskExecutor delegate) {
        this(delegate, Set.of());
    }

    public FailOnceTaskExecutor(TaskExecutor delegate, Set<String> onlyTaskIds) {
        this.delegate = delegate;
        this.onlyTaskIds = Set.copyOf(onlyTaskIds);
    }

    @Override
    public synchronized TaskExecutionResult execute(TaskExecutionContext context) {
        String taskId = context.task().id();
        if ((onlyTaskIds.isEmpty() || onlyTaskIds.contains(taskId)) && failedTaskIds.add(taskId)) {
            return TaskExecutionResult.failure(
                    "故意制造的一次性失败",
                    "FailOnceTaskExecutor first attempt failure",
                    Set.of("task=" + taskId, "firstAttempt=true").stream().toList()
            );
        }
        return delegate.execute(context);
    }
}
