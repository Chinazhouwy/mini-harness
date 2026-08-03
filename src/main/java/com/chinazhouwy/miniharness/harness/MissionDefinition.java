package com.chinazhouwy.miniharness.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MissionDefinition(
        String id,
        String goal,
        List<String> acceptanceCriteria,
        List<TaskNode> tasks
) {

    public MissionDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Mission definition id must not be blank");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("Mission definition goal must not be blank");
        }
        acceptanceCriteria = List.copyOf(Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria"));
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("Mission definition must contain at least one task");
        }

        Set<String> taskIds = new HashSet<>();
        for (TaskNode task : tasks) {
            if (!taskIds.add(task.id())) {
                throw new IllegalArgumentException("Duplicate task id: " + task.id());
            }
        }
        for (TaskNode task : tasks) {
            for (String dependencyId : task.dependencyIds()) {
                if (!taskIds.contains(dependencyId)) {
                    throw new IllegalArgumentException(
                            "Task %s depends on missing task %s".formatted(task.id(), dependencyId)
                    );
                }
            }
        }
    }
}
