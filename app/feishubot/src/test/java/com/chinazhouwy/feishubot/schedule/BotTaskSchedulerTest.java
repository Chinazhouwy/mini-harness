package com.chinazhouwy.feishubot.schedule;

import com.chinazhouwy.feishubot.bot.BotRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotTaskSchedulerTest {
    @TempDir
    Path temp;

    @Test
    void stopsOneTaskWithoutInterruptingItOrStoppingAnotherTaskForTheSameBot() throws InterruptedException {
        String botId = BotRegistry.MAIN.id();
        var first = new SchedTask("first", botId, "* * * * * *", "查天气", "chat-1");
        var second = new SchedTask("second", botId, "* * * * * *", "发简报", "chat-2");
        var firstStarted = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var firstFinished = new CountDownLatch(1);
        var firstRuns = new AtomicInteger();
        var secondRuns = new Semaphore(0);

        try (var scheduler = new BotTaskScheduler(temp.resolve("scheduled-tasks.json"))) {
            try {
                scheduler.register(first, () -> {
                    firstRuns.incrementAndGet();
                    firstStarted.countDown();
                    try {
                        finishFirst.await();
                        firstFinished.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                scheduler.register(second, secondRuns::release);
                assertThat(scheduler.list()).containsExactly(first, second);

                assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(scheduler.stop(first.taskId())).isTrue();
                finishFirst.countDown();
                assertThat(firstFinished.await(5, TimeUnit.SECONDS)).isTrue();

                secondRuns.drainPermits();
                assertThat(secondRuns.tryAcquire(2, 5, TimeUnit.SECONDS)).isTrue();
                assertThat(firstRuns.get()).isEqualTo(1);
                assertThat(scheduler.list()).containsExactly(second);
            } finally {
                finishFirst.countDown();
            }
        }
    }

    @Test
    void rejectsInvalidCronAndDuplicateTaskIds() {
        String botId = BotRegistry.MAIN.id();
        var task = new SchedTask("daily", botId, "0 0 9 * * *", "查天气", "chat-1");
        var invalidTask = new SchedTask("invalid", botId, "incorrect cron", "查天气", "chat-1");
        Runnable action = () -> {};

        try (var scheduler = new BotTaskScheduler(temp.resolve("scheduled-tasks.json"))) {
            scheduler.register(task, action);
            assertThatIllegalArgumentException().isThrownBy(() -> scheduler.register(task, action));
            assertThatIllegalArgumentException().isThrownBy(() -> scheduler.register(invalidTask, action));
            assertThat(scheduler.list()).containsExactly(task);
        }
    }

    @Test
    void restoresEnabledTasksFromJsonWithTheirOriginalInstructionAndChat() throws Exception {
        Path file = temp.resolve("scheduled-tasks.json");
        String botId = BotRegistry.MAIN.id();
        var enabled = new SchedTask("enabled", botId, "* * * * * *", ZoneId.of("Europe/Paris"),
                "查天气", "chat-1", true);
        var stopped = new SchedTask("stopped", botId, "* * * * * *", "发简报", "chat-2");

        try (var scheduler = new BotTaskScheduler(file)) {
            scheduler.register(enabled, () -> {});
            scheduler.register(stopped, () -> {});
            assertThat(scheduler.stop(stopped.taskId())).isTrue();
        }

        assertThat(new ScheduledTaskStore(file).load()).containsExactly(enabled, stopped.disabled());

        var triggered = new CompletableFuture<SchedTask>();
        try (var restarted = new BotTaskScheduler(file)) {
            restarted.restore(triggered::complete);
            assertThat(restarted.list()).containsExactly(enabled);
            assertThat(triggered.get(5, TimeUnit.SECONDS)).isEqualTo(enabled);
        }
    }

    @Test
    void failedSaveLeavesTaskUnregisteredAndAllowsRetry() throws Exception {
        Path file = temp.resolve("scheduled-tasks.json");
        var task = new SchedTask("retry", BotRegistry.MAIN.id(), "* * * * * *", "查天气", "chat-1");
        var failedRuns = new AtomicInteger();
        var retriedRun = new CountDownLatch(1);

        try (var scheduler = new BotTaskScheduler(file)) {
            Files.createDirectory(file);
            assertThatThrownBy(() -> scheduler.register(task, failedRuns::incrementAndGet))
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(scheduler.list()).isEmpty();

            Files.delete(file);
            scheduler.register(task, retriedRun::countDown);
            assertThat(retriedRun.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failedRuns.get()).isZero();
            assertThat(new ScheduledTaskStore(file).load()).containsExactly(task);
        }
    }
}
