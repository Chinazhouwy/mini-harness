package com.chinazhouwy.miniharness.harness.execution;

import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;

import java.util.List;
import java.util.Objects;

public record TaskExecutionResult(
        boolean success,
        String summary,
        List<String> evidence,
        List<ArtifactRef> artifacts,
        String error
) {

    public TaskExecutionResult {
        summary = Objects.requireNonNullElse(summary, "");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        error = Objects.requireNonNullElse(error, "");
    }

    public static TaskExecutionResult success(
            String summary,
            List<String> evidence,
            List<ArtifactRef> artifacts
    ) {
        return new TaskExecutionResult(true, summary, evidence, artifacts, "");
    }

    public static TaskExecutionResult failure(String summary, String error, List<String> evidence) {
        return new TaskExecutionResult(false, summary, evidence, List.of(), error);
    }
}
