package com.chinazhouwy.miniharness.harness.event;

import java.time.Instant;
import java.util.Objects;

public record HarnessEvent(
        long sequence,
        String missionId,
        String taskId,
        String type,
        String payload,
        Instant createdAt
) {

    public HarnessEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("Event sequence must be positive");
        }
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("Event missionId must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Event type must not be blank");
        }
        payload = Objects.requireNonNullElse(payload, "{}");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public HarnessEvent(
            long sequence,
            String missionId,
            String taskId,
            HarnessEventType type,
            String payload,
            Instant createdAt
    ) {
        this(sequence, missionId, taskId, type.name(), payload, createdAt);
    }
}
