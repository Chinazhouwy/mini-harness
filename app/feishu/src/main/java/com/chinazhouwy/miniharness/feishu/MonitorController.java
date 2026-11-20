package com.chinazhouwy.miniharness.feishu;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** 最小监控查询接口：按 traceId 看单次链路，按 sessionId 看历史链路。 */
@RestController
public class MonitorController {
    private final AgentEventStore eventStore;

    public MonitorController(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @GetMapping(value = "/api/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> events(@RequestParam(required = false) String traceId,
                                            @RequestParam(required = false) String sessionId,
                                            @RequestParam(required = false, defaultValue = "500") int limit) {
        if (traceId != null && !traceId.isBlank()) return eventStore.byTraceId(traceId);
        if (sessionId != null && !sessionId.isBlank()) return eventStore.bySessionId(sessionId);
        return eventStore.recent(limit);
    }

    /** 进程内实时总线；服务重启后历史请走 /api/events。 */
    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> stream() {
        return eventStore.stream();
    }
}
