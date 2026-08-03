package com.chinazhouwy.miniharness.harness.demo;

import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionContext;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutor;

import java.util.List;

/** 不调用模型的本地执行器，用来验证 Harness 的调度和恢复。 */
public final class DeterministicTaskExecutor implements TaskExecutor {

    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        String taskId = context.task().id();
        return switch (taskId) {
            case "prepare" -> success(
                    context,
                    "准备阶段完成",
                    "准备阶段已经生成输入摘要"
            );
            case "execute" -> success(
                    context,
                    "执行阶段完成",
                    "执行阶段读取 prepare Artifact 并生成执行结果"
            );
            case "verify" -> verifyInputs(context);
            case "report" -> report(context);
            default -> success(context, "任务 %s 完成".formatted(taskId), "确定性任务已执行");
        };
    }

    private TaskExecutionResult verifyInputs(TaskExecutionContext context) {
        boolean hasPrepare = hasArtifact(context, "prepare");
        boolean hasExecute = hasArtifact(context, "execute");
        if (!hasPrepare || !hasExecute) {
            return TaskExecutionResult.failure(
                    "验证前置条件不满足",
                    "verify 需要 prepare 和 execute Artifact",
                    List.of("prepare=" + hasPrepare, "execute=" + hasExecute)
            );
        }
        return success(context, "验证阶段通过", "prepare 和 execute Artifact 均存在");
    }

    private TaskExecutionResult report(TaskExecutionContext context) {
        if (!hasArtifact(context, "verify")) {
            return TaskExecutionResult.failure(
                    "报告前置条件不满足",
                    "report 需要 verify Artifact",
                    List.of("verify=false")
            );
        }
        return success(context, "最终报告已生成", "报告引用了验证结果");
    }

    private TaskExecutionResult success(TaskExecutionContext context, String summary, String evidence) {
        String taskId = context.task().id();
        ArtifactRef artifact = new ArtifactRef(
                context.mission().id() + "-" + taskId,
                "text",
                "data/harness/artifacts/" + context.mission().id() + "-" + taskId + ".txt",
                summary
        );
        return TaskExecutionResult.success(summary, List.of(evidence), List.of(artifact));
    }

    private static boolean hasArtifact(TaskExecutionContext context, String taskId) {
        String artifactId = context.mission().id() + "-" + taskId;
        return context.artifacts().stream().anyMatch(artifact -> artifact.id().equals(artifactId));
    }
}
