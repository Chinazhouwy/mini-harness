package com.chinazhouwy.miniharness.finance.research.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 通过 westock-data skill 的 CLI 获取腾讯自选股结构化数据。
 * 不在 Java 中复制底层接口，也不把查询结果伪装成交易所原始数据。
 */
public final class WestockCommandClient {

    private static final String PACKAGE = "westock-data-skillhub@1.0.5";

    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final String npmCommand;
    private final String npmCache;

    public WestockCommandClient(ObjectMapper objectMapper) {
        this(objectMapper, Duration.ofSeconds(45), "npx");
    }

    public WestockCommandClient(ObjectMapper objectMapper, Duration timeout, String npmCommand) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.npmCommand = Objects.requireNonNull(npmCommand, "npmCommand");
        String configuredCache = System.getenv("MINIHARNESS_NPM_CACHE");
        this.npmCache = configuredCache == null || configuredCache.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "miniharness-npm-cache").toString()
                : configuredCache;
    }

    public JsonNode kline(String symbol, LocalDate start, LocalDate end) {
        return run("kline", symbol, "--period", "day", "--start", start.toString(), "--end", end.toString());
    }

    public JsonNode shortSelling(String symbol, LocalDate start, LocalDate end) {
        return run("fund", "short", symbol, "--start", start.toString(), "--end", end.toString());
    }

    private JsonNode run(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(npmCommand);
        command.add("-y");
        command.add(PACKAGE);
        command.addAll(List.of(arguments));
        command.add("--raw");

        Process process = null;
        try {
            Files.createDirectories(Path.of(npmCache));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(false);
            builder.environment().put("NPM_CONFIG_CACHE", npmCache);
            process = builder.start();
            Process running = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> read(running.getInputStream())
            );
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> read(running.getErrorStream())
            );
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("westock command timed out: " + command);
            }
            String output = stdout.join().trim();
            String error = stderr.join().trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "westock command failed (exit=" + process.exitValue() + "): "
                                + command + "\n" + truncate(error)
                );
            }
            if (output.isBlank()) {
                throw new IllegalStateException("westock command returned empty output: " + command);
            }
            return objectMapper.readTree(output);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute westock command: " + command, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing westock command: " + command, exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String read(InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read westock process output", exception);
        }
    }

    private static String truncate(String value) {
        return value.length() <= 800 ? value : value.substring(0, 800) + "...";
    }
}
