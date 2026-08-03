package com.chinazhouwy.miniharness.harness;

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
