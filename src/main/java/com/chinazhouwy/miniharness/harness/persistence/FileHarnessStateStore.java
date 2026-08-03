package com.chinazhouwy.miniharness.harness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chinazhouwy.miniharness.harness.domain.*;
import com.chinazhouwy.miniharness.harness.event.HarnessEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 单进程、本地 JSON/JSONL 存储。
 *
 * <p>它使用 synchronized 防止同一个 JVM 内的并发写入，但不支持多进程并发写入，也不提供
 * 分布式锁。snapshot 采用同目录临时文件加原子移动，events 采用 append-only JSONL。</p>
 */
public final class FileHarnessStateStore implements HarnessStateStore {

    private final Path rootDirectory;
    private final ObjectMapper mapper;

    public FileHarnessStateStore(Path rootDirectory) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().findAndRegisterModules();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create harness storage directory: " + this.rootDirectory, exception);
        }
    }

    @Override
    public synchronized MissionSnapshot load(String missionId) {
        Path snapshotPath = snapshotPath(missionId);
        Path eventsPath = eventsPath(missionId);
        MissionSnapshot snapshot = null;

        try {
            if (Files.exists(snapshotPath)) {
                snapshot = mapper.readValue(snapshotPath.toFile(), Checkpoint.class).snapshot();
            }

            for (HarnessEvent event : readEvents(missionId)) {
                if (snapshot == null || event.sequence() > snapshot.lastEventSequence()) {
                    snapshot = StateReducer.apply(snapshot, event);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load mission " + missionId, exception);
        }

        if (snapshot == null) {
            throw new IllegalStateException("No harness state found for mission: " + missionId);
        }
        return snapshot;
    }

    @Override
    public synchronized void appendEvent(HarnessEvent event) {
        Path eventsPath = eventsPath(event.missionId());
        try {
            List<HarnessEvent> existing = readEvents(event.missionId());
            long expectedSequence = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).sequence() + 1;
            if (event.sequence() != expectedSequence) {
                throw new IllegalStateException(
                        "Event sequence must be %d but was %d for mission %s"
                                .formatted(expectedSequence, event.sequence(), event.missionId())
                );
            }
            String line = mapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(
                    eventsPath,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot append event for mission " + event.missionId(), exception);
        }
    }

    @Override
    public synchronized void saveCheckpoint(Checkpoint checkpoint) {
        String missionId = checkpoint.mission().id();
        Path targetPath = snapshotPath(missionId);
        try {
            List<HarnessEvent> events = readEvents(missionId);
            long lastEventSequence = events.isEmpty() ? 0 : events.get(events.size() - 1).sequence();
            if (checkpoint.lastEventSequence() > lastEventSequence) {
                throw new IllegalStateException(
                        "Checkpoint sequence %d is ahead of event log sequence %d"
                                .formatted(checkpoint.lastEventSequence(), lastEventSequence)
                );
            }

            Path temporaryPath = Files.createTempFile(
                    rootDirectory,
                    targetPath.getFileName().toString() + ".",
                    ".tmp"
            );
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), checkpoint);
                try {
                    Files.move(
                            temporaryPath,
                            targetPath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryPath);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot save checkpoint for mission " + missionId, exception);
        }
    }

    @Override
    public synchronized List<HarnessEvent> readEvents(String missionId) {
        Path path = eventsPath(missionId);
        if (!Files.exists(path)) {
            return List.of();
        }

        try {
            List<HarnessEvent> events = new ArrayList<>();
            long expectedSequence = 1;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                HarnessEvent event = mapper.readValue(line, HarnessEvent.class);
                if (!missionId.equals(event.missionId())) {
                    throw new IllegalStateException("Event log contains another mission id: " + event.missionId());
                }
                if (event.sequence() != expectedSequence) {
                    throw new IllegalStateException(
                            "Event sequence is not contiguous: expected %d but found %d"
                                    .formatted(expectedSequence, event.sequence())
                    );
                }
                events.add(event);
                expectedSequence++;
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read event log for mission " + missionId, exception);
        }
    }

    @Override
    public synchronized boolean exists(String missionId) {
        return Files.exists(snapshotPath(missionId)) || Files.exists(eventsPath(missionId));
    }

    public Path snapshotPath(String missionId) {
        return rootDirectory.resolve("mission-" + safeMissionId(missionId) + ".snapshot.json");
    }

    public Path eventsPath(String missionId) {
        return rootDirectory.resolve("mission-" + safeMissionId(missionId) + ".events.jsonl");
    }

    private static String safeMissionId(String missionId) {
        if (missionId == null || missionId.isBlank() || !missionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Mission id contains unsupported path characters: " + missionId);
        }
        return missionId;
    }
}
