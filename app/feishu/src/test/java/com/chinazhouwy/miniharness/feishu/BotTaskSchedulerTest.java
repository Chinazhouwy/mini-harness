package com.chinazhouwy.miniharness.feishu;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BotTaskSchedulerTest {
    @Test
    void stopsOneTaskWithoutInterruptingItOrStoppingAnotherTaskForTheSameBot() throws InterruptedException {
        String botId = BotRegistry.MAIN.id();
        var first = new SchedTask("first", botId, "* * * * * *");
        var second = new SchedTask("second", botId, "* * * * * *");
        var firstStarted = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var firstFinished = new CountDownLatch(1);
        var firstRuns = new AtomicInteger();
        var secondRuns = new Semaphore(0);

        try (var scheduler = new BotTaskScheduler()) {
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
        var task = new SchedTask("daily", botId, "0 0 9 * * *");
        var invalidTask = new SchedTask("invalid", botId, "incorrect cron");
        Runnable action = () -> {};

        try (var scheduler = new BotTaskScheduler()) {
            scheduler.register(task, action);
            assertThatIllegalArgumentException().isThrownBy(() -> scheduler.register(task, action));
            assertThatIllegalArgumentException().isThrownBy(() -> scheduler.register(invalidTask, action));
            assertThat(scheduler.list()).containsExactly(task);
        }
    }
}
