package com.chinazhouwy.miniharness.harness.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 写入磁盘的可恢复快照。 */
public record Checkpoint(
        Mission mission,
        List<TaskNode> tasks,
        List<TaskAttempt> attempts,
        List<ArtifactRef> artifacts,
        long lastEventSequence,
        Instant savedAt
) {

    public Checkpoint {
        mission = Objects.requireNonNull(mission, "mission");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("Checkpoint sequence must not be negative");
        }
        savedAt = Objects.requireNonNull(savedAt, "savedAt");
    }

    public MissionSnapshot snapshot() {
        return new MissionSnapshot(mission, tasks, attempts, artifacts, lastEventSequence, savedAt);
    }
}
