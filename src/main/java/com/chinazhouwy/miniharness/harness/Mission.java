package com.chinazhouwy.miniharness.harness;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Mission(
        String id,
        String goal,
        List<String> acceptanceCriteria,
        MissionStatus status,
        int revision,
        Instant createdAt,
        Instant updatedAt
) {

    public Mission {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Mission id must not be blank");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("Mission goal must not be blank");
        }
        acceptanceCriteria = List.copyOf(Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria"));
        status = Objects.requireNonNull(status, "status");
        if (revision < 0) {
            throw new IllegalArgumentException("Mission revision must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public Mission transitionTo(MissionStatus nextStatus) {
        return transitionTo(nextStatus, Instant.now());
    }

    public Mission transitionTo(MissionStatus nextStatus, Instant changedAt) {
        Objects.requireNonNull(nextStatus, "nextStatus");
        Objects.requireNonNull(changedAt, "changedAt");
        StateTransitions.requireMissionTransition(status, nextStatus);
        return new Mission(id, goal, acceptanceCriteria, nextStatus, revision + 1, createdAt, changedAt);
    }
}
