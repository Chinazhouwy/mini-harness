package com.chinazhouwy.miniharness.harness;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将事件应用到快照；磁盘恢复和运行时更新共用这一个受控入口。 */
public final class StateReducer {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private StateReducer() {
    }

    public static MissionSnapshot apply(MissionSnapshot current, HarnessEvent event) {
        if (current != null && event.sequence() <= current.lastEventSequence()) {
            throw new IllegalArgumentException(
                    "Event sequence %d is not after snapshot sequence %d"
                            .formatted(event.sequence(), current.lastEventSequence())
            );
        }
        if (!event.missionId().equals(current == null ? missionIdFromCreatedEvent(event) : current.mission().id())) {
            throw new IllegalArgumentException("Event mission id does not match snapshot: " + event.missionId());
        }

        HarnessEventType eventType;
        try {
            eventType = HarnessEventType.valueOf(event.type());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown harness event type: " + event.type(), exception);
        }

        if (eventType == HarnessEventType.MISSION_CREATED) {
            return applyMissionCreated(current, event);
        }
        if (current == null) {
            throw new IllegalArgumentException("Cannot apply %s without a created mission".formatted(event.type()));
        }
        if (eventType == HarnessEventType.CHECKPOINT_SAVED) {
            return current.withLastEventSequence(event.sequence());
        }
        if (isMissionEvent(eventType)) {
            return applyMissionState(current, event);
        }
        return applyTaskState(current, event);
    }

    private static MissionSnapshot applyMissionCreated(MissionSnapshot current, HarnessEvent event) {
        if (current != null && current.lastEventSequence() > 0) {
            throw new IllegalArgumentException("A mission can only be created once");
        }
        HarnessEventPayloads.MissionCreated payload = read(event.payload(), HarnessEventPayloads.MissionCreated.class);
        if (!event.missionId().equals(payload.mission().id())) {
            throw new IllegalArgumentException("Created mission id does not match event mission id");
        }
        Instant savedAt = event.createdAt();
        return new MissionSnapshot(
                payload.mission(),
                payload.tasks(),
                List.of(),
                List.of(),
                event.sequence(),
                savedAt
        );
    }

    private static MissionSnapshot applyMissionState(MissionSnapshot current, HarnessEvent event) {
        HarnessEventPayloads.MissionState payload = read(event.payload(), HarnessEventPayloads.MissionState.class);
        Mission nextMission = payload.mission();
        if (!current.mission().id().equals(nextMission.id())) {
            throw new IllegalArgumentException("Mission state payload id does not match snapshot");
        }
        if (current.mission().status() != nextMission.status()) {
            StateTransitions.requireMissionTransition(current.mission().status(), nextMission.status());
        }
        return current.withMission(nextMission).withLastEventSequence(event.sequence());
    }

    private static MissionSnapshot applyTaskState(MissionSnapshot current, HarnessEvent event) {
        HarnessEventPayloads.TaskState payload = read(event.payload(), HarnessEventPayloads.TaskState.class);
        TaskNode nextTask = payload.task();
        TaskNode previousTask = current.task(nextTask.id());
        if (previousTask.status() != nextTask.status()) {
            StateTransitions.requireTaskTransition(previousTask.status(), nextTask.status());
        }

        List<TaskNode> nextTasks = replaceTask(current.tasks(), nextTask);
        List<TaskAttempt> nextAttempts = upsertAttempt(current.attempts(), payload.attempt());
        List<ArtifactRef> nextArtifacts = mergeArtifacts(current.artifacts(), payload.artifacts());
        return current.withState(nextTasks, nextAttempts, nextArtifacts)
                .withLastEventSequence(event.sequence());
    }

    private static boolean isMissionEvent(HarnessEventType eventType) {
        return switch (eventType) {
            case MISSION_READY,
                    MISSION_STARTED,
                    MISSION_WAITING,
                    MISSION_VERIFYING,
                    MISSION_COMPLETED,
                    MISSION_FAILED,
                    MISSION_BLOCKED,
                    MISSION_CANCELLED -> true;
            default -> false;
        };
    }

    private static List<TaskNode> replaceTask(List<TaskNode> tasks, TaskNode replacement) {
        List<TaskNode> result = new ArrayList<>(tasks.size());
        boolean replaced = false;
        for (TaskNode task : tasks) {
            if (task.id().equals(replacement.id())) {
                result.add(replacement);
                replaced = true;
            } else {
                result.add(task);
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("Unknown task in event: " + replacement.id());
        }
        return List.copyOf(result);
    }

    private static List<TaskAttempt> upsertAttempt(List<TaskAttempt> attempts, TaskAttempt replacement) {
        if (replacement == null) {
            return attempts;
        }
        List<TaskAttempt> result = new ArrayList<>(attempts.size() + 1);
        boolean replaced = false;
        for (TaskAttempt attempt : attempts) {
            if (attempt.taskId().equals(replacement.taskId())
                    && attempt.attemptNumber() == replacement.attemptNumber()) {
                result.add(replacement);
                replaced = true;
            } else {
                result.add(attempt);
            }
        }
        if (!replaced) {
            result.add(replacement);
        }
        return List.copyOf(result);
    }

    private static List<ArtifactRef> mergeArtifacts(
            List<ArtifactRef> current,
            List<ArtifactRef> additions
    ) {
        Map<String, ArtifactRef> byId = new LinkedHashMap<>();
        for (ArtifactRef artifact : current) {
            byId.put(artifact.id(), artifact);
        }
        for (ArtifactRef artifact : additions) {
            byId.put(artifact.id(), artifact);
        }
        return List.copyOf(byId.values());
    }

    private static String missionIdFromCreatedEvent(HarnessEvent event) {
        if (!HarnessEventType.MISSION_CREATED.name().equals(event.type())) {
            return event.missionId();
        }
        HarnessEventPayloads.MissionCreated payload = read(
                event.payload(),
                HarnessEventPayloads.MissionCreated.class
        );
        return payload.mission().id();
    }

    private static <T> T read(String payload, Class<T> type) {
        try {
            return MAPPER.readValue(payload, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot decode harness event payload as " + type.getSimpleName(), exception);
        }
    }
}
