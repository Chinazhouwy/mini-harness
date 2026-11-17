package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
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
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * Middleware 钩子演示（2.0.4 共 5 个：onAgent / onReasoning / onSystemPrompt / onModelCall / onActing）。
 * 每个测试只演示一个钩子，代码全部写在方法里，方便一行行读。
 */
@org.junit.jupiter.api.Tag("live")
public class MiddleTest {

    private static OpenAIChatModel model;

    // JUnit 在本类测试开始前执行一次初始化；沿用根 config 的本地配置，不把密钥写到代码或日志。
    @BeforeAll
    static void before() throws IOException {
        Properties props = ModelConfig.loadLocalProperties();
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                // false 选择框架的非原生结构化输出路径；普通问答仍正常使用该模型配置。
                .nativeStructuredOutput(false)
                // 这里控制模型响应的流式接收；外层 agent.call 仍可聚合为一个最终 Msg，事件流用 streamEvents 观察。
                .stream(true)
                .build();
    }

    // ================= 0. Reactor 语义小实验 =================

    /**
     * 用三元素 Flux 区分订阅与逐元素事件。
     * <p>关键点：doFirst 按订阅触发，doOnNext 按元素触发，doOnComplete 仅在正常完成触发。
     * <p>观察：一次订阅看到一次开始、三次元素和一次完成；本方法不请求模型。
     */
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

    /**
     * 观察 Middleware 包住一趟 Agent 调用。
     * <p>关键点：onAgent 作用于完整调用，next.apply 继续后续流程；内部可能包含多次模型与工具步骤。
     * <p>观察：看 agent 名、输入与总耗时；doOnComplete 不会覆盖错误和取消的所有终止路径。
     */
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
                // 文件 Store 按调用的 userId/sessionId 保存和读取状态；新 Java 对象不等于新会话。
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                // 仅关闭这两类预置能力以减少干扰；聊天历史、文件工具等其他能力仍看各自配置。
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("s-" + UUID.randomUUID()).build();
            Msg ret = agent.call(new UserMessage("1+1 等于几？只回答数字"), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 2/5 onReasoning =================

    /**
     * 观察每轮推理阶段的输入与工具意图。
     * <p>关键点：onReasoning 针对一次推理阶段，不是整个用户会话；在事件中观察模型是否请求工具。
     * <p>观察：比较轮数与消息数；工具循环继续时会再次进入推理。
     */
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

    /**
     * 在系统提示词准备阶段注入一条规则。
     * <p>关键点：onSystemPrompt 返回修改后的字符串，框架再将其用于模型请求；它不是直接修改磁盘文件。
     * <p>观察：核对提示词长度与新增前缀规则；模型输出遵从程度仍需观察。
     */
    @Test
    @DisplayName("钩子 3/5 onSystemPrompt：改写系统提示词")
    public void test04OnSystemPrompt() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
                System.out.println("[onSystemPrompt] 原始长度=" + prompt.length()
                        + " 开头=" + prompt.replace("\n", " ").substring(0, Math.min(100, prompt.length())));
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

    // ================= 4/5 onModelCall =================

    /**
     * 在模型适配层之前查看实际模型输入。
     * <p>关键点：ModelCallInput 提供消息、工具定义和选项；next.apply 触发后续模型处理链。
     * <p>观察：比较请求次数、工具数和 toolChoice；此层仍不是原始网络报文。
     */
    @Test
    @DisplayName("钩子 4/5 onModelCall：真正发给模型的那一层")
    public void test05OnModelCall() {
        MiddlewareBase mw = new MiddlewareBase() {
            int call = 0;

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                int n = ++call;
                long start = System.currentTimeMillis();
                Msg first = in.messages().getFirst();
                return next.apply(in)
                        .doFirst(() -> System.out.println("[onModelCall] 第 " + n + " 次请求: 消息数="
                                + in.messages().size() + " 工具数=" + in.tools().size()
                                + " toolChoice=" + (in.options() == null ? null : in.options().getToolChoice())
                                + "\n      第一条=[" + first.getRole() + "] "
                                + first.getTextContent().substring(0, Math.min(40, first.getTextContent().length()))
                                        .replace("\n", " ")))
                        .doOnComplete(() -> System.out.println("[onModelCall] 第 " + n + " 次请求结束 耗时="
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

    // ================= 5/5 onActing =================

    /**
     * 观察一批工具从请求到执行完成的过程。
     * <p>关键点：ActingInput 含待执行工具；ToolResultEndEvent 是工具完成事件，和模型工具参数结束不同。
     * <p>观察：按名称、参数及结果状态对应；看到工具请求不代表工具已成功执行。
     */
    @Test
    @DisplayName("钩子 5/5 onActing：工具执行前后")
    public void test06OnActing() {
        MiddlewareBase mw = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                             Function<ActingInput, Flux<AgentEvent>> next) {
                long start = System.currentTimeMillis();
                return next.apply(in)
                        .doFirst(() -> {
                            // 这里直接拿得到要执行的工具名和参数
                            in.toolCalls().forEach(tc -> System.out.println("[onActing] 要执行工具: "
                                    + tc.getName() + " 参数=" + tc.getInput()));
                        })
                        .doOnNext(ev -> {
                            if (ev instanceof ToolResultEndEvent e) {
                                System.out.println("[onActing] 工具执行结束: " + e.getToolCallName()
                                        + " 状态=" + e.getState());
                            }
                        })
                        .doOnComplete(() -> System.out.println("[onActing] 本批工具耗时="
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

    // ================= 多 Agent：父 agent 派子 agent =================

    /**
     * 观察父 Agent 委派时不同实例的 Middleware 日志。
     * <p>关键点：子 Agent 会有自己的 agent/session 标识；只有模型实际发起委派时才会产生子调用。
     * <p>观察：按 agent 名与会话标识分组，不能把父工具的多个事件误当成多个 Agent。
     */
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
