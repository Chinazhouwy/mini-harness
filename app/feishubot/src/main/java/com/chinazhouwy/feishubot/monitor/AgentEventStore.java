package com.chinazhouwy.feishubot.monitor;

import com.chinazhouwy.feishubot.ProjectPaths;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 把 AgentScope 事件流写成 JSONL，并发布到进程内事件总线。 */
@Component
public class AgentEventStore {
    private static final Logger log = LoggerFactory.getLogger(AgentEventStore.class);
    private static final int MAX_SUMMARY = 300;
    private static final int MAX_DETAIL = 4000;
    private static final Object WRITE_LOCK = new Object();

    private final Path logDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer();
    private final ConcurrentHashMap<String, CallStats> modelCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CallStats> toolCalls = new ConcurrentHashMap<>();

    public AgentEventStore() {
        this(ProjectPaths.home().resolve("logs"));
    }

    /** 测试用构造函数，避免依赖项目根目录探测。 */
    public AgentEventStore(Path logDir) {
        this.logDir = logDir;
    }

    /** 每次飞书消息生成一个 traceId，同时放进 RuntimeContext 传给子 Agent。 */
    public RuntimeContext newContext(String sessionId, String messageId) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        return RuntimeContext.builder()
                .userId("feishu-user")
                .sessionId(sessionId)
                .put("feishu.traceId", traceId)
                .put("feishu.messageId", messageId == null ? "-" : messageId)
                .build();
    }

    /** 应用边界事件：收到消息、生成回复、发送完成、处理失败。 */
    public void appEvent(RuntimeContext context, String type, String status, String name, String summary) {
        append(base(context, type, status, name, null, summary));
    }

    /** AgentScope 运行事件：直接消费 streamEvents() 发出的事件。 */
    public void agentEvent(RuntimeContext context, AgentEvent event) {
        String type = event.getType().name();
        Map<String, Object> metadata = event.getMetadata();
        boolean forwardedSubagent = metadata != null && metadata.containsKey("subagent_event");
        if (forwardedSubagent) type = "SUBAGENT_EVENT";

        Map<String, Object> e = base(context, type, status(event), eventName(event),
                toolCallId(event), summary(event));
        e.put("eventId", event.getId());
        e.put("ts", event.getCreatedAt());
        e.put("source", event.getSource());
        String replyId = replyId(event);
        if (replyId != null) e.put("replyId", replyId);
        if (forwardedSubagent && context != null) {
            e.put("parentSessionId", context.getSessionId());
        }

        ExecutionIdentity identity = event.getExecution();
        if (identity != null) {
            if (identity.sessionId() != null) e.put("sessionId", identity.sessionId());
            if (identity.runId() != null) e.put("runId", identity.runId());
            if (identity.turnId() != null) e.put("turnId", identity.turnId());
        }

        if (metadata != null) {
            for (String key : new String[]{"parentSessionId", "parentRunId", "parentTurnId"}) {
                Object value = metadata.get(key);
                if (value != null) e.put(key, String.valueOf(value));
            }
            Object subEvent = metadata.get("subagent_event");
            if (subEvent != null) {
                e.put("eventType", "SUBAGENT_EVENT");
                e.put("subagentEvent", String.valueOf(subEvent));
            }
            Object subName = metadata.get("subagent_name");
            if (subName != null) e.put("subagentName", String.valueOf(subName));
            Object subId = metadata.get("subagent_id");
            if (subId != null) e.put("subagentId", String.valueOf(subId));
            Object subSession = metadata.get("subagent_session_id");
            if (subSession != null) {
                String child = String.valueOf(subSession);
                e.put("subagentSessionId", child);
                // 子 Agent 的 session 是随机值；同时把父 session 写进去，历史查询仍能挂回主链路。
                e.put("parentSessionId", context == null ? "-" : context.getSessionId());
                e.put("sessionId", child);
            }
        }
        append(e);
    }

    /** 查询某次飞书消息对应的事件链。 */
    public List<Map<String, Object>> byTraceId(String traceId) {
        return read().stream()
                .filter(e -> eq(traceId, str(e.get("traceId"))))
                .toList();
    }

    /** 查询同一 bot:chatId 的历史事件；可用于列出会话下所有 trace。 */
    public List<Map<String, Object>> bySessionId(String sessionId) {
        return read().stream()
                .filter(e -> eq(sessionId, str(e.get("sessionId")))
                        || eq(sessionId, str(e.get("parentSessionId"))))
                .toList();
    }

    /** 最近若干条事件，给监控页自动列出 trace 用；不要求用户先知道任何 ID。 */
    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> all = read();
        if (limit <= 0 || all.size() <= limit) return all;
        return all.subList(all.size() - limit, all.size());
    }

    /** 进程内实时事件总线；页面统一通过文件或 HTTP 读取，不直接订阅。 */
    public Flux<Map<String, Object>> stream() {
        return sink.asFlux();
    }

    private Map<String, Object> base(RuntimeContext context, String type, String status,
                                     String name, String toolCallId, String summary) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString().replace("-", ""));
        event.put("ts", LocalDateTime.now().toString());
        event.put("eventType", type);
        event.put("traceId", context == null ? "-" : str(context.get("feishu.traceId")));
        event.put("messageId", context == null ? "-" : str(context.get("feishu.messageId")));
        event.put("sessionId", context == null ? "-" : context.getSessionId());
        event.put("parentSessionId", "-");
        event.put("userId", context == null ? "-" : context.getUserId());
        event.put("runId", context == null ? "-" : context.getRunId());
        event.put("turnId", "-");
        event.put("replyId", "-");
        event.put("source", null);
        event.put("name", name);
        event.put("status", status);
        event.put("toolCallId", toolCallId);
        event.put("summary", clip(summary, MAX_SUMMARY));
        return event;
    }

    private void append(Map<String, Object> event) {
        String line;
        try {
            line = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("事件序列化失败：{}", e.getMessage());
            return;
        }
        Path file = logDir.resolve("feishu-events-" + LocalDate.now() + ".jsonl");
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(logDir);
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("事件写入失败：{}", e.getMessage());
            }
        }
        sink.tryEmitNext(event);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> read() {
        List<Map<String, Object>> all = new ArrayList<>();
        if (!Files.isDirectory(logDir)) return all;
        try (var paths = Files.list(logDir)) {
            List<Path> files = paths.filter(p -> p.getFileName().toString().startsWith("feishu-events-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    all.add((Map<String, Object>) mapper.readValue(line, Map.class));
                }
            }
        } catch (IOException e) {
            log.warn("事件读取失败：{}", e.getMessage());
        }
        return all;
    }

    /** 中间件聚合模型/工具调用的入参、出参、耗时，只写一条详情事件，不改调用链。 */
    public MiddlewareBase middleware() {
        return new MiddlewareBase() {
            @Override
            public Set<ExtensionPoint> activePoints() {
                return EnumSet.of(ExtensionPoint.ON_MODEL_CALL, ExtensionPoint.ON_ACTING);
            }

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                String requestKey = modelKey(ctx, "pending-" + UUID.randomUUID());
                String modelId = input != null && input.model() != null ? input.model().getModelName() : "-";
                String modelInput = modelInput(input);
                long start = System.nanoTime();
                modelCalls.put(requestKey, new CallStats(modelId, modelInput, start));
                return next.apply(input).doOnNext(event -> collectModelEvent(ctx, requestKey, event, modelId, modelInput, start));
            }

            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                             Function<ActingInput, Flux<AgentEvent>> next) {
                if (input != null && input.toolCalls() != null) {
                    for (ToolUseBlock tool : input.toolCalls()) {
                        if (tool == null) continue;
                        String id = tool.getId() == null ? "-" : tool.getId();
                        String args = tool.getInput() == null ? "" : mapper.valueToTree(tool.getInput()).toString();
                        String key = traceKey(ctx) + ":" + id;
                        toolCalls.put(key, new CallStats(tool.getName(), args, System.nanoTime()));
                        detailEvent(ctx, "TOOL_CALL_DETAIL", "START", tool.getName(), id,
                                null, null, args, null, null, "-");
                    }
                }
                return next.apply(input).doOnNext(event -> collectToolEvent(ctx, event));
            }
        };
    }

    private void collectModelEvent(RuntimeContext ctx, String pendingKey, AgentEvent event,
                                   String modelId, String modelInput, long start) {
        if (event instanceof ModelCallStartEvent e) {
            String replyId = e.getReplyId();
            String key = modelKey(ctx, replyId);
            CallStats stats = modelCalls.remove(pendingKey);
            if (stats == null) stats = new CallStats(modelId, modelInput, start);
            stats.output.setLength(0);
            modelCalls.put(key, stats);
            return;
        }
        if (event instanceof TextBlockDeltaEvent e) {
            appendModelOutput(ctx, e.getReplyId(), e.getDelta());
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent e) {
            appendModelOutput(ctx, e.getReplyId(), e.getDelta());
            return;
        }
        if (event instanceof ToolCallDeltaEvent e) {
            appendModelOutput(ctx, e.getReplyId(), e.getDelta());
            return;
        }
        if (event instanceof ModelCallEndEvent e) {
            String key = modelKey(ctx, e.getReplyId());
            CallStats stats = modelCalls.remove(key);
            if (stats == null) stats = new CallStats(modelId, modelInput, start);
            ChatUsage usage = e.getUsage();
            long durationMs = usage != null && usage.getTime() > 0
                    ? Math.round(usage.getTime() * 1000)
                    : (System.nanoTime() - stats.startNanos) / 1_000_000;
            detailEvent(ctx, "MODEL_CALL_DETAIL", "END", stats.name, null,
                    e.getReplyId(), stats.name, stats.input, stats.output.toString(), durationMs, null);
        }
    }

    private void collectToolEvent(RuntimeContext ctx, AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent e) {
            appendToolOutput(ctx, e.getToolCallId(), e.getDelta());
            return;
        }
        if (event instanceof ToolResultDataDeltaEvent e) {
            appendToolOutput(ctx, e.getToolCallId(), toJson(e.getData()));
            return;
        }
        if (event instanceof ToolProgressEvent e) {
            String text = e.getText();
            if (text != null && !text.isBlank()) appendToolOutput(ctx, e.getToolCallId(), text);
            return;
        }
        if (event instanceof ToolResultEndEvent e) {
            String key = traceKey(ctx) + ":" + e.getToolCallId();
            CallStats stats = toolCalls.remove(key);
            long durationMs = stats == null ? 0 : (System.nanoTime() - stats.startNanos) / 1_000_000;
            detailEvent(ctx, "TOOL_CALL_DETAIL", String.valueOf(e.getState()), e.getToolCallName(),
                    e.getToolCallId(), e.getReplyId(), null,
                    stats == null ? null : stats.input, stats == null ? null : stats.output.toString(),
                    durationMs, null);
        }
    }

    private void detailEvent(RuntimeContext ctx, String type, String status, String name, String toolCallId,
                             String replyId, String modelId, String input, String output,
                             Long durationMs, String subagentSessionId) {
        Map<String, Object> e = base(ctx, type, status, name, toolCallId, output);
        if (replyId != null) e.put("replyId", replyId);
        if (modelId != null) e.put("modelId", modelId);
        if (durationMs != null) e.put("durationMs", durationMs);
        e.put("input", clip(input, MAX_DETAIL));
        e.put("output", clip(output, MAX_DETAIL));
        e.put("detail", true);
        if (subagentSessionId != null) e.put("subagentSessionId", subagentSessionId);
        append(e);
    }

    private void appendModelOutput(RuntimeContext ctx, String replyId, String delta) {
        if (delta == null || delta.isEmpty()) return;
        CallStats stats = modelCalls.get(modelKey(ctx, replyId));
        if (stats != null && stats.output.length() < MAX_DETAIL) stats.output.append(delta);
    }

    private void appendToolOutput(RuntimeContext ctx, String toolCallId, String delta) {
        if (delta == null || delta.isEmpty()) return;
        CallStats stats = toolCalls.get(traceKey(ctx) + ":" + toolCallId);
        if (stats != null && stats.output.length() < MAX_DETAIL) stats.output.append(delta);
    }

    private static String modelInput(ModelCallInput input) {
        if (input == null || input.messages() == null) return "";
        try {
            return JsonUtils.getJsonCodec().toJson(input.messages());
        } catch (RuntimeException e) {
            return input.messages().toString();
        }
    }

    private static String toJson(Object value) {
        if (value == null) return "";
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    private static String modelKey(RuntimeContext ctx, String replyId) {
        return traceKey(ctx) + ":reply:" + str(replyId);
    }

    private static String traceKey(RuntimeContext ctx) {
        return ctx == null ? "-" : str(ctx.get("feishu.traceId"));
    }

    private static final class CallStats {
        private final String name;
        private final String input;
        private final long startNanos;
        private final StringBuilder output = new StringBuilder();

        private CallStats(String name, String input, long startNanos) {
            this.name = str(name);
            this.input = str(input);
            this.startNanos = startNanos;
        }
    }

    private static String eventName(AgentEvent event) {
        if (event instanceof AgentStartEvent e) return e.getName();
        if (event instanceof ToolCallStartEvent e) return e.getToolCallName();
        if (event instanceof ToolCallEndEvent e) return e.getToolCallName();
        if (event instanceof ToolResultStartEvent e) return e.getToolCallName();
        if (event instanceof ToolResultEndEvent e) return e.getToolCallName();
        if (event instanceof ToolProgressEvent e) return e.getToolCallName();
        if (event instanceof ToolResultTextDeltaEvent e) return e.getToolCallName();
        return event.getClass().getSimpleName();
    }

    private static String status(AgentEvent event) {
        if (event instanceof ToolResultEndEvent e) return String.valueOf(e.getState());
        if (event instanceof AgentResultEvent) return "RESULT";
        if (event instanceof AgentEndEvent) return "END";
        if (event instanceof ModelCallEndEvent) return "END";
        if (event.getClass().getSimpleName().endsWith("EndEvent")) return "END";
        return "START";
    }

    private static String toolCallId(AgentEvent event) {
        if (event instanceof ToolCallStartEvent e) return e.getToolCallId();
        if (event instanceof ToolCallEndEvent e) return e.getToolCallId();
        if (event instanceof ToolResultStartEvent e) return e.getToolCallId();
        if (event instanceof ToolResultEndEvent e) return e.getToolCallId();
        if (event instanceof ToolProgressEvent e) return e.getToolCallId();
        if (event instanceof ToolResultTextDeltaEvent e) return e.getToolCallId();
        if (event instanceof ToolResultDataDeltaEvent e) return e.getToolCallId();
        return "-";
    }

    private static String replyId(AgentEvent event) {
        if (event instanceof AgentStartEvent e) return e.getReplyId();
        if (event instanceof AgentEndEvent e) return e.getReplyId();
        if (event instanceof ModelCallStartEvent e) return e.getReplyId();
        if (event instanceof ModelCallEndEvent e) return e.getReplyId();
        if (event instanceof ToolCallStartEvent e) return e.getReplyId();
        if (event instanceof ToolCallEndEvent e) return e.getReplyId();
        if (event instanceof ToolResultStartEvent e) return e.getReplyId();
        if (event instanceof ToolResultEndEvent e) return e.getReplyId();
        if (event instanceof ToolProgressEvent e) return e.getReplyId();
        if (event instanceof ToolResultTextDeltaEvent e) return e.getReplyId();
        return null;
    }

    private static String summary(AgentEvent event) {
        if (event instanceof AgentStartEvent e) return "name=" + e.getName() + ",role=" + e.getRole();
        if (event instanceof AgentResultEvent e) {
            Msg result = e.getResult();
            return result == null ? "" : String.valueOf(result.getTextContent());
        }
        if (event instanceof SubagentExposedEvent e) {
            return "subagentId=" + e.getSubagentId() + ",sessionId=" + e.getSessionId();
        }
        if (event instanceof ModelCallStartEvent) return "model call start";
        if (event instanceof ModelCallEndEvent) return "model call end";
        return event.getType().name();
    }

    private static String str(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equals(b);
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
