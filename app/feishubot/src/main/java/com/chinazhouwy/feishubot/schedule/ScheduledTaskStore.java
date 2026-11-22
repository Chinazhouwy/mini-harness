package com.chinazhouwy.feishubot.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

/** 本地任务定义的存储。调用方串行更新，文件通过原子替换保存。 */
final class ScheduledTaskStore {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    ScheduledTaskStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    List<SchedTask> load() {
        try (var input = Files.newInputStream(file)) {
            return mapper.readerForListOf(SchedTask.class).readValue(input);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("读取定时任务失败 " + file, e);
        }
    }

    void save(Collection<SchedTask> tasks) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".scheduled-tasks-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), tasks);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存定时任务失败 " + file, e);
        }
    }
}
