package com.chinazhouwy.miniharness.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chinazhouwy.miniharness.harness.demo.DemoMissionFactory;
import com.chinazhouwy.miniharness.harness.demo.DemoTaskVerifier;
import com.chinazhouwy.miniharness.harness.demo.DeterministicTaskExecutor;
import com.chinazhouwy.miniharness.harness.domain.MissionDefinition;
import com.chinazhouwy.miniharness.harness.domain.MissionSnapshot;
import com.chinazhouwy.miniharness.harness.domain.MissionStatus;
import com.chinazhouwy.miniharness.harness.domain.TaskAttempt;
import com.chinazhouwy.miniharness.harness.domain.TaskAttemptStatus;
import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.domain.TaskStatus;
import com.chinazhouwy.miniharness.harness.event.HarnessEvent;
import com.chinazhouwy.miniharness.harness.event.HarnessEventPayloads;
import com.chinazhouwy.miniharness.harness.event.HarnessEventType;
import com.chinazhouwy.miniharness.harness.execution.AlwaysFailTaskExecutor;
import com.chinazhouwy.miniharness.harness.execution.FailOnceTaskExecutor;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutor;
import com.chinazhouwy.miniharness.harness.persistence.FileHarnessStateStore;
import com.chinazhouwy.miniharness.harness.verification.TaskVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void missionCanBeCreated() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = demoOrchestrator(store);

        MissionSnapshot snapshot = orchestrator.createMission(DemoMissionFactory.create("create-test"));

        assertEquals(MissionStatus.CREATED, snapshot.mission().status());
        assertEquals(TaskStatus.READY, snapshot.task("prepare").status());
        assertEquals(TaskStatus.PENDING, snapshot.task("execute").status());
        assertTrue(store.readEvents("create-test").stream()
                .anyMatch(event -> event.type().equals(HarnessEventType.MISSION_CREATED.name())));
    }

    @Test
    void demoMissionCanRunToSuccess() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = demoOrchestrator(store);
        orchestrator.createMission(DemoMissionFactory.create("success-test"));

        RunResult result = orchestrator.runUntilPause("success-test", 10);

        assertEquals(RunStopReason.COMPLETED, result.stopReason());
        assertEquals(MissionStatus.SUCCEEDED, result.snapshot().mission().status());
        assertEquals(
                List.of(TaskStatus.ACCEPTED, TaskStatus.ACCEPTED, TaskStatus.ACCEPTED, TaskStatus.ACCEPTED),
                result.snapshot().tasks().stream().map(TaskNode::status).toList()
        );
        assertTrue(result.snapshot().artifacts().stream()
                .anyMatch(artifact -> artifact.id().equals("success-test-report")));

        List<HarnessEvent> events = store.readEvents("success-test");
        assertFalse(events.isEmpty());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
        assertTrue(events.stream().anyMatch(event -> event.type().equals(HarnessEventType.MISSION_COMPLETED.name())));
    }

    @Test
    void missionCanResumeAfterRestart() {
        FileHarnessStateStore firstStore = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator firstOrchestrator = demoOrchestrator(firstStore);
        firstOrchestrator.createMission(DemoMissionFactory.create("restart-test"));
        AdvanceResult firstStep = firstOrchestrator.advanceOneStep("restart-test");
        assertEquals(TaskStatus.ACCEPTED, firstStep.snapshot().task("prepare").status());

        FileHarnessStateStore restartedStore = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator restartedOrchestrator = demoOrchestrator(restartedStore);
        MissionSnapshot resumed = restartedOrchestrator.resume("restart-test");
        RunResult result = restartedOrchestrator.runUntilPause("restart-test", 10);

        assertEquals(TaskStatus.ACCEPTED, resumed.task("prepare").status());
        assertEquals(MissionStatus.SUCCEEDED, result.snapshot().mission().status());
        assertEquals(1, result.snapshot().attemptsFor("prepare").stream()
                .filter(attempt -> attempt.status() == TaskAttemptStatus.SUCCEEDED)
                .count());
    }

    @Test
    void failedTaskCanRetry() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        MissionDefinition definition = singleTaskDefinition("retry-test", 2);
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                store,
                new FailOnceTaskExecutor(new DeterministicTaskExecutor(), Set.of("flaky")),
                new DemoTaskVerifier()
        );
        orchestrator.createMission(definition);

        AdvanceResult first = orchestrator.advanceOneStep("retry-test");
        assertEquals(TaskStatus.READY, first.snapshot().task("flaky").status());
        assertTrue(store.readEvents("retry-test").stream()
                .anyMatch(event -> event.type().equals(HarnessEventType.TASK_RETRY_SCHEDULED.name())));

        AdvanceResult second = orchestrator.advanceOneStep("retry-test");
        assertEquals(TaskStatus.ACCEPTED, second.snapshot().task("flaky").status());
        assertEquals(MissionStatus.SUCCEEDED, second.snapshot().mission().status());
        assertEquals(2, second.snapshot().attemptsFor("flaky").size());
        assertEquals(TaskAttemptStatus.SUCCEEDED, second.snapshot().attemptsFor("flaky").get(1).status());
    }

    @Test
    void retryLimitProducesBlocked() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                store,
                new AlwaysFailTaskExecutor(),
                new DemoTaskVerifier()
        );
        orchestrator.createMission(singleTaskDefinition("blocked-test", 2));

        RunResult result = orchestrator.runUntilPause("blocked-test", 10);

        assertEquals(RunStopReason.BLOCKED, result.stopReason());
        assertEquals(MissionStatus.BLOCKED, result.snapshot().mission().status());
        assertEquals(TaskStatus.BLOCKED, result.snapshot().task("flaky").status());
        assertEquals(2, result.snapshot().attemptsFor("flaky").size());
        assertTrue(result.steps() < 10);
    }

    @Test
    void blockedDependencyKeepsDependentsPending() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                store,
                new AlwaysFailTaskExecutor(),
                new DemoTaskVerifier()
        );
        orchestrator.createMission(twoTaskDefinition("block-propagation-test", 1));

        RunResult result = orchestrator.runUntilPause("block-propagation-test", 10);

        assertEquals(RunStopReason.BLOCKED, result.stopReason());
        assertEquals(MissionStatus.BLOCKED, result.snapshot().mission().status());
        assertEquals(TaskStatus.BLOCKED, result.snapshot().task("first").status());
        assertEquals(TaskStatus.PENDING, result.snapshot().task("second").status());
    }

    @Test
    void cancelStopsMissionButKeepsAcceptedWork() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = demoOrchestrator(store);
        orchestrator.createMission(DemoMissionFactory.create("cancel-test"));
        orchestrator.advanceOneStep("cancel-test");

        orchestrator.cancel("cancel-test");
        MissionSnapshot snapshot = orchestrator.resume("cancel-test");

        assertEquals(MissionStatus.CANCELLED, snapshot.mission().status());
        assertEquals(TaskStatus.ACCEPTED, snapshot.task("prepare").status());
        assertTrue(snapshot.tasks().stream()
                .filter(task -> !task.id().equals("prepare"))
                .allMatch(task -> task.status() == TaskStatus.CANCELLED));

        RunResult result = orchestrator.runUntilPause("cancel-test", 10);
        assertEquals(RunStopReason.CANCELLED, result.stopReason());
        List<HarnessEvent> events = store.readEvents("cancel-test");
        assertTrue(events.stream().anyMatch(event -> event.type().equals(HarnessEventType.MISSION_CANCELLED.name())));
        assertTrue(events.stream().anyMatch(event -> event.type().equals(HarnessEventType.TASK_CANCELLED.name())));
    }

    @Test
    void executorExceptionBecomesRetryableFailure() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        TaskExecutor throwingExecutor = context -> {
            throw new IllegalStateException("executor exploded");
        };
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(store, throwingExecutor, new DemoTaskVerifier());
        orchestrator.createMission(singleTaskDefinition("executor-exception-test", 2));

        AdvanceResult first = orchestrator.advanceOneStep("executor-exception-test");

        assertEquals(TaskStatus.READY, first.snapshot().task("flaky").status());
        assertTrue(first.snapshot().attemptsFor("flaky").stream()
                .anyMatch(attempt -> attempt.evidence().contains("executorException=true")));

        RunResult result = orchestrator.runUntilPause("executor-exception-test", 10);
        assertEquals(RunStopReason.BLOCKED, result.stopReason());
    }

    @Test
    void verifierExceptionBlocksTaskImmediately() {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        TaskVerifier throwingVerifier = (task, result) -> {
            throw new IllegalStateException("verifier exploded");
        };
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                store,
                new DeterministicTaskExecutor(),
                throwingVerifier
        );
        orchestrator.createMission(singleTaskDefinition("verifier-exception-test", 2));

        RunResult result = orchestrator.runUntilPause("verifier-exception-test", 10);

        assertEquals(RunStopReason.BLOCKED, result.stopReason());
        assertEquals(TaskStatus.BLOCKED, result.snapshot().task("flaky").status());
        assertTrue(result.snapshot().attemptsFor("flaky").stream()
                .allMatch(attempt -> attempt.evidence().contains("verifierException=true")));
    }

    @Test
    void interruptedRunningTaskRecoversAndReExecutes() throws Exception {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = demoOrchestrator(store);
        orchestrator.createMission(singleTaskDefinition("interrupted-recovery-test", 2));
        appendTaskStartedEvent(
                store,
                orchestrator.resume("interrupted-recovery-test"),
                "interrupted-recovery-test",
                "flaky",
                1,
                Instant.parse("2026-01-01T00:00:00Z")
        );

        // 模拟进程重启：丢弃旧 Orchestrator，用同一个存储目录重新装配
        HarnessOrchestrator restarted = demoOrchestrator(new FileHarnessStateStore(tempDir));
        AdvanceResult recovered = restarted.advanceOneStep("interrupted-recovery-test");

        assertEquals(TaskStatus.ACCEPTED, recovered.snapshot().task("flaky").status());
        List<TaskAttempt> attempts = recovered.snapshot().attemptsFor("flaky");
        assertEquals(2, attempts.size());
        assertEquals(TaskAttemptStatus.RETRYABLE, attempts.get(0).status());
        assertEquals(TaskAttemptStatus.SUCCEEDED, attempts.get(1).status());
        assertTrue(store.readEvents("interrupted-recovery-test").stream()
                .anyMatch(event -> event.type().equals(HarnessEventType.TASK_RETRY_SCHEDULED.name())));
        assertEquals(MissionStatus.SUCCEEDED, recovered.snapshot().mission().status());
    }

    @Test
    void interruptedTaskAtMaxAttemptsBlocksOnRecovery() throws Exception {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = demoOrchestrator(store);
        orchestrator.createMission(singleTaskDefinition("interrupted-max-attempts-test", 1));
        appendTaskStartedEvent(
                store,
                orchestrator.resume("interrupted-max-attempts-test"),
                "interrupted-max-attempts-test",
                "flaky",
                1,
                Instant.parse("2026-01-01T00:00:00Z")
        );

        // 模拟进程重启：恢复时发现尝试次数已达上限，直接阻塞而不是再次重试
        HarnessOrchestrator restarted = demoOrchestrator(new FileHarnessStateStore(tempDir));
        RunResult result = restarted.runUntilPause("interrupted-max-attempts-test", 5);

        assertEquals(RunStopReason.BLOCKED, result.stopReason());
        assertEquals(MissionStatus.BLOCKED, result.snapshot().mission().status());
        assertEquals(TaskStatus.BLOCKED, result.snapshot().task("flaky").status());
        assertEquals(TaskAttemptStatus.BLOCKED, result.snapshot().attemptsFor("flaky").get(0).status());
        assertEquals(
                "恢复时已达到最大尝试次数",
                result.snapshot().attemptsFor("flaky").get(0).summary()
        );
    }

    @Test
    void eventReplayRestoresState() throws Exception {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = demoOrchestrator(store);
        MissionSnapshot created = orchestrator.createMission(DemoMissionFactory.create("replay-test"));

        HarnessEvent event = appendTaskStartedEvent(
                store,
                created,
                "replay-test",
                "prepare",
                1,
                Instant.parse("2026-01-01T00:00:00Z")
        );

        MissionSnapshot replayed = new FileHarnessStateStore(tempDir).load("replay-test");

        assertEquals(event.sequence(), replayed.lastEventSequence());
        assertEquals(TaskStatus.RUNNING, replayed.task("prepare").status());
        assertNotNull(replayed.attemptsFor("prepare").get(0));
        assertEquals(TaskAttemptStatus.RUNNING, replayed.attemptsFor("prepare").get(0).status());
    }

    private static HarnessOrchestrator demoOrchestrator(FileHarnessStateStore store) {
        return new HarnessOrchestrator(store, new DeterministicTaskExecutor(), new DemoTaskVerifier());
    }

    /** 手工向事件日志追加一个 TASK_STARTED 事件，模拟"事件已写入但进程已退出"的崩溃点。 */
    private static HarnessEvent appendTaskStartedEvent(
            FileHarnessStateStore store,
            MissionSnapshot snapshot,
            String missionId,
            String taskId,
            int attemptNumber,
            Instant startedAt
    ) throws Exception {
        TaskNode running = snapshot.task(taskId).transitionTo(TaskStatus.RUNNING);
        TaskAttempt attempt = new TaskAttempt(
                taskId,
                attemptNumber,
                TaskAttemptStatus.RUNNING,
                "",
                List.of(),
                List.of(),
                startedAt,
                null
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HarnessEvent event = new HarnessEvent(
                snapshot.lastEventSequence() + 1,
                missionId,
                taskId,
                HarnessEventType.TASK_STARTED,
                mapper.writeValueAsString(new HarnessEventPayloads.TaskState(running, attempt, List.of())),
                startedAt.plusSeconds(1)
        );
        store.appendEvent(event);
        return event;
    }

    private static MissionDefinition singleTaskDefinition(String missionId, int maxAttempts) {
        return new MissionDefinition(
                missionId,
                "验证重试边界",
                List.of("flaky 最终被正确处理"),
                List.of(new TaskNode(
                        "flaky",
                        "可失败任务",
                        List.of(),
                        TaskStatus.PENDING,
                        maxAttempts,
                        "",
                        ""
                ))
        );
    }

    private static MissionDefinition twoTaskDefinition(String missionId, int maxAttempts) {
        return new MissionDefinition(
                missionId,
                "验证阻塞传播",
                List.of("first 被阻塞后 mission 整体阻塞"),
                List.of(
                        new TaskNode("first", "前置任务", List.of(), TaskStatus.PENDING, maxAttempts, "", ""),
                        new TaskNode("second", "依赖任务", List.of("first"), TaskStatus.PENDING, 2, "", "")
                )
        );
    }
}
