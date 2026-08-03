package com.chinazhouwy.miniharness.harness;

@FunctionalInterface
public interface TaskVerifier {

    VerifierResult verify(TaskNode task, TaskExecutionResult result);
}
