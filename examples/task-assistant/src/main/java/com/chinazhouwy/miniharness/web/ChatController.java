package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.capability.RoutingAction;
import com.chinazhouwy.miniharness.runtime.core.SessionId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.task.Run;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat 页面后端：用户消息 → 能力路由 →（自动）建任务并执行 / 关联核查 / 请求确认。
 * 路由与执行状态由 Harness 管控；模型只是可选的语义辅助，不是流程的主人。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final HarnessEngine engine;

    public ChatController(HarnessEngine engine) {
        this.engine = engine;
    }

    public record ChatRequest(String sessionId, String input) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        if (request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("输入不能为空");
        }
        SessionId sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? SessionId.random() : new SessionId(request.sessionId());
        var result = engine.handleUserInput(request.input(), sessionId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId.value());
        response.put("action", result.decision().action().name());
        response.put("reply", result.reply());
        response.put("reason", result.decision().reason());
        response.put("needsUserConfirmation", result.decision().needsUserConfirmation());
        response.put("clarificationCandidates", result.decision().clarificationCandidates().stream()
                .map(TaskId::value).toList());
        if (result.task() != null) {
            response.put("taskId", result.task().id().value());
            // 研究类新任务与质疑类继续任务都自动发起一次执行（Chat 的产品语义）。
            if (result.decision().action() == RoutingAction.NEW_TASK_WITH_WORKFLOW
                    || result.decision().action() == RoutingAction.CONTINUE_TASK) {
                var runId = engine.startRun(result.task().id(), null, "CHAT");
                response.put("runId", runId.value());
            }
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId) {
        var session = engine.sessionById(sessionId);
        return session == null ? Map.of("id", sessionId, "taskIds", List.of(), "messages", List.of())
                : ApiMappers.session(session);
    }

    /** 等待某次执行结束（轮询替代；前端实时流走 SSE）。 */
    @GetMapping("/runs/{runId}/await")
    public ResponseEntity<Map<String, Object>> awaitRun(@PathVariable String runId) {
        Run run = engine.awaitRun(new com.chinazhouwy.miniharness.runtime.core.RunId(runId), 60_000);
        return ResponseEntity.ok(ApiMappers.run(run));
    }
}
