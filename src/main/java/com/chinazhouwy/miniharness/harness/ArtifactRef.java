package com.chinazhouwy.miniharness.harness;

import java.util.Objects;

public record ArtifactRef(
        String id,
        String type,
        String path,
        String description,
        String sha256
) {

    public ArtifactRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Artifact id must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Artifact type must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Artifact path must not be blank");
        }
        description = Objects.requireNonNullElse(description, "");
    }

    public ArtifactRef(String id, String type, String path, String description) {
        this(id, type, path, description, null);
    }
}
