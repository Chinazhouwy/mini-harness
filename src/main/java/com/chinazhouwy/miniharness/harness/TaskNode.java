package com.chinazhouwy.miniharness.harness;

import java.util.List;
import java.util.Objects;

public record TaskNode(
        String id,
        String title,
        List<String> dependencyIds,
        TaskStatus status,
        int maxAttempts,
        String inputSummary,
        String outputSummary
) {

    public TaskNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title must not be blank");
        }
        dependencyIds = List.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        status = Objects.requireNonNull(status, "status");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Task maxAttempts must be positive");
        }
    }

    public TaskNode transitionTo(TaskStatus nextStatus) {
        StateTransitions.requireTaskTransition(status, nextStatus);
        return new TaskNode(id, title, dependencyIds, nextStatus, maxAttempts, inputSummary, outputSummary);
    }

    public TaskNode withInputSummary(String nextInputSummary) {
        return new TaskNode(id, title, dependencyIds, status, maxAttempts, nextInputSummary, outputSummary);
    }

    public TaskNode withOutputSummary(String nextOutputSummary) {
        return new TaskNode(id, title, dependencyIds, status, maxAttempts, inputSummary, nextOutputSummary);
    }
}
