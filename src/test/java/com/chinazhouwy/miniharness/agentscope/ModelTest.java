package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * Model（模型）这一页的演示。
 * 本类注册了一个“全局中间件”，把 5 个钩子全打出来，方便观察每次调用都发生了什么。
 */
public class ModelTest {

    private static Properties props;
    private static OpenAIChatModel model;

    /** 全局中间件：5 个钩子全实现，只打摘要。 */
    private static final MiddlewareBase GLOBAL = new MiddlewareBase() {
        private int round = 0;

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput in,
                                        Function<AgentInput, Flux<AgentEvent>> next) {
            long start = System.currentTimeMillis();
            return next.apply(in)
                    .doFirst(() -> System.out.println("① onAgent      开始 agent=" + agent.getName()))
                    .doOnComplete(() -> System.out.println("① onAgent      结束 耗时="
                            + (System.currentTimeMillis() - start) + "ms"));
        }

        @Override
        public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput in,
                                            Function<ReasoningInput, Flux<AgentEvent>> next) {
            int n = ++round;
            return next.apply(in)
                    .doFirst(() -> System.out.println("② onReasoning  第 " + n + " 轮 消息数="
                            + in.messages().size() + " 工具数=" + in.tools().size()));
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
            System.out.println("③ onSystemPrompt 长度=" + prompt.length());
            return Mono.just(prompt);
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            return next.apply(in)
                    .doFirst(() -> System.out.println("④ onModelCall  发给模型 消息数=" + in.messages().size()));
        }

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                         Function<ActingInput, Flux<AgentEvent>> next) {
            return next.apply(in)
                    .doFirst(() -> in.toolCalls().forEach(tc -> System.out.println("⑤ onActing     执行工具="
                            + tc.getName() + " 参数=" + tc.getInput())));
        }
    };

    @BeforeAll
    static void before() throws IOException {
        props = new Properties();
        try (InputStream in = ModelTest.class.getClassLoader()
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

    /** 建一个装了全局中间件的 agent。 */
    private static HarnessAgent agent(OpenAIChatModel m) {
        return HarnessAgent.builder()
                .name("model-demo").agentId("model-demo")
                .sysPrompt("you are a helper")
                .model(m)
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build();
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder()
                .userId("zhouwy")
                .sessionId("sModel-" + UUID.randomUUID())
                .build();
    }

    // ================= 1. 字符串 id（ModelRegistry） =================

    @Test
    @DisplayName("模型 1：用字符串 id 指定模型（ModelRegistry）")
    public void test02ModelRegistry() {
        // 先看看框架能不能识别 deepseek: 前缀（能不能识别 ≠ 有没有 key）
        System.out.println("canResolve(\"deepseek:deepseek-flash\") = " + ModelRegistry.canResolve("deepseek:deepseek-flash"));
        try {
            ModelRegistry.resolve("deepseek:deepseek-flash");
            System.out.println("deepseek: 解析成功");
        } catch (RuntimeException e) {
            System.out.println("deepseek: 解析失败 → " + e.getMessage());
        }
        // 把自己配好的模型注册成一个名字，然后用字符串 id 引用它
        ModelRegistry.register("my-model", model);
        System.out.println("canResolve(\"my-model\") = " + ModelRegistry.canResolve("my-model"));

        try (HarnessAgent a = HarnessAgent.builder()
                .name("model-demo").agentId("model-demo")
                .sysPrompt("you are a helper")
                .model("my-model")                       // ← 字符串 id
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {
            Msg ret = a.call(new UserMessage("用一句话介绍一下你自己"), ctx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 2. 给字符串 id 补 key 和 url =================

    @Test
    @DisplayName("模型 2：字符串 id 的 key 和 url 从哪来")
    public void test03ResolveWithContext() {
        // A. 直接 resolve：key 读环境变量 DEEPSEEK_API_KEY，url 用 Provider 写死的 https://api.deepseek.com
        //    这里没配环境变量，所以会失败
        try {
            ModelRegistry.resolve("deepseek:deepseek-flash");
        } catch (RuntimeException e) {
            System.out.println("A 失败（没配环境变量）: " + e.getMessage());
        }

        // B. 带 ModelCreationContext 传 key / url
        var m = ModelRegistry.resolve("deepseek:deepseek-flash",
                ModelCreationContext.builder()
                        .apiKey(props.getProperty("openrouter.api.key"))   // 用 properties 里的 key
                        .baseUrl("https://api.deepseek.com")               // 自定义 url
                        .build());
        System.out.println("B 成功: " + m.getModelName());

        // C. 注册工厂：让 .model("deepseek:xxx") 也能用我们自己的 key
        ModelRegistry.registerFactory("deepseek:.*", id ->
                OpenAIChatModel.builder()
                        .apiKey(props.getProperty("openrouter.api.key"))
                        .baseUrl(props.getProperty("openrouter.base.url"))
                        .modelName(id.substring("deepseek:".length()))
                        .nativeStructuredOutput(false)
                        .stream(true)
                        .build());

        try (HarnessAgent a = HarnessAgent.builder()
                .name("model-demo").agentId("model-demo")
                .sysPrompt("you are a helper")
                .model("deepseek:deepseek-flash")     // ← 字符串 id，走我们注册的工厂
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {
            Msg ret = a.call(new UserMessage("1+1 等于几？只回答数字"), ctx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 3. maxRetries 重试 + fallbackModel 备用模型 =================

    @Test
    @DisplayName("模型 3：主模型挂了 → maxRetries 重试 → fallbackModel 接管")
    public void test04RetryAndFallback() {
        // 主模型：故意配错 key，每次请求都会 401
        OpenAIChatModel broken = OpenAIChatModel.builder()
                .apiKey("sk-invalid-key-on-purpose")
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .stream(true)
                .build();

        try (HarnessAgent a = HarnessAgent.builder()
                .name("model-demo").agentId("model-demo")
                .sysPrompt("you are a helper")
                .model(broken)              // 主模型（坏的）
                .maxRetries(2)              // 失败后最多再试 2 次
                .fallbackModel(model)       // 重试用完还不行 → 切到好模型
                .failoverListener((primary, error) -> System.out.println("[failover] 从 "
                        + primary.getModelName() + " 切换到备用模型，原因: "
                        + String.valueOf(error.getMessage()).substring(0,
                                Math.min(120, String.valueOf(error.getMessage()).length()))))
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            Msg ret = a.call(new UserMessage("1+1 等于几？只回答数字"), ctx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= ModelTest 演示 =================

    @Test
    @DisplayName("全局中间件：一次调用里 5 个钩子的触发顺序")
    public void test01HookOrder() {
        try (HarnessAgent a = agent(model)) {
            Msg ret = a.call(new UserMessage("1+1 等于几？只回答数字"), ctx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 3. 清空预设提示词：看最干净的请求/返回 =================

    @Test
    @DisplayName("清空预设提示词：ReActAgent 零注入，每次请求/返回完整可见")
    public void test04CleanRequestResponse() {
        MiddlewareBase dump = new MiddlewareBase() {
            int req = 0;

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                int n = ++req;
                long start = System.currentTimeMillis();
                StringBuilder text = new StringBuilder();
                return next.apply(in)
                        .doFirst(() -> {
                            System.out.println("\n===== 第 " + n + " 次请求（消息数=" + in.messages().size()
                                    + " 工具数=" + in.tools().size() + "）=====");
                            for (Msg m : in.messages()) {
                                System.out.println("  [" + m.getRole() + "] " + m.getTextContent());
                            }
                        })
                        .doOnNext(ev -> {
                            if (ev instanceof TextBlockDeltaEvent t && t.getDelta() != null) {
                                text.append(t.getDelta());
                            }
                        })
                        .doOnComplete(() -> {
                            System.out.println("----- 返回 -----");
                            System.out.println("  " + text);
                            System.out.println("  （耗时 " + (System.currentTimeMillis() - start) + "ms）");
                        });
            }
        };

        // ReActAgent：裸引擎。系统提示词=你写的那句；不注册工具=请求里没有 tools
        try (ReActAgent agent = ReActAgent.builder()
                .name("clean")
                .sysPrompt("you are a helper")
                .model(model)
                .middlewares(List.of(dump))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sClean-" + UUID.randomUUID()).build();

            // 同一会话连问两次：第二次请求里能直接看到上一轮历史
            agent.call(List.of(new UserMessage("1+1等于几？只回答数字")), ctx).block();
            agent.call(List.of(new UserMessage("再乘以10呢？只回答数字")), ctx).block();
        }
    }
}
