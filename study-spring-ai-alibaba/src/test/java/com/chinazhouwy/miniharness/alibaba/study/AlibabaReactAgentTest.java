package com.chinazhouwy.miniharness.alibaba.study;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.chinazhouwy.miniharness.model.ModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本模块使用 Spring AI 1.1.2，与 Spring AI 2.0.2 学习模块分别编译和运行。 */
class AlibabaReactAgentTest {
    public static class PingTool {
        int calls;

        @Tool(description = "执行连通性检查，返回 pong 与调用方名称")
        public String ping(@ToolParam(description = "调用方名称") String name) {
            calls++;
            return "pong: " + name;
        }
    }

    @Test
    @DisplayName("Alibaba 离线验证：Graph 执行两轮模型、一次带参数工具并返回消息状态")
    void scriptedToolLoopReturnsState() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        PingTool tool = new PingTool();
        // 只替换模型服务；ReactAgent、参数 Schema、工具执行和状态累积均走真实框架。
        ChatModel model = prompt -> {
            AssistantMessage output = modelCalls.incrementAndGet() == 1
                    ? AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("call-ping", "function", "ping", "{\"name\":\"study\"}")))
                            .build()
                    : new AssistantMessage("连通正常");
            return new ChatResponse(List.of(new Generation(output)));
        };
        ReactAgent agent = ReactAgent.builder().name("alibaba-state-study")
                .model(model).tools(ToolCallbacks.from(tool)).build();
        var state = agent.invoke("请检查连通性").orElseThrow();
        List<?> messages = (List<?>) state.value("messages").orElseThrow();
        messages.forEach(message -> System.out.println("[Alibaba 状态消息] " + message));
        assertEquals(2, modelCalls.get());
        assertEquals(1, tool.calls);
        assertTrue(messages.stream().anyMatch(ToolResponseMessage.class::isInstance));
    }

    @Test
    @Tag("live")
    @DisplayName("Alibaba 真实模型：调用工具后读取整趟执行状态")
    void realModelReturnsState() throws Exception {
        ModelConfig config = ModelConfig.loadProperties();
        // 1.1.x 的 OpenAI 模型构造入口与 2.0.x 不同，仅在本模块使用。
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl()).build())
                .defaultOptions(OpenAiChatOptions.builder().model(config.model()).build()).build();
        ReactAgent agent = ReactAgent.builder().name("alibaba-live-study").model(model)
                .systemPrompt("请调用一次 ping 工具，再根据结果用中文简短回答。")
                .tools(ToolCallbacks.from(new PingTool())).build();
        var state = agent.invoke("我是 study，请检查连通性。").orElseThrow();
        ((List<?>) state.value("messages").orElseThrow())
                .forEach(message -> System.out.println("[Alibaba 状态消息] " + message));
    }
}
