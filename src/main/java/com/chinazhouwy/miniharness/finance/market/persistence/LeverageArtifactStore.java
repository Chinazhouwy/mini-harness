package com.chinazhouwy.miniharness.finance.market.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 金融领域 Artifact 的本地持久化，和 Harness 的事件快照存储保持独立。 */
public final class LeverageArtifactStore {

    private final Path rootDirectory;
    private final ObjectMapper mapper;

    public LeverageArtifactStore(Path rootDirectory) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().findAndRegisterModules();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create finance artifact directory: " + rootDirectory, exception);
        }
    }

    public ArtifactRef writeJson(
            String missionId,
            String artifactName,
            Object value,
            String type,
            String description
    ) {
        Path path = preparePath(missionId, artifactName);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write finance JSON artifact: " + path, exception);
        }
        return artifact(missionId, artifactName, path, type, description);
    }

    public ArtifactRef writeMarkdown(
            String missionId,
            String artifactName,
            String markdown,
            String description
    ) {
        Path path = preparePath(missionId, artifactName);
        try {
            Files.writeString(path, Objects.requireNonNull(markdown, "markdown"), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write finance Markdown artifact: " + path, exception);
        }
        return artifact(missionId, artifactName, path, "text/markdown", description);
    }

    public <T> T readJson(Path path, Class<T> type) {
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read finance JSON artifact: " + path, exception);
        }
    }

    public String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read finance text artifact: " + path, exception);
        }
    }

    private Path preparePath(String missionId, String artifactName) {
        safePart(missionId, "missionId");
        safePart(artifactName, "artifactName");
        Path missionDirectory = rootDirectory.resolve(missionId);
        try {
            Files.createDirectories(missionDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create finance mission artifact directory", exception);
        }
        return missionDirectory.resolve(artifactName);
    }

    private ArtifactRef artifact(
            String missionId,
            String artifactName,
            Path path,
            String type,
            String description
    ) {
        return new ArtifactRef(
                missionId + "-" + artifactName.replace('.', '-'),
                type,
                path.toString(),
                description,
                sha256(path)
        );
    }

    private static String sha256(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot calculate artifact sha256: " + path, exception);
        }
    }

    private static void safePart(String value, String name) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported path characters: " + value);
        }
    }
}
