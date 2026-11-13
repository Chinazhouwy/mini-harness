package com.chinazhouwy.miniharness.springai.study;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
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
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 同一个模型配置、问题与工具，对照两个框架的阶段扩展点。 */
public class AgentHookComparisonTest {

    private static final String QUESTION = "我的午餐拍照任务完成了吗？";
    private static final String SYSTEM = "你是任务助手，请查询工具后，根据工具结果回答。";
    private static Properties properties;

    @BeforeAll
    static void loadConfig() throws IOException {
        properties = new Properties();
        try (InputStream input = AgentHookComparisonTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            if (input == null) {
                throw new IllegalStateException("找不到 openrouter.local.properties，请沿用现有学习测试的本地配置");
            }
            properties.load(input);
        }
    }

    /** 同一个 Java 方法同时声明为两个框架的工具；不会修改任务状态。 */
    static class TaskTools {
        @io.agentscope.core.tool.Tool(name = "queryLunchTask", description = "查询用户的午餐拍照任务状态")
        @org.springframework.ai.tool.annotation.Tool(name = "queryLunchTask", description = "查询用户的午餐拍照任务状态")
        public String queryLunchTask() {
            System.out.println("    [Java 工具本体] 返回固定任务状态");
            return "午餐拍照任务：待完成";
        }
    }

    @Test
    void agentScopeHooks() {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(properties.getProperty("openrouter.api.key"))
                .baseUrl(properties.getProperty("openrouter.base.url"))
                .modelName(properties.getProperty("openrouter.model"))
                .stream(true)
                .build();

        AtomicInteger rounds = new AtomicInteger();
        MiddlewareBase middleware = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                                Function<ReasoningInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    System.out.println("[AgentScope onReasoning 前] 第 " + rounds.incrementAndGet() + " 轮");
                    return next.apply(input).doOnComplete(() -> System.out.println("[AgentScope onReasoning 后]"));
                });
            }

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    System.out.println("  [AgentScope onModelCall 前] 消息数：" + input.messages().size());
                    return next.apply(input).doOnComplete(() -> System.out.println("  [AgentScope onModelCall 后]"));
                });
            }

            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                             Function<ActingInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    System.out.println("  [AgentScope onActing 前] 工具调用数：" + input.toolCalls().size());
                    return next.apply(input).doOnComplete(() -> System.out.println("  [AgentScope onActing 后]"));
                });
            }
        };

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TaskTools());
        try (ReActAgent agent = ReActAgent.builder()
                .name("hook-comparison")
                .sysPrompt(SYSTEM)
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(middleware))
                .build()) {
            var answer = agent.call(QUESTION).block();
            System.out.println("[AgentScope 最终回答] " + answer.getTextContent());
        }
    }

    @Test
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
