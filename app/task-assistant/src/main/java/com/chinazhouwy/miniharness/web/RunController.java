package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Run Inspector 后端：Run 详情、事件回放与 SSE 实时流。 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final HarnessEngine engine;

    public RunController(HarnessEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (var run : engine.runs().findAll()) {
            runs.add(ApiMappers.run(run));
        }
        return runs;
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> runDetail(@PathVariable String runId) {
        var run = engine.runs().find(new RunId(runId))
                .orElseThrow(() -> new HarnessException("RUN_NOT_FOUND", "执行不存在: " + runId));
        Map<String, Object> detail = ApiMappers.run(run);
        List<Map<String, Object>> events = new ArrayList<>();
        for (HarnessEvent event : engine.eventLog().byRun(runId)) {
            events.add(ApiMappers.event(event));
        }
        detail.put("events", events);
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(required = false) String runId,
                                            @RequestParam(defaultValue = "0") long since) {
        List<HarnessEvent> source = runId != null && !runId.isBlank()
                ? engine.eventLog().byRun(runId) : engine.eventLog().since(since);
        List<Map<String, Object>> events = new ArrayList<>();
        for (HarnessEvent event : source) {
            if (event.seq() > since) {
                events.add(ApiMappers.event(event));
            }
        }
        return events;
    }

    /** SSE 实时事件流：任何引擎事件即刻推送（可用 runId/taskId 过滤）。 */
    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String runId,
                             @RequestParam(required = false) String taskId,
                             @RequestParam(defaultValue = "0") long since) {
        SseEmitter emitter = new SseEmitter(0L);
        // 立即发送握手事件：冲刷响应头，让浏览器 EventSource 尽快进入 OPEN 状态。
        // 注意：不使用具名事件（.name()），否则前端 onmessage 收不到，需按类型逐一 addEventListener。
        try {
            emitter.send(SseEmitter.event()
                    .data(Map.of("connected", true, "since", since)));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        Runnable unsubscribe = engine.events().subscribe(event -> {
            if (runId != null && !runId.isBlank()
                    && (event.runId() == null || !event.runId().value().equals(runId))) {
                return;
            }
            if (taskId != null && !taskId.isBlank()
                    && (event.taskId() == null || !event.taskId().value().equals(taskId))) {
                return;
            }
            if (event.seq() <= since) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.seq()))
                        .data(ApiMappers.event(event)));
            } catch (IOException | IllegalStateException e) {
                // 客户端断开等发送失败：结束本次连接。
                emitter.completeWithError(e);
            }
        });
        emitter.onCompletion(unsubscribe::run);
        emitter.onTimeout(unsubscribe::run);
        emitter.onError(t -> unsubscribe.run());
        return emitter;
    }
}
