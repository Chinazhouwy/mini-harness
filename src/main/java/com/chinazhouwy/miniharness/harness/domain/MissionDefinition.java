package com.chinazhouwy.miniharness.harness.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        Map<String, List<String>> dependenciesById = new HashMap<>();
        for (TaskNode task : tasks) {
            for (String dependencyId : task.dependencyIds()) {
                if (!taskIds.contains(dependencyId)) {
                    throw new IllegalArgumentException(
                            "Task %s depends on missing task %s".formatted(task.id(), dependencyId)
                    );
                }
            }
            dependenciesById.put(task.id(), task.dependencyIds());
        }

        // 环会让任务永远无法进入 READY，必须在定义期拒绝而不是运行时变成难以理解的 BLOCKED。
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (TaskNode task : tasks) {
            detectCycle(task.id(), dependenciesById, visiting, visited);
        }
    }

    private static void detectCycle(
            String taskId,
            Map<String, List<String>> dependenciesById,
            Set<String> visiting,
            Set<String> visited
    ) {
        if (visited.contains(taskId)) {
            return;
        }
        if (!visiting.add(taskId)) {
            throw new IllegalArgumentException("Task dependency cycle detected at: " + taskId);
        }
        for (String dependencyId : dependenciesById.get(taskId)) {
            detectCycle(dependencyId, dependenciesById, visiting, visited);
        }
        visiting.remove(taskId);
        visited.add(taskId);
    }
}
