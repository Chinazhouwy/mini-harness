package com.chinazhouwy.miniharness.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;
import com.chinazhouwy.miniharness.harness.domain.Mission;
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
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionContext;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutor;
import com.chinazhouwy.miniharness.harness.persistence.HarnessStateStore;
import com.chinazhouwy.miniharness.harness.persistence.StateReducer;
import com.chinazhouwy.miniharness.harness.verification.TaskVerifier;
import com.chinazhouwy.miniharness.harness.verification.VerifierResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 单节点、串行的任务推进器。
 *
 * <p>它只调度传入的 TaskExecutor；即使未来接入模型，模型也不能直接修改这里的状态。
 * Executor 和 Verifier 由调用方装配，核心运行时不依赖任何具体实现。</p>
 */
public final class HarnessOrchestrator {

    private final HarnessStateStore stateStore;
    private final TaskExecutor taskExecutor;
    private final TaskVerifier taskVerifier;
    private final ObjectMapper mapper;

    public HarnessOrchestrator(
            HarnessStateStore stateStore,
            TaskExecutor taskExecutor,
            TaskVerifier taskVerifier
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.taskVerifier = Objects.requireNonNull(taskVerifier, "taskVerifier");
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    public synchronized MissionSnapshot createMission(MissionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (stateStore.exists(definition.id())) {
            throw new IllegalArgumentException("Mission already exists: " + definition.id());
        }

        Instant now = Instant.now();
        Mission mission = new Mission(
                definition.id(),
                definition.goal(),
                definition.acceptanceCriteria(),
                MissionStatus.CREATED,
                0,
                now,
                now
        );
        List<TaskNode> initialTasks = definition.tasks().stream()
                .map(HarnessOrchestrator::asPendingTask)
                .toList();
        MissionSnapshot state = new MissionSnapshot(mission, initialTasks, List.of(), List.of(), 0, now);
        List<HarnessEvent> events = new ArrayList<>();

        state = emit(
                state,
                HarnessEventType.MISSION_CREATED,
                null,
                new HarnessEventPayloads.MissionCreated(mission, initialTasks),
                events
        );

        for (TaskNode task : state.tasks()) {
            if (task.dependencyIds().isEmpty()) {
                TaskNode readyTask = task.transitionTo(TaskStatus.READY);
                state = emit(
                        state,
                        HarnessEventType.TASK_READY,
                        readyTask.id(),
                        new HarnessEventPayloads.TaskState(readyTask, null, List.of()),
                        events
                );
            }
        }
        return checkpoint(state, events);
    }

    public synchronized AdvanceResult advanceOneStep(String missionId) {
        MissionSnapshot state = stateStore.load(missionId);
        List<HarnessEvent> events = new ArrayList<>();

        if (state.mission().status().isTerminal()) {
            return new AdvanceResult(state, events, false, "Mission 已经处于终态");
        }

        state = recoverInterruptedTasks(state, events);
        state = ensureMissionRunning(state, events);
        if (state.mission().status() == MissionStatus.WAITING) {
            return new AdvanceResult(state, events, !events.isEmpty(), "等待人工输入");
        }

        List<TaskNode> currentTasks = state.tasks();
        TaskNode nextTask = schedulableTasks(state).stream()
                .min(Comparator.comparingInt(currentTasks::indexOf))
                .orElse(null);
        if (nextTask == null) {
            state = updateMissionAfterStep(state, events, true);
            if (!events.isEmpty()) {
                state = checkpoint(state, events);
            }
            String message = state.mission().status() == MissionStatus.WAITING
                    ? "没有可调度任务，等待外部输入"
                    : "没有可调度任务";
            return new AdvanceResult(state, events, !events.isEmpty(), message);
        }

        state = executeOneTask(state, nextTask, events);
        state = updateMissionAfterStep(state, events, false);
        state = checkpoint(state, events);
        return new AdvanceResult(state, events, true, "执行了任务 " + nextTask.id());
    }

    public synchronized RunResult runUntilPause(String missionId, int maxSteps) {
        if (maxSteps < 0) {
            throw new IllegalArgumentException("maxSteps must not be negative");
        }

        MissionSnapshot state = stateStore.load(missionId);
        RunStopReason initialReason = terminalReason(state.mission().status());
        if (initialReason != null) {
            return new RunResult(state, 0, initialReason);
        }
        if (maxSteps == 0) {
            return new RunResult(state, 0, RunStopReason.MAX_STEPS);
        }

        int steps = 0;
        while (steps < maxSteps) {
            AdvanceResult result = advanceOneStep(missionId);
            state = result.snapshot();
            steps++;

            RunStopReason reason = terminalReason(state.mission().status());
            if (reason != null) {
                return new RunResult(state, steps, reason);
            }
            if (!result.progressed()) {
                return new RunResult(
                        state,
                        steps,
                        state.mission().status() == MissionStatus.WAITING
                                ? RunStopReason.WAITING_FOR_INPUT
                                : RunStopReason.NO_SCHEDULABLE_TASKS
                );
            }
        }
        return new RunResult(state, steps, RunStopReason.MAX_STEPS);
    }

    public synchronized MissionSnapshot resume(String missionId) {
        return stateStore.load(missionId);
    }

    public synchronized void cancel(String missionId) {
        MissionSnapshot state = stateStore.load(missionId);
        if (state.mission().status().isTerminal()) {
            return;
        }

        List<HarnessEvent> events = new ArrayList<>();
        state = state.mission().status() == MissionStatus.CANCELLED
                ? state
                : emitMissionTransition(state, MissionStatus.CANCELLED, HarnessEventType.MISSION_CANCELLED, events);
        for (TaskNode task : state.tasks()) {
            if (!task.status().isTerminal()) {
                TaskNode cancelled = task.transitionTo(TaskStatus.CANCELLED);
                state = emit(
                        state,
                        HarnessEventType.TASK_CANCELLED,
                        task.id(),
                        new HarnessEventPayloads.TaskState(cancelled, null, List.of()),
                        events
                );
            }
        }
        checkpoint(state, events);
    }

    private MissionSnapshot ensureMissionRunning(MissionSnapshot state, List<HarnessEvent> events) {
        if (state.mission().status() == MissionStatus.CREATED) {
            state = emitMissionTransition(state, MissionStatus.READY, HarnessEventType.MISSION_READY, events);
        }
        if (state.mission().status() == MissionStatus.READY) {
            state = emitMissionTransition(state, MissionStatus.RUNNING, HarnessEventType.MISSION_STARTED, events);
        }
        return state;
    }

    private MissionSnapshot recoverInterruptedTasks(MissionSnapshot state, List<HarnessEvent> events) {
        for (TaskNode task : List.copyOf(state.tasks())) {
            if (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.SUBMITTED) {
                continue;
            }

            TaskAttempt latestAttempt = latestAttempt(state, task.id());
            if (latestAttempt == null) {
                TaskNode blocked = task.transitionTo(TaskStatus.BLOCKED);
                state = emit(
                        state,
                        HarnessEventType.TASK_BLOCKED,
                        task.id(),
                        new HarnessEventPayloads.TaskState(
                                blocked,
                                null,
                                List.of()
                        ),
                        events
                );
                continue;
            }

            if (state.attemptsFor(task.id()).size() >= task.maxAttempts()) {
                TaskAttempt blockedAttempt = latestAttempt.withStatus(
                        TaskAttemptStatus.BLOCKED,
                        "恢复时已达到最大尝试次数",
                        latestAttempt.evidence(),
                        latestAttempt.artifacts(),
                        Instant.now()
                );
                TaskNode blocked = task.transitionTo(TaskStatus.BLOCKED);
                state = emit(
                        state,
                        HarnessEventType.TASK_BLOCKED,
                        task.id(),
                        new HarnessEventPayloads.TaskState(blocked, blockedAttempt, latestAttempt.artifacts()),
                        events
                );
                continue;
            }

            TaskAttempt retryableAttempt = latestAttempt.withStatus(
                    TaskAttemptStatus.RETRYABLE,
                    "进程恢复时发现未完成尝试，重新调度",
                    latestAttempt.evidence(),
                    latestAttempt.artifacts(),
                    Instant.now()
            );
            TaskNode retryable = task.transitionTo(TaskStatus.RETRYABLE);
            state = emit(
                    state,
                    HarnessEventType.TASK_RETRY_SCHEDULED,
                    task.id(),
                    new HarnessEventPayloads.TaskState(retryable, retryableAttempt, latestAttempt.artifacts()),
                    events
            );
            TaskNode ready = retryable.transitionTo(TaskStatus.READY);
            state = emit(
                    state,
                    HarnessEventType.TASK_READY,
                    task.id(),
                    new HarnessEventPayloads.TaskState(ready, retryableAttempt, latestAttempt.artifacts()),
                    events
            );
        }
        return state;
    }

    private MissionSnapshot executeOneTask(
            MissionSnapshot state,
            TaskNode task,
            List<HarnessEvent> events
    ) {
        int attemptNumber = state.attemptsFor(task.id()).stream()
                .mapToInt(TaskAttempt::attemptNumber)
                .max()
                .orElse(0) + 1;
        if (attemptNumber > task.maxAttempts()) {
            TaskNode blocked = task.transitionTo(TaskStatus.BLOCKED);
            return emit(
                    state,
                    HarnessEventType.TASK_BLOCKED,
                    task.id(),
                    new HarnessEventPayloads.TaskState(blocked, null, List.of()),
                    events
            );
        }

        Instant startedAt = Instant.now();
        TaskNode running = task.transitionTo(TaskStatus.RUNNING);
        TaskAttempt runningAttempt = new TaskAttempt(
                task.id(),
                attemptNumber,
                TaskAttemptStatus.RUNNING,
                "",
                List.of(),
                List.of(),
                startedAt,
                null
        );
        state = emit(
                state,
                HarnessEventType.TASK_STARTED,
                task.id(),
                new HarnessEventPayloads.TaskState(running, runningAttempt, List.of()),
                events
        );

        TaskExecutionResult executionResult;
        try {
            executionResult = taskExecutor.execute(new TaskExecutionContext(
                    state.mission(),
                    state.task(task.id()),
                    state.tasks(),
                    state.attempts(),
                    state.artifacts()
            ));
        } catch (RuntimeException exception) {
            executionResult = TaskExecutionResult.failure(
                    "任务执行器抛出异常",
                    exception.getClass().getSimpleName() + ": " + Objects.requireNonNullElse(exception.getMessage(), ""),
                    List.of("executorException=true")
            );
        }

        TaskNode submitted = state.task(task.id())
                .transitionTo(TaskStatus.SUBMITTED)
                .withOutputSummary(executionResult.summary());
        TaskAttempt submittedAttempt = new TaskAttempt(
                task.id(),
                attemptNumber,
                TaskAttemptStatus.SUBMITTED,
                executionResult.summary(),
                executionResult.evidence(),
                executionResult.artifacts(),
                startedAt,
                null
        );
        state = emit(
                state,
                HarnessEventType.TASK_SUBMITTED,
                task.id(),
                new HarnessEventPayloads.TaskState(submitted, submittedAttempt, executionResult.artifacts()),
                events
        );

        VerifierResult verifierResult;
        try {
            verifierResult = taskVerifier.verify(state.task(task.id()), executionResult);
        } catch (RuntimeException exception) {
            verifierResult = VerifierResult.block(
                    "验证器抛出异常: " + exception.getClass().getSimpleName(),
                    List.of("verifierException=true")
            );
        }
        List<String> evidence = joinEvidence(executionResult.evidence(), verifierResult.evidence());

        return switch (verifierResult.verdict()) {
            case PASS -> acceptTask(state, task.id(), submittedAttempt, executionResult, evidence, events);
            case RETRY -> retryOrBlock(
                    state,
                    task.id(),
                    submittedAttempt,
                    executionResult,
                    evidence,
                    verifierResult.reason(),
                    events
            );
            case BLOCK -> blockTask(
                    state,
                    task.id(),
                    submittedAttempt,
                    executionResult,
                    evidence,
                    verifierResult.reason(),
                    events
            );
        };
    }

    private MissionSnapshot acceptTask(
            MissionSnapshot state,
            String taskId,
            TaskAttempt submittedAttempt,
            TaskExecutionResult result,
            List<String> evidence,
            List<HarnessEvent> events
    ) {
        TaskAttempt acceptedAttempt = submittedAttempt.withStatus(
                TaskAttemptStatus.SUCCEEDED,
                result.summary(),
                evidence,
                result.artifacts(),
                Instant.now()
        );
        TaskNode accepted = state.task(taskId).transitionTo(TaskStatus.ACCEPTED);
        state = emit(
                state,
                HarnessEventType.TASK_ACCEPTED,
                taskId,
                new HarnessEventPayloads.TaskState(accepted, acceptedAttempt, result.artifacts()),
                events
        );

        for (TaskNode candidate : List.copyOf(state.tasks())) {
            if (candidate.status() == TaskStatus.PENDING && dependenciesAccepted(state, candidate)) {
                TaskNode ready = candidate.transitionTo(TaskStatus.READY);
                state = emit(
                        state,
                        HarnessEventType.TASK_READY,
                        candidate.id(),
                        new HarnessEventPayloads.TaskState(ready, null, List.of()),
                        events
                );
            }
        }
        return state;
    }

    private MissionSnapshot retryOrBlock(
            MissionSnapshot state,
            String taskId,
            TaskAttempt submittedAttempt,
            TaskExecutionResult result,
            List<String> evidence,
            String reason,
            List<HarnessEvent> events
    ) {
        TaskNode submitted = state.task(taskId);
        boolean hasAttemptsLeft = state.attemptsFor(taskId).size() < submitted.maxAttempts();
        if (!hasAttemptsLeft) {
            return blockTask(state, taskId, submittedAttempt, result, evidence, reason, events);
        }

        TaskAttempt retryableAttempt = submittedAttempt.withStatus(
                TaskAttemptStatus.RETRYABLE,
                reason,
                evidence,
                result.artifacts(),
                Instant.now()
        );
        TaskNode retryable = submitted.transitionTo(TaskStatus.RETRYABLE);
        state = emit(
                state,
                HarnessEventType.TASK_RETRY_SCHEDULED,
                taskId,
                new HarnessEventPayloads.TaskState(retryable, retryableAttempt, result.artifacts()),
                events
        );
        TaskNode ready = retryable.transitionTo(TaskStatus.READY);
        return emit(
                state,
                HarnessEventType.TASK_READY,
                taskId,
                new HarnessEventPayloads.TaskState(ready, retryableAttempt, result.artifacts()),
                events
        );
    }

    private MissionSnapshot blockTask(
            MissionSnapshot state,
            String taskId,
            TaskAttempt submittedAttempt,
            TaskExecutionResult result,
            List<String> evidence,
            String reason,
            List<HarnessEvent> events
    ) {
        TaskAttempt blockedAttempt = submittedAttempt.withStatus(
                TaskAttemptStatus.BLOCKED,
                reason,
                evidence,
                result.artifacts(),
                Instant.now()
        );
        TaskNode blocked = state.task(taskId).transitionTo(TaskStatus.BLOCKED);
        return emit(
                state,
                HarnessEventType.TASK_BLOCKED,
                taskId,
                new HarnessEventPayloads.TaskState(blocked, blockedAttempt, result.artifacts()),
                events
        );
    }

    private MissionSnapshot updateMissionAfterStep(
            MissionSnapshot state,
            List<HarnessEvent> events,
            boolean noSchedulableTask
    ) {
        if (state.mission().status().isTerminal()) {
            return state;
        }
        if (state.tasks().stream().allMatch(task -> task.status() == TaskStatus.ACCEPTED)) {
            state = emitMissionTransition(state, MissionStatus.VERIFYING, HarnessEventType.MISSION_VERIFYING, events);
            if (!state.artifacts().isEmpty()) {
                return emitMissionTransition(state, MissionStatus.SUCCEEDED, HarnessEventType.MISSION_COMPLETED, events);
            }
            return emitMissionTransition(state, MissionStatus.BLOCKED, HarnessEventType.MISSION_BLOCKED, events);
        }
        if (state.tasks().stream().anyMatch(task -> task.status() == TaskStatus.FAILED)) {
            return emitMissionTransition(state, MissionStatus.FAILED, HarnessEventType.MISSION_FAILED, events);
        }
        if (state.tasks().stream().anyMatch(task -> task.status() == TaskStatus.BLOCKED)) {
            return emitMissionTransition(state, MissionStatus.BLOCKED, HarnessEventType.MISSION_BLOCKED, events);
        }
        if (noSchedulableTask && state.tasks().stream().anyMatch(task -> !task.status().isTerminal())) {
            return emitMissionTransition(state, MissionStatus.BLOCKED, HarnessEventType.MISSION_BLOCKED, events);
        }
        return state;
    }

    private MissionSnapshot emitMissionTransition(
            MissionSnapshot state,
            MissionStatus nextStatus,
            HarnessEventType eventType,
            List<HarnessEvent> events
    ) {
        Mission nextMission = state.mission().transitionTo(nextStatus, Instant.now());
        return emit(
                state,
                eventType,
                null,
                new HarnessEventPayloads.MissionState(nextMission),
                events
        );
    }

    private MissionSnapshot emit(
            MissionSnapshot current,
            HarnessEventType type,
            String taskId,
            Object payload,
            List<HarnessEvent> emittedEvents
    ) {
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encode event payload " + type, exception);
        }
        HarnessEvent event = new HarnessEvent(
                current.lastEventSequence() + 1,
                current.mission().id(),
                taskId,
                type,
                payloadJson,
                Instant.now()
        );
        stateStore.appendEvent(event);
        MissionSnapshot next = StateReducer.apply(current, event);
        stateStore.saveCheckpoint(next.toCheckpoint());
        emittedEvents.add(event);
        return next;
    }

