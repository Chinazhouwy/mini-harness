package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** AgentScope 扩展点；与 study-spring-ai 中同题示例分别运行。 */
@org.junit.jupiter.api.Tag("live")
public class AgentScopeHookTest {
    private static final String QUESTION = "我的午餐拍照任务完成了吗？";
    private static final String SYSTEM = "你是任务助手，请查询工具后，根据工具结果回答。";
    private static Properties properties;

    // JUnit 在本类测试开始前执行一次初始化；沿用根 config 的本地配置，不把密钥写到代码或日志。
    @BeforeAll
    static void loadConfig() throws IOException {
        properties = ModelConfig.loadLocalProperties();
    }

    public static class TaskTools {
        @io.agentscope.core.tool.Tool(name = "queryLunchTask", description = "查询用户的午餐拍照任务状态")
        public String queryLunchTask() {
            System.out.println("    [Java 工具本体] 返回固定任务状态");
            return "午餐拍照任务：待完成";
        }
    }

    /**
     * 对照一趟调用、一次推理和一批工具的扩展边界。
     * <p>关键点：onReasoning/onModelCall/onActing 包裹不同阶段；next.apply 交给后续链路，Flux.defer 延迟到订阅执行。
     * <p>观察：工具请求出现后应进入 acting，再有后续模型请求；计数不是整个会话的轮数。
     */
    @Test
    @DisplayName("AgentScope：观察推理、模型请求与工具执行扩展点")
    void agentScopeHooks() {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(properties.getProperty("openrouter.api.key"))
                .baseUrl(properties.getProperty("openrouter.base.url"))
                .modelName(properties.getProperty("openrouter.model"))
                // 这里控制模型响应的流式接收；外层 agent.call 仍可聚合为一个最终 Msg，事件流用 streamEvents 观察。
                .stream(true)
                .build();

        AtomicInteger rounds = new AtomicInteger();
        MiddlewareBase middleware = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                                Function<ReasoningInput, Flux<AgentEvent>> next) {
                // defer 把阶段开始日志与 next 调用放到订阅时，避免在仅组装响应式链时就记成已执行。
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

}
