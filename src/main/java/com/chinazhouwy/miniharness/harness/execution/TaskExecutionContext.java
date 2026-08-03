package com.chinazhouwy.miniharness.harness.execution;

import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;
import com.chinazhouwy.miniharness.harness.domain.Mission;
import com.chinazhouwy.miniharness.harness.domain.TaskAttempt;
import com.chinazhouwy.miniharness.harness.domain.TaskNode;

import java.util.List;
import java.util.Objects;

public record TaskExecutionContext(
        Mission mission,
        TaskNode task,
        List<TaskNode> tasks,
        List<TaskAttempt> attempts,
        List<ArtifactRef> artifacts
) {

    public TaskExecutionContext {
        mission = Objects.requireNonNull(mission, "mission");
        task = Objects.requireNonNull(task, "task");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }
}
