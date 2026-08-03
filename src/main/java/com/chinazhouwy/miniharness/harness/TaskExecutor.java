package com.chinazhouwy.miniharness.harness;

@FunctionalInterface
public interface TaskExecutor {

    TaskExecutionResult execute(TaskExecutionContext context);
}
