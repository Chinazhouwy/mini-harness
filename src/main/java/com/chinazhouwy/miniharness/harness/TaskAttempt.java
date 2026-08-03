package com.chinazhouwy.miniharness.harness;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TaskAttempt(
        String taskId,
        int attemptNumber,
        TaskAttemptStatus status,
        String summary,
        List<String> evidence,
        List<ArtifactRef> artifacts,
        Instant startedAt,
        Instant finishedAt
) {

    public TaskAttempt {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task attempt taskId must not be blank");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Task attempt number must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        summary = Objects.requireNonNullElse(summary, "");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public TaskAttempt withStatus(
            TaskAttemptStatus nextStatus,
            String nextSummary,
            List<String> nextEvidence,
            List<ArtifactRef> nextArtifacts,
            Instant nextFinishedAt
    ) {
        StateTransitions.requireAttemptTransition(status, nextStatus);
        return new TaskAttempt(
                taskId,
                attemptNumber,
                nextStatus,
                nextSummary,
                nextEvidence,
                nextArtifacts,
                startedAt,
                nextFinishedAt
        );
    }
}
