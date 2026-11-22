package com.chinazhouwy.feishubot.monitor;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void eventsCarryTraceAndSessionAndCanBeQueried() throws Exception {
        AgentEventStore store = new AgentEventStore(tempDir);
        RuntimeContext context = store.newContext("main:chat-1", "msg-1");
        String traceId = context.get("feishu.traceId").toString();

        store.appEvent(context, "MESSAGE_RECEIVED", "START", "feishu", "hello");
        store.appEvent(context, "AGENT_REPLY", "OK", "小飞", "你好");

        List<Map<String, Object>> byTrace = store.byTraceId(traceId);
        List<Map<String, Object>> bySession = store.bySessionId("main:chat-1");
        assertThat(byTrace).hasSize(2);
        assertThat(bySession).hasSize(2);
        assertThat(byTrace.get(0).get("eventType")).isEqualTo("MESSAGE_RECEIVED");
        assertThat(byTrace.get(0).get("traceId")).isEqualTo(traceId);
        assertThat(byTrace.get(0).get("messageId")).isEqualTo("msg-1");
        assertThat(byTrace.get(0).get("sessionId")).isEqualTo("main:chat-1");
        assertThat(store.recent(10)).hasSize(2);
        assertThat(store.recent(1)).hasSize(1);

        Path file = tempDir.resolve("feishu-events-" + java.time.LocalDate.now() + ".jsonl");
        assertThat(Files.readString(file)).contains("MESSAGE_RECEIVED", traceId, "main:chat-1");
    }

    @Test
    void agentEventsCarryIdentityAndSubagentParentLink() {
        AgentEventStore store = new AgentEventStore(tempDir);
        RuntimeContext context = store.newContext("main:chat-2", "msg-2");

        store.agentEvent(context, new ModelCallStartEvent("reply-9"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subagent_event", "MODEL_CALL_START");
        metadata.put("subagent_session_id", "child-session-77");
        metadata.put("subagent_name", "feishu-sub-agent");
        metadata.put("parentSessionId", "main:chat-2");
        AgentEvent forwarded = new ToolProgressEvent("e-1", "2026-11-20T12:00:00", "reply-9",
                "call-9", "spawn_agent", null, metadata);
        store.agentEvent(context, forwarded);

        List<Map<String, Object>> events = store.byTraceId(context.get("feishu.traceId").toString());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("replyId")).isEqualTo("reply-9");
        assertThat(events.get(1).get("eventType")).isEqualTo("SUBAGENT_EVENT");
        assertThat(events.get(1).get("toolCallId")).isEqualTo("call-9");
        assertThat(events.get(1).get("subagentSessionId")).isEqualTo("child-session-77");
        assertThat(events.get(1).get("parentSessionId")).isEqualTo("main:chat-2");
        assertThat(store.bySessionId("main:chat-2")).hasSize(2);
        assertThat(store.bySessionId("child-session-77")).hasSize(1);
    }
    @Test
    void middlewareAggregatesModelAndToolDetails() {
        AgentEventStore store = new AgentEventStore(tempDir);
        RuntimeContext context = store.newContext("main:chat-3", "msg-3");
        MiddlewareBase middleware = store.middleware();

        // 模拟一次模型请求：开始、流式文本增量、结束；中间件应写出一条带入参/出参/耗时的详情。
        Model fakeModel = new Model() {
            @Override public String getModelName() { return "test-model-id"; }
            @Override public Flux<io.agentscope.core.model.ChatResponse> stream(
                    List<io.agentscope.core.message.Msg> messages,
                    List<io.agentscope.core.model.ToolSchema> tools,
                    io.agentscope.core.model.GenerateOptions options) {
                return Flux.empty();
            }
        };
        ModelCallInput modelInput = new ModelCallInput(List.of(), List.of(), null, fakeModel);
        middleware.onModelCall(null, context, modelInput, in -> Flux.just(
                new ModelCallStartEvent("reply-77"),
                new TextBlockDeltaEvent("reply-77", "block-1", "答案A"),
                new ModelCallEndEvent("reply-77", new ChatUsage(3, 5, 1.25))
        )).blockLast(Duration.ofSeconds(10));

        // 模拟一次工具调用：ON_ACTING 记录参数，工具结果增量拼出参，结束事件写详情。
        ToolUseBlock tool = new ToolUseBlock("call-77", "read", Map.of("path", "demo.txt"));
        middleware.onActing(null, context, new ActingInput(List.of(tool)), in -> Flux.just(
                new ToolResultTextDeltaEvent("reply-77", "call-77", "read", "hello"),
                new ToolResultEndEvent("reply-77", "call-77", "read", ToolResultState.SUCCESS)
        )).blockLast(Duration.ofSeconds(10));

        List<Map<String, Object>> events = store.byTraceId(context.get("feishu.traceId").toString());
        Map<String, Object> model = events.stream()
                .filter(e -> "MODEL_CALL_DETAIL".equals(e.get("eventType")))
                .findFirst().orElseThrow();
        assertThat(model.get("modelId")).isEqualTo("test-model-id");
        assertThat(String.valueOf(model.get("output"))).contains("答案A");
        assertThat(((Number) model.get("durationMs")).longValue()).isEqualTo(1250L);
        assertThat(model.get("detail")).isEqualTo(true);

        Map<String, Object> toolDetail = events.stream()
                .filter(e -> "TOOL_CALL_DETAIL".equals(e.get("eventType")) && "SUCCESS".equals(e.get("status")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(toolDetail.get("input"))).contains("demo.txt");
        assertThat(String.valueOf(toolDetail.get("output"))).contains("hello");
        assertThat(toolDetail.get("toolCallId")).isEqualTo("call-77");
        assertThat(toolDetail.get("durationMs")).isNotNull();
    }

}
