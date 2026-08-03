package com.chinazhouwy.miniharness.harness.execution;

@FunctionalInterface
public interface TaskExecutor {

    TaskExecutionResult execute(TaskExecutionContext context);
}
