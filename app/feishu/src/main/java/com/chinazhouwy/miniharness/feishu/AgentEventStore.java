package com.chinazhouwy.miniharness.feishu;

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
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
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
import java.util.function.Function;

/** 把 AgentScope 事件流写成 JSONL，并发布到进程内事件总线。 */
@Component
public class AgentEventStore {
    private static final Logger log = LoggerFactory.getLogger(AgentEventStore.class);
    private static final int MAX_SUMMARY = 300;
    private static final Object WRITE_LOCK = new Object();

    private final Path logDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer();

    public AgentEventStore() {
        this(ProjectPaths.home().resolve("logs"));
    }

    /** 测试用构造函数，避免依赖项目根目录探测。 */
    AgentEventStore(Path logDir) {
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
        Map<String, Object> e = base(context, event.getType().name(), status(event), eventName(event),
                toolCallId(event), summary(event));
        e.put("eventId", event.getId());
        e.put("ts", event.getCreatedAt());
        e.put("source", event.getSource());
        ExecutionIdentity identity = event.getExecution();
        if (identity != null) {
            e.put("sessionId", identity.sessionId());
            e.put("runId", identity.runId());
            e.put("turnId", identity.turnId());
        }
        Map<String, Object> metadata = event.getMetadata();
        if (metadata != null && metadata.get("parentSessionId") != null) {
            e.put("parentSessionId", String.valueOf(metadata.get("parentSessionId")));
        }
        if (identity != null && identity.agentId() != null) e.put("name", identity.agentId());
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
        event.put("summary", clip(summary));
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

    /** 用于 streamEvents() 的中间件：记录工具名与参数摘要，不改调用内容。 */
    public MiddlewareBase middleware() {
        return new MiddlewareBase() {
            @Override
            public Set<ExtensionPoint> activePoints() {
                return EnumSet.of(ExtensionPoint.ON_ACTING);
            }

            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                              Function<ActingInput, Flux<AgentEvent>> next) {
                if (input != null && input.toolCalls() != null) {
                    for (ToolUseBlock tool : input.toolCalls()) {
                        if (tool == null) continue;
                        String args = tool.getInput() == null ? "" : mapper.valueToTree(tool.getInput()).toString();
                        appEvent(ctx, "TOOL_CALL_START", "START", tool.getName(), args);
                    }
                }
                return next.apply(input);
            }
        };
    }

    private static String eventName(AgentEvent event) {
        if (event instanceof AgentStartEvent e) return e.getName();
        if (event instanceof ToolCallStartEvent e) return e.getToolCallName();
        if (event instanceof ToolCallEndEvent e) return e.getToolCallName();
        if (event instanceof ToolResultStartEvent e) return e.getToolCallName();
        if (event instanceof ToolResultEndEvent e) return e.getToolCallName();
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
        return "-";
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

    private static String clip(String value) {
        if (value == null) return "";
        String oneLine = value.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        return oneLine.length() <= MAX_SUMMARY ? oneLine : oneLine.substring(0, MAX_SUMMARY) + "...";
    }
}