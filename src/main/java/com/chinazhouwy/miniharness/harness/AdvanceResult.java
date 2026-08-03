package com.chinazhouwy.miniharness.harness;

import java.util.List;
import java.util.Objects;

public record AdvanceResult(
        MissionSnapshot snapshot,
        List<HarnessEvent> events,
        boolean progressed,
        String message
) {

    public AdvanceResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        message = Objects.requireNonNullElse(message, "");
    }

    public HarnessEvent latestEvent() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }
}
