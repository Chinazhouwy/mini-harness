package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.task.Task;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Task Center 后端：任务列表、详情、追加信息、发起执行。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final HarnessEngine engine;

    public TaskController(HarnessEngine engine) {
        this.engine = engine;
    }

    public record CreateTaskRequest(String input, String sessionId, Map<String, Object> metadata) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody CreateTaskRequest request) {
        if (request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        var sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? com.chinazhouwy.miniharness.runtime.core.SessionId.random()
                : new com.chinazhouwy.miniharness.runtime.core.SessionId(request.sessionId());
        var result = engine.handleUserInput(request.input(), sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", result.decision().action().name());
        response.put("reason", result.decision().reason());
        response.put("needsUserConfirmation", result.decision().needsUserConfirmation());
        response.put("question", result.decision().question());
        response.put("clarificationCandidates", result.decision().clarificationCandidates().stream()
                .map(TaskId::value).toList());
        if (result.task() != null) {
            if (request.metadata() != null) {
                request.metadata().forEach(result.task()::putMetadata);
            }
            response.put("task", ApiMappers.task(result.task()));
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public List<Map<String, Object>> listTasks() {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Task task : engine.tasks().findAll()) {
            tasks.add(ApiMappers.task(task));
        }
        return tasks;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> taskDetail(@PathVariable String taskId) {
        Task task = engine.tasks().find(new TaskId(taskId))
                .orElseThrow(() -> new com.chinazhouwy.miniharness.runtime.core.HarnessException(
                        "TASK_NOT_FOUND", "任务不存在: " + taskId));
        Map<String, Object> detail = ApiMappers.task(task);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (var run : engine.runs().findByTask(task.id())) {
            runs.add(ApiMappers.run(run));
        }
        detail.put("runs", runs);
        return ResponseEntity.ok(detail);
    }

    public record AppendMessageRequest(String content, String linkedRunId) {
    }

    /** 追加信息/质疑：质疑类消息自动关联原 Run 并留标注；歧义时返回确认问题。 */
    @PostMapping("/{taskId}/messages")
    public ResponseEntity<Map<String, Object>> appendMessage(@PathVariable String taskId,
                                                             @RequestBody AppendMessageRequest request) {
        Task task = engine.tasks().find(new TaskId(taskId))
                .orElseThrow(() -> new com.chinazhouwy.miniharness.runtime.core.HarnessException(
                        "TASK_NOT_FOUND", "任务不存在: " + taskId));
        Map<String, Object> response = new LinkedHashMap<>();
        var decision = engine.route(request.content());
        if (decision.action() == com.chinazhouwy.miniharness.runtime.capability.RoutingAction.CONTINUE_TASK
                && decision.taskId() != null && decision.taskId().value().equals(taskId)) {
            RunId linkedRunId = request.linkedRunId() == null
                    ? lastRunOf(task) : new RunId(request.linkedRunId());
            engine.challengeResult(task.id(), request.content(), linkedRunId);
            var verificationRunId = engine.startRun(task.id(), null, "CHALLENGE");
            response.put("linked", true);
            response.put("linkedRunId", linkedRunId == null ? null : linkedRunId.value());
            response.put("verificationRunId", verificationRunId.value());
            response.put("reply", "已关联原执行并开始核查");
        } else if (decision.action() == com.chinazhouwy.miniharness.runtime.capability.RoutingAction.CLARIFY_TASK_LINK) {
            response.put("linked", false);
            response.put("needsUserConfirmation", true);
            response.put("question", decision.question());
            response.put("clarificationCandidates", decision.clarificationCandidates().stream()
                    .map(TaskId::value).toList());
        } else {
            engine.challengeResult(task.id(), request.content(),
                    request.linkedRunId() == null ? lastRunOf(task) : new RunId(request.linkedRunId()));
            response.put("linked", false);
            response.put("reply", "消息已记录，未自动关联核查（" + decision.action() + "）");
        }
        return ResponseEntity.ok(response);
    }

    public record StartRunRequest(String workflowName, Integer workflowVersion, Map<String, Object> input) {
    }

    @PostMapping("/{taskId}/runs")
    public ResponseEntity<Map<String, Object>> startRun(@PathVariable String taskId,
                                                        @RequestBody StartRunRequest request) {
        Task task = engine.tasks().find(new TaskId(taskId))
                .orElseThrow(() -> new com.chinazhouwy.miniharness.runtime.core.HarnessException(
                        "TASK_NOT_FOUND", "任务不存在: " + taskId));
        if (request.input() != null) {
            request.input().forEach(task::putMetadata);
        }
        com.chinazhouwy.miniharness.runtime.core.WorkflowRef ref = null;
        if (request.workflowName() != null && !request.workflowName().isBlank()) {
            int version = request.workflowVersion() != null
                    ? request.workflowVersion()
                    : engine.workflows().nextVersion(request.workflowName()) - 1;
            ref = new com.chinazhouwy.miniharness.runtime.core.WorkflowRef(request.workflowName(), version);
        }
        var runId = engine.startRun(task.id(), ref, "USER");
        return ResponseEntity.ok(Map.of("runId", runId.value()));
    }

    private RunId lastRunOf(Task task) {
        var runs = task.runIds();
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }
}
