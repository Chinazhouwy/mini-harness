package com.chinazhouwy.miniharness.runtime.task;

import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.workflow.NodeResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次执行的记录：关联 Task 与 Workflow 版本，聚合节点结果与用量。
 * SUCCEEDED 只表示流程走完；业务结果正确性由结构化输出与评测判断，不由执行状态代答。
 */
public class Run {

    private final RunId id;
    private final TaskId taskId;
    private final WorkflowRef workflow;
    private final String triggeredBy;
    private final Instant queuedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private RunStatus status;
    private String errorCode;
    private String errorSummary;
    private final List<NodeResult> nodeResults = new ArrayList<>();
    private final Map<String, Object> output = new LinkedHashMap<>();
    private final Map<String, Object> input = new LinkedHashMap<>();

    public Run(RunId id, TaskId taskId, WorkflowRef workflow, String triggeredBy, Instant queuedAt, Map<String, Object> input) {
        this.id = id;
        this.taskId = taskId;
        this.workflow = workflow;
        this.triggeredBy = triggeredBy;
        this.queuedAt = queuedAt;
        this.input.putAll(input);
        this.status = RunStatus.QUEUED;
    }

    public synchronized RunId id() {
        return id;
    }

    public synchronized TaskId taskId() {
        return taskId;
    }

    public synchronized WorkflowRef workflow() {
        return workflow;
    }

    public synchronized String triggeredBy() {
        return triggeredBy;
    }

    public synchronized Instant queuedAt() {
        return queuedAt;
    }

    public synchronized Instant startedAt() {
        return startedAt;
    }

    public synchronized Instant finishedAt() {
        return finishedAt;
    }

    public synchronized RunStatus status() {
        return status;
    }

    public synchronized String errorCode() {
        return errorCode;
    }

    public synchronized String errorSummary() {
        return errorSummary;
    }

    public synchronized Map<String, Object> input() {
        return new LinkedHashMap<>(input);
    }

    public synchronized List<NodeResult> nodeResults() {
        return List.copyOf(nodeResults);
    }

    public synchronized Map<String, Object> output() {
        return new LinkedHashMap<>(output);
    }

    public synchronized void transitionTo(RunStatus target) {
        status.requireTransitionTo(target);
        if (target == RunStatus.RUNNING) {
            startedAt = Instant.now();
        }
        if (target.isTerminal()) {
            finishedAt = Instant.now();
        }
        status = target;
    }

    /** 允许 QUEUED → RUNNING 由执行器补写 startedAt 之外的显式开始时间。 */
    public synchronized void markStartedAt(Instant instant) {
        this.startedAt = instant;
    }

    public synchronized void appendNodeResult(NodeResult result) {
        nodeResults.add(result);
    }

    public synchronized void recordError(String errorCode, String errorSummary) {
        this.errorCode = errorCode;
        this.errorSummary = errorSummary;
    }

    public synchronized void recordOutput(Map<String, Object> output) {
        this.output.clear();
        if (output != null) {
            this.output.putAll(output);
        }
    }
}