    private MissionSnapshot checkpoint(MissionSnapshot current, List<HarnessEvent> emittedEvents) {
        HarnessEvent event = new HarnessEvent(
                current.lastEventSequence() + 1,
                current.mission().id(),
                null,
                HarnessEventType.CHECKPOINT_SAVED,
                writePayload(new HarnessEventPayloads.CheckpointSaved(current.lastEventSequence())),
                Instant.now()
        );
        stateStore.appendEvent(event);
        MissionSnapshot next = StateReducer.apply(current, event);
        stateStore.saveCheckpoint(next.toCheckpoint());
        emittedEvents.add(event);
        return next;
    }

    private List<TaskNode> schedulableTasks(MissionSnapshot state) {
        return state.tasks().stream()
                .filter(task -> task.status() == TaskStatus.READY)
                .filter(task -> dependenciesAccepted(state, task))
                .toList();
    }

    private static boolean dependenciesAccepted(MissionSnapshot state, TaskNode task) {
        return task.dependencyIds().stream()
                .map(state::task)
                .allMatch(dependency -> dependency.status() == TaskStatus.ACCEPTED);
    }

    private static TaskAttempt latestAttempt(MissionSnapshot state, String taskId) {
        return state.attemptsFor(taskId).stream()
                .max(Comparator.comparingInt(TaskAttempt::attemptNumber))
                .orElse(null);
    }

    private static List<String> joinEvidence(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static TaskNode asPendingTask(TaskNode task) {
        return new TaskNode(
                task.id(),
                task.title(),
                task.dependencyIds(),
                TaskStatus.PENDING,
                task.maxAttempts(),
                task.inputSummary(),
                task.outputSummary()
        );
    }

    private static RunStopReason terminalReason(MissionStatus status) {
        return switch (status) {
            case SUCCEEDED -> RunStopReason.COMPLETED;
            case FAILED -> RunStopReason.FAILED;
            case BLOCKED -> RunStopReason.BLOCKED;
            case CANCELLED -> RunStopReason.CANCELLED;
            default -> null;
        };
    }

    private String writePayload(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encode checkpoint event payload", exception);
        }
    }
}
