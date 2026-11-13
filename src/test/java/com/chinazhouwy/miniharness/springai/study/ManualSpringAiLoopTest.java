package com.chinazhouwy.miniharness.springai.study;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用 Spring AI 的模型与工具接口，自己组织一次最小工具调用循环。 */
public class ManualSpringAiLoopTest {

    static class TaskTools {
        private final AtomicInteger executions = new AtomicInteger();

        @Tool(name = "queryLunchTask", description = "查询午餐拍照任务的状态")
        public String queryLunchTask() {
            executions.incrementAndGet();
            System.out.println("  [Java 工具] 查询午餐任务");
            return "待完成";
        }
    }

    /** 返回最终回答；本例的 maxRounds 是本次最多允许调用模型的次数。 */
    static String runLoop(ChatModel model, TaskTools taskTools, int maxRounds) {
        ToolCallback[] tools = ToolCallbacks.from(taskTools);
        ToolCallingManager toolManager = ToolCallingManager.builder().build();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .build();

        List<Message> messages = List.of(
                new SystemMessage("你是任务助手，请根据工具结果回答。"),
                new UserMessage("我的午餐拍照任务完成了吗？"));

        for (int round = 1; round <= maxRounds; round++) {
            // 1. 把当前消息与可用工具发给模型。
            Prompt prompt = new Prompt(messages, options);
            System.out.println("第 " + round + " 轮：调用模型，消息数=" + messages.size());
            ChatResponse response = model.call(prompt);

            // 2. 模型没有请求工具：拿到最终回答，结束循环。
            if (!response.hasToolCalls()) {
                String answer = response.getResult().getOutput().getText();
                System.out.println("最终回答：" + answer);
                return answer;
            }

            // 已经没有下一次模型调用的预算，避免执行后无法继续处理结果。
            if (round == maxRounds) {
                throw new IllegalStateException("已达到模型调用次数上限：" + maxRounds);
            }

            // 3. 模型请求了工具：Spring AI 根据请求找到并执行 Java 方法。
            System.out.println("  模型请求调用工具，开始执行");
            ToolExecutionResult result = toolManager.executeToolCalls(prompt, response);
            if (result.returnDirect()) {
                throw new IllegalStateException("此入门示例不处理 returnDirect 工具");
            }

            // 4. 保存原消息、模型工具请求和工具结果，带入下一轮。
            messages = result.conversationHistory();
            System.out.println("  工具结果已加入消息，准备下一轮");
        }

        throw new IllegalStateException("模型调用次数上限必须大于 0");
    }

    @Test
    void scriptedModelShowsToolLoop() {
        TaskTools tools = new TaskTools();
        AtomicInteger modelCalls = new AtomicInteger();

        // 固定模型响应：第一轮请求工具，第二轮确认收到工具结果后回答。
        // 它只替代模型服务；循环、工具注册和工具执行均使用真实 Spring AI API。
        ChatModel scriptedModel = prompt -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "call-1", "function", "queryLunchTask", "{}");
                AssistantMessage message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(message)));
            }

            assertTrue(prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance),
                    "第二轮应该收到工具结果");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("午餐拍照任务还未完成。"))));
        };

        String answer = runLoop(scriptedModel, tools, 3);
        assertEquals("午餐拍照任务还未完成。", answer);
        assertEquals(2, modelCalls.get());
        assertEquals(1, tools.executions.get());
    }
}
