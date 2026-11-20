package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}