package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
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
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * Middleware 钩子演示（2.0.4 共 5 个：onAgent / onReasoning / onSystemPrompt / onModelCall / onActing）。
 * 每个测试只演示一个钩子，代码全部写在方法里，方便一行行读。
 */
public class MiddleTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = MiddleTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            props.load(in);
        }
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

    // ================= 0. Reactor 语义小实验 =================

    @Test
    @DisplayName("doFirst 只在订阅开始时执行一次，doOnNext 每个元素一次")
    public void test00DoFirst() {
        reactor.core.publisher.Flux.just("A", "B", "C")
                .doFirst(() -> System.out.println("doFirst        ← 订阅开始，只一次"))
                .doOnNext(e -> System.out.println("doOnNext " + e + "    ← 每个元素一次"))
                .doOnComplete(() -> System.out.println("doOnComplete   ← 全部结束，只一次"))
                .blockLast();
    }

    // ================= 1/5 onAgent =================

    @Test
    @DisplayName("钩子 1/5 onAgent：包住一次完整 call")
    public void test01OnAgent() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput in,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
                long start = System.currentTimeMillis();
                Msg last = in.msgs().getLast();
                return next.apply(in)
                        .doFirst(() -> System.out.println("[onAgent] 开始 agent=" + agent.getName()
                                + " 消息数=" + in.msgs().size() + " 最后一条=[" + last.getRole() + "] " + last.getTextContent()))
                        .doOnComplete(() -> System.out.println("[onAgent] 结束 耗时="
                                + (System.currentTimeMillis() - start) + "ms"));
            }
        };

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("middle-demo").agentId("middle-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(mw)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("s-" + UUID.randomUUID()).build();
            Msg ret = agent.call(new UserMessage("1+1 等于几？只回答数字"), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 2/5 onReasoning =================

    @Test
    @DisplayName("钩子 2/5 onReasoning：包住每一轮推理")
    public void test02OnReasoning() {
        MiddlewareBase mw = new MiddlewareBase() {
            int round = 0;

            @Override
            public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput in,
                                                Function<ReasoningInput, Flux<AgentEvent>> next) {
                int n = ++round;
                long start = System.currentTimeMillis();
                Msg last = in.messages().getLast();
                return next.apply(in)
                        .doFirst(() -> System.out.println("[onReasoning] 第 " + n + " 轮 消息数=" + in.messages().size() + " 最后一条=[" + last.getRole() + "] " + last.getTextContent()))
                        .doOnNext(ev -> {
                            if (ev instanceof ToolCallStartEvent t) {
                                System.out.println("      -> 模型要调工具: " + t.getToolCallName());
                            }
                        })
                        .doOnComplete(() -> System.out.println("[onReasoning] 第 " + n + " 轮结束 耗时="
                                + (System.currentTimeMillis() - start) + "ms"));
            }
        };

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("middle-demo").agentId("middle-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(mw)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("s-" + UUID.randomUUID()).build();
            Msg ret = agent.call(new UserMessage("上海天气如何？一句话回答"), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 3/5 onSystemPrompt =================

    @Test
    @DisplayName("钩子 3/5 onSystemPrompt：改写系统提示词")
    public void test04OnSystemPrompt() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
                System.out.println("[onSystemPrompt] 原始长度=" + prompt.length()
                        + " 开头=" + prompt.substring(0, Math.min(50, prompt.length())).replace("\n", " "));

                String changed = prompt + "\n\n【middleware 注入的规则】回答必须以「【已改写】」开头。";
                System.out.println("[onSystemPrompt] 改写后长度=" + changed.length());
                return Mono.just(changed);
            }
        };

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("middle-demo").agentId("middle-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(mw)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("s-" + UUID.randomUUID()).build();
            Msg ret = agent.call(new UserMessage("用一句话介绍你自己"), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 多 Agent：父 agent 派子 agent =================

    @Test
    @DisplayName("多 Agent：父 agent 派子 agent 干活，middleware 在两边都触发")
    public void test03MultiAgent() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput in,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
                long start = System.currentTimeMillis();
                Msg last = in.msgs().getLast();
                return next.apply(in)
                        .doFirst(() -> System.out.println("[onAgent] 开始 agent=" + agent.getName()
                                + " session=" + ctx.getSessionId() + " 最后一条=[" + last.getRole() + "] " + last.getTextContent()))
                        .doOnComplete(() -> System.out.println("[onAgent] 结束 agent=" + agent.getName()
                                + " 耗时=" + (System.currentTimeMillis() - start) + "ms"));
            }
        };

        // 注意：不 disableSubagents()，父 agent 才有 agent_spawn 工具
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("parent").agentId("parent")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(mw)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("s-" + UUID.randomUUID()).build();
            Msg ret = agent.call(new UserMessage("请用子 agent 去查一下上海今天天气，然后一句话总结给我。"), ctx).block();
            System.out.println("最终回答: " + ret.getTextContent());
        }
    }
}
