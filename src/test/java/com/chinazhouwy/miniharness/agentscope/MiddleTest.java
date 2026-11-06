package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Middleware 六个钩子，一个钩子一个 @Test。
 * 1. onAgent（本文件 test01OnAgent）
 * 2. onAgentStateReady
 * 3. onReasoning
 * 4. onSystemPrompt
 * 5. onModelCall
 * 6. onActing
 */
public class MiddleTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = loadLocalProperties();
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

    /** 装一个 middleware 建个 agent（每个用例一个新会话，避免历史互相影响）。 */
    private static HarnessAgent newAgent(MiddlewareBase mw) {
        return HarnessAgent.builder()
                .name("middle-demo")
                .agentId("middle-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(mw)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools()
                .disableSubagents()
                .build();
    }

    private static RuntimeContext newCtx() {
        return RuntimeContext.builder()
                .userId("zhouwy")
                .sessionId("sMiddle-" + UUID.randomUUID())
                .build();
    }

    // ================= 1/6 onAgent =================

    @Test
    @DisplayName("钩子 1/6 onAgent：包住一次完整 call")
    public void test01OnAgent() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput in,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
                long start = System.currentTimeMillis();
                return next.apply(in)
                        .doFirst(() -> System.out.println("[onAgent] 开始: agent=" + agent.getName() + " 输入消息数=" + in.msgs().size() + " 第一条=" + in.msgs().get(0).getTextContent()))
                        .doOnComplete(() -> System.out.println("[onAgent] 结束: 耗时=" + (System.currentTimeMillis() - start) + "ms"))
                        .doOnError(e -> System.out.println("[onAgent] 出错: " + e.getMessage()));
            }
        };

        try (HarnessAgent agent = newAgent(mw)) {
            Msg ret = agent.call(new UserMessage("1+1 等于几？只回答数字"), newCtx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 2/5 onReasoning =================

    @Test
    @DisplayName("钩子 2/5 onReasoning：包住每一轮推理")
    public void test03OnReasoning() {
        AtomicInteger round = new AtomicInteger();

        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx,
                                                ReasoningInput in,
                                                Function<ReasoningInput, Flux<AgentEvent>> next) {
                int n = round.incrementAndGet();
                System.out.println("[onReasoning] 第 " + n + " 轮开始: 消息数=" + in.messages().size()
                        + " 可用工具数=" + in.tools().size() + "可用工具: " + in.tools().stream().map(tool -> tool.getName()).collect(Collectors.joining(","))
                        + " toolChoice=" + (in.options() == null ? null : in.options().getToolChoice()));
                long start = System.currentTimeMillis();
                return next.apply(in)
                        .doFirst(() -> System.out.println("[onReasoning] 开始: agent=" + agent.getName() + " 输入消息数=" + in.messages().size() + " 第一条=" + in.messages().get(0).getTextContent().replace("\n","")))
                        .doOnComplete(() -> System.out.println("[onReasoning] 第 " + n + " 轮结束: 耗时=" + (System.currentTimeMillis() - start) + "ms"))
                        .doOnError(e -> System.out.println("[onReasoning] 出错: " + e.getMessage()));
            }
        };

        try (HarnessAgent agent = newAgent(mw)) {
            Msg ret = agent.call(new UserMessage("上海天气如何？一句话回答"), newCtx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 多 Agent：父 agent 委派子 agent =================

    @Test
    @DisplayName("多 Agent：父 agent 派子 agent 干活，middleware 在两边都触发")
    public void test02MultiAgent() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput in,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
                long start = System.currentTimeMillis();
                return next.apply(in)
                        .doFirst(() -> System.out.println("["+agent.getName()+"] 开始:"+"输入消息数=" + in.msgs().size() + " 第一条=" + in.msgs().get(0).getTextContent()))
                        .doOnComplete(() -> System.out.println("["+agent.getName()+"] 结束: 耗时=" + (System.currentTimeMillis() - start) + "ms"))
                        .doOnError(e -> System.out.println("["+agent.getName()+"] 出错: " + e.getMessage()));
            }
        };

        // 注意：这次不 disableSubagents()，父 agent 才有 agent_spawn 能力
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("parent")
                .agentId("parent")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(mw)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools()
                .build()) {

            Msg ret = agent.call(new UserMessage(
                    "请用子 agent 去查一下上海今天天气，然后一句话总结给我。"), newCtx()).block();
            System.out.println("父 agent 最终回答: " + ret.getTextContent());
        }
    }

    private static Properties loadLocalProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream in = MiddleTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            if (in == null) {
                throw new IllegalStateException("找不到 openrouter.local.properties");
            }
            props.load(in);
        }
        return props;
    }
}
