package com.chinazhouwy.miniharness.harness;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Orchestrator 读到的状态视图；它不等同于模型的上下文历史。 */
public record MissionSnapshot(
        Mission mission,
        List<TaskNode> tasks,
        List<TaskAttempt> attempts,
        List<ArtifactRef> artifacts,
        long lastEventSequence,
        Instant savedAt
) {

    public MissionSnapshot {
        mission = Objects.requireNonNull(mission, "mission");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("Mission snapshot sequence must not be negative");
        }
        savedAt = Objects.requireNonNull(savedAt, "savedAt");
    }

    public TaskNode task(String taskId) {
        return tasks.stream()
                .filter(task -> task.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
    }

    public List<TaskAttempt> attemptsFor(String taskId) {
        return attempts.stream().filter(attempt -> attempt.taskId().equals(taskId)).toList();
    }

    public Checkpoint toCheckpoint() {
        return new Checkpoint(mission, tasks, attempts, artifacts, lastEventSequence, Instant.now());
    }

    public MissionSnapshot withMission(Mission nextMission) {
        return new MissionSnapshot(nextMission, tasks, attempts, artifacts, lastEventSequence, savedAt);
    }

    public MissionSnapshot withState(
            List<TaskNode> nextTasks,
            List<TaskAttempt> nextAttempts,
            List<ArtifactRef> nextArtifacts
    ) {
        return new MissionSnapshot(mission, nextTasks, nextAttempts, nextArtifacts, lastEventSequence, savedAt);
    }

    public MissionSnapshot withLastEventSequence(long sequence) {
        return new MissionSnapshot(mission, tasks, attempts, artifacts, sequence, savedAt);
    }
}
