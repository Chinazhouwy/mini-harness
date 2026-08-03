package com.chinazhouwy.miniharness.harness.runtime;

import com.chinazhouwy.miniharness.harness.domain.MissionSnapshot;

import java.util.Objects;

public record RunResult(
        MissionSnapshot snapshot,
        int steps,
        RunStopReason stopReason
) {

    public RunResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (steps < 0) {
            throw new IllegalArgumentException("Run steps must not be negative");
        }
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
    }
}
