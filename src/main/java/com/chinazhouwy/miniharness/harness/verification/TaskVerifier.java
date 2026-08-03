package com.chinazhouwy.miniharness.harness.verification;

import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;

@FunctionalInterface
public interface TaskVerifier {

    VerifierResult verify(TaskNode task, TaskExecutionResult result);
}
