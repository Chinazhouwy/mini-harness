package com.chinazhouwy.miniharness.harness.demo;

import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;
import com.chinazhouwy.miniharness.harness.verification.TaskVerifier;
import com.chinazhouwy.miniharness.harness.verification.VerifierResult;

import java.util.List;

/** Demo 验证器：成功必须有产物，执行器失败只能进入重试或阻塞。 */
public final class DemoTaskVerifier implements TaskVerifier {

    @Override
    public VerifierResult verify(TaskNode task, TaskExecutionResult result) {
        if (!result.success()) {
            String reason = result.error().isBlank() ? result.summary() : result.error();
            return VerifierResult.retry(
                    reason,
                    List.of("task=" + task.id(), "executorSuccess=false")
            );
        }
        if (result.artifacts().isEmpty()) {
            return VerifierResult.retry(
                    "成功结果没有可复用 Artifact",
                    List.of("task=" + task.id(), "artifactCount=0")
            );
        }
        return VerifierResult.pass(
                "执行结果通过最小验证",
                List.of("task=" + task.id(), "artifactCount=" + result.artifacts().size())
        );
    }
}
