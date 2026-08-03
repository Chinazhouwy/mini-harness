package com.chinazhouwy.miniharness.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chinazhouwy.miniharness.harness.demo.DemoMissionFactory;
import com.chinazhouwy.miniharness.harness.domain.*;
import com.chinazhouwy.miniharness.harness.event.HarnessEvent;
import com.chinazhouwy.miniharness.harness.event.HarnessEventPayloads;
import com.chinazhouwy.miniharness.harness.event.HarnessEventType;
import com.chinazhouwy.miniharness.harness.execution.*;
import com.chinazhouwy.miniharness.harness.persistence.FileHarnessStateStore;
import com.chinazhouwy.miniharness.harness.verification.DemoTaskVerifier;
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
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(store);

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
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(store);
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
        HarnessOrchestrator firstOrchestrator = new HarnessOrchestrator(firstStore);
        firstOrchestrator.createMission(DemoMissionFactory.create("restart-test"));
        AdvanceResult firstStep = firstOrchestrator.advanceOneStep("restart-test");
        assertEquals(TaskStatus.ACCEPTED, firstStep.snapshot().task("prepare").status());

        FileHarnessStateStore restartedStore = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator restartedOrchestrator = new HarnessOrchestrator(restartedStore);
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
    void eventReplayRestoresState() throws Exception {
        FileHarnessStateStore store = new FileHarnessStateStore(tempDir);
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(store);
        MissionSnapshot created = orchestrator.createMission(DemoMissionFactory.create("replay-test"));

        TaskNode running = created.task("prepare").transitionTo(TaskStatus.RUNNING);
        TaskAttempt attempt = new TaskAttempt(
                "prepare",
                1,
                TaskAttemptStatus.RUNNING,
                "",
                List.of(),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                null
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HarnessEvent event = new HarnessEvent(
                created.lastEventSequence() + 1,
                "replay-test",
                "prepare",
                HarnessEventType.TASK_STARTED,
                mapper.writeValueAsString(new HarnessEventPayloads.TaskState(running, attempt, List.of())),
                Instant.parse("2026-01-01T00:00:01Z")
        );
        store.appendEvent(event);

        MissionSnapshot replayed = new FileHarnessStateStore(tempDir).load("replay-test");

        assertEquals(event.sequence(), replayed.lastEventSequence());
        assertEquals(TaskStatus.RUNNING, replayed.task("prepare").status());
        assertNotNull(replayed.attemptsFor("prepare").get(0));
        assertEquals(TaskAttemptStatus.RUNNING, replayed.attemptsFor("prepare").get(0).status());
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
}
