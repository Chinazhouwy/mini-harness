package com.chinazhouwy.miniharness.harness;

import java.util.List;
import java.util.Objects;

/** 事件 payload 的最小稳定结构，避免把整个运行时对象塞进一段自然语言。 */
public final class HarnessEventPayloads {

    private HarnessEventPayloads() {
    }

    public record MissionCreated(Mission mission, List<TaskNode> tasks) {
        public MissionCreated {
            mission = Objects.requireNonNull(mission, "mission");
            tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        }
    }

    public record MissionState(Mission mission) {
        public MissionState {
            mission = Objects.requireNonNull(mission, "mission");
        }
    }

    public record TaskState(
            TaskNode task,
            TaskAttempt attempt,
            List<ArtifactRef> artifacts
    ) {
        public TaskState {
            task = Objects.requireNonNull(task, "task");
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    public record CheckpointSaved(long checkpointSequence) {
        public CheckpointSaved {
            if (checkpointSequence < 0) {
                throw new IllegalArgumentException("Checkpoint sequence must not be negative");
            }
        }
    }
}
