package com.chinazhouwy.miniharness.springai.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Spring AI 扩展点；AgentScope 半边已移至 study-agentscope 的 AgentScopeHookTest。 */
@org.junit.jupiter.api.Tag("live")
public class AgentHookComparisonTest {

    private static final String QUESTION = "我的午餐拍照任务完成了吗？";
    private static final String SYSTEM = "你是任务助手，请查询工具后，根据工具结果回答。";
    private static Properties properties;

    @BeforeAll
    static void loadConfig() throws IOException {
        properties = ModelConfig.loadLocalProperties();
    }

    /** 与 AgentScope 示例使用相同问题与固定结果，各自注册自己的工具。 */
    public static class TaskTools {
        @org.springframework.ai.tool.annotation.Tool(name = "queryLunchTask", description = "查询用户的午餐拍照任务状态")
        public String queryLunchTask() {
            System.out.println("    [Java 工具本体] 返回固定任务状态");
            return "午餐拍照任务：待完成";
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Spring AI：观察工具循环、模型调用与工具回调扩展点")
    void springAiHooks() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(properties.getProperty("openrouter.api.key"))
                        .baseUrl(properties.getProperty("openrouter.base.url"))
                        .model(properties.getProperty("openrouter.model"))
                        .build())
                .build();
        AtomicInteger rounds = new AtomicInteger();

        // Spring AI 的每轮调用前后钩子：类比 reasoning 的部分边界。
        ToolCallingAdvisor loop = new ToolCallingAdvisor(
                ToolCallingManager.builder().build(),
                response -> response != null && response.hasToolCalls(),
                ToolCallingAdvisor.DEFAULT_ORDER,
                true) {
            @Override
            protected ChatClientRequest doBeforeCall(
                    ChatClientRequest request, CallAdvisorChain chain) {
                System.out.println("[ToolCallingAdvisor每轮开始] 第 " + rounds.incrementAndGet() + " 轮");
                return super.doBeforeCall(request, chain);
            }

            @Override
            protected ChatClientResponse doAfterCall(
                    ChatClientResponse response, CallAdvisorChain chain) {
                System.out.println("[ToolCallingAdvisor每轮模型响应] 包含工具调用："
                        + (response.chatResponse() != null
                        && response.chatResponse().hasToolCalls()));
                return super.doAfterCall(response, chain);
            }
        };

        // 位于工具循环内部、模型调用之前的 Advisor。
        CallAdvisor modelTrace = new CallAdvisor() {
            @Override
            public String getName() {
                return "study-model-trace";
            }

            @Override
            public int getOrder() {
                return 0;
            }

            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                System.out.println("  [CallAdvisor模型调用前] 消息数：" + request.prompt().getInstructions().size());
                try {
                    ChatClientResponse response = chain.nextCall(request);
                    System.out.println("  [CallAdvisor模型调用后] 已收到响应");
                    return response;
                } catch (RuntimeException error) {
                    System.out.println("  [CallAdvisor模型调用失败] " + error.getClass().getSimpleName());
                    throw error;
                }
            }
        };

        ToolCallback original = ToolCallbacks.from(new TaskTools())[0];

        ToolCallback tracedTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return original.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return original.getToolMetadata();
            }

            @Override
            public String call(String input) {
                System.out.println("  [ToolCallback工具执行前] " + getToolDefinition().name());
                try {
                    return original.call(input);
                } finally {
                    System.out.println("  [ToolCallback工具执行结束] " + getToolDefinition().name());
                }
            }

            @Override
            public String call(String input, ToolContext context) {
                System.out.println("  [工具执行前] " + getToolDefinition().name());
                try {
                    return original.call(input, context);
                } finally {
                    System.out.println("  [工具执行结束] " + getToolDefinition().name());
                }
            }
        };

        String answer = ChatClient.create(model).prompt()
                .system(SYSTEM)
                .user(QUESTION)
                .tools(tracedTool)
                .advisors(loop, modelTrace)
                .call()
                .content();
        System.out.println("最终回答：" + answer);
    }

}
