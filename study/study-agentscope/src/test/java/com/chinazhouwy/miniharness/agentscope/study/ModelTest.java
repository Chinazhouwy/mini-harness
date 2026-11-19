package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * Model（模型）这一页的演示。
 * 本类注册了一个“全局中间件”，把 5 个钩子全打出来，方便观察每次调用都发生了什么。
 */
@org.junit.jupiter.api.Tag("live")
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

    // JUnit 在本类测试开始前执行一次初始化；沿用根 config 的本地配置，不把密钥写到代码或日志。
    @BeforeAll
    static void before() throws IOException {
        props = ModelConfig.loadLocalProperties();
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

    /** 建一个装了全局中间件的 agent。 */
    private static HarnessAgent agent(OpenAIChatModel m) {
        return HarnessAgent.builder()
                .name("model-demo").agentId("model-demo")
                .sysPrompt("you are a helper")
                .model(m)
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                // 文件 Store 按调用的 userId/sessionId 保存和读取状态；新 Java 对象不等于新会话。
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                // 仅关闭这两类预置能力以减少干扰；聊天历史、文件工具等其他能力仍看各自配置。
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

    /**
     * 观察字符串模型标识的解析与显式注册。
     * <p>关键点：canResolve 说明有匹配的解析器，不等于凭据齐全；register 把已经配置好的模型绑定到名字。
     * <p>观察：先看前缀解析是否成功，再看 my-model 的实际调用，区分模型 ID 与服务参数。
     */
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

    /**
     * 比较模型标识如何获得密钥、地址与实例。
     * <p>关键点：默认 Provider、带属性的 resolve 和 registerFactory 是不同配置入口；工厂按匹配的 ID 构造模型。
     * <p>观察：核对实际选择的模型与请求结果；注册工厂影响进程级 Registry，建议单独运行。
     */
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

    /**
     * 观察主模型失败后的重试与备用模型切换。
     * <p>关键点：主模型故意使用无效凭据；maxRetries/fallbackModel 控制失败路径，listener 观察切换。
     * <p>观察：401 是否可重试取决于当前策略，次数以事件/请求日志为准；这是模型失败，不是工具失败。
     */
    @Test
    @DisplayName("模型 3：主模型挂了 → maxRetries 重试 → fallbackModel 接管")
    public void test04RetryAndFallback() {
        // 主模型故意使用无效 key；错误码和是否重试由当前服务与框架策略决定。
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

    /**
     * 观察全局 Middleware 中不同钩子的作用顺序。
     * <p>关键点：同一调用经过 Agent、提示词、推理、模型或工具边界；无工具分支不必出现 acting。
     * <p>观察：用编号日志识别范围与先后，不把缺少工具事件视为框架漏执行。
     */
    @Test
    @DisplayName("全局中间件：一次调用里 5 个钩子的触发顺序")
    public void test01HookOrder() {
        try (HarnessAgent a = agent(model)) {
            Msg ret = a.call(new UserMessage("1+1 等于几？只回答数字"), ctx()).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 3. 清空预设提示词：看最干净的请求/返回 =================

    /**
     * 用直接构造的 ReActAgent 观察较少附加能力的问答。
     * <p>关键点：不注册工具，Middleware 汇总模型文本；同一会话的第二次调用应带入上次问答。
     * <p>观察：请求层日志能看到历史，但不是原始 HTTP 字节；流式增量到完成时才汇总展示。
     */
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

    // ================= 4. 同样的干净 dump，但带上工具调用 =================

    /** 最小工具集：一个查净值工具，模型不调它就答不了问题。 */
    public static class NavTools {

        @Tool(name = "query_fund_nav", description = "查询基金最新净值（估值）。用户问某个基金的净值时必须调用此工具。")
        public String queryFundNav(
                @ToolParam(name = "fund_code", description = "基金六位代码，例如 005827") String fundCode) {
            return "{\"code\":\"" + fundCode + "\",\"name\":\"易方达蓝筹精选\",\"nav\":2.3456,\"day_change\":\"+1.23%\"}";
        }
    }

    /**
     * 观察一个工具如何改变模型交互消息。
     * <p>关键点：Toolkit 暴露固定净值查询工具，ModelCallInput 中分别读取 ToolUseBlock 与 ToolResultBlock。
     * <p>观察：第二轮模型输入应含工具结果；净值是示例固定数据，不是实时金融数据。
     */
    @Test
    @DisplayName("干净 dump + 工具：模型要调工具 → 工具结果回填 → 第二次请求才出最终回答")
    public void test05CleanRequestResponseWithTool() {
        MiddlewareBase dump = new MiddlewareBase() {
            int req = 0;

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                int n = ++req;
                long start = System.currentTimeMillis();
                StringBuilder text = new StringBuilder();
                String[] toolName = {null};
                StringBuilder toolArgs = new StringBuilder();
                return next.apply(in)
                        .doFirst(() -> {
                            System.out.println("\n===== 第 " + n + " 次请求（消息数=" + in.messages().size()
                                    + " 工具数=" + in.tools().size() + "）=====");
                            for (Msg m : in.messages()) {
                                String t = m.getTextContent();
                                if (t != null && !t.isBlank()) {
                                    System.out.println("  [" + m.getRole() + "] " + t);
                                }
                                for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                                    System.out.println("  [" + m.getRole() + "] →要调工具 " + u.getName()
                                            + " 参数=" + u.getInput());
                                }
                                for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                                    StringBuilder out = new StringBuilder();
                                    for (ContentBlock b : r.getOutput()) {
                                        if (b instanceof TextBlock tb) out.append(tb.getText());
                                    }
                                    System.out.println("  [" + m.getRole() + "] →工具结果 " + r.getName()
                                            + " = " + out);
                                }
                            }
                        })
                        .doOnNext(ev -> {
                            if (ev instanceof TextBlockDeltaEvent t && t.getDelta() != null) {
                                text.append(t.getDelta());
                            } else if (ev instanceof ToolCallStartEvent s) {
                                toolName[0] = s.getToolCallName();
                            } else if (ev instanceof ToolCallDeltaEvent d && d.getDelta() != null) {
                                toolArgs.append(d.getDelta());
                            } else if (ev instanceof ToolCallEndEvent) {
                                System.out.println("  （模型要调工具: " + toolName[0]
                                        + " 参数=" + toolArgs + "）");
                            }
                        })
                        .doOnComplete(() -> {
                            System.out.println("----- 返回 -----");
                            if (text.length() > 0) {
                                System.out.println("  " + text);
                            }
                            System.out.println("  （耗时 " + (System.currentTimeMillis() - start) + "ms）");
                        });
            }

            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                             Function<ActingInput, Flux<AgentEvent>> next) {
                return next.apply(in)
                        .doFirst(() -> in.toolCalls().forEach(tc ->
                                System.out.println("  ⚙ 工具执行: " + tc.getName() + " 参数=" + tc.getInput())));
            }
        };

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new NavTools());

        // ReActAgent + 1 个工具：第 1 次请求带 tools，模型返回的是工具调用而不是文字
        try (ReActAgent agent = ReActAgent.builder()
                .name("clean-tool")
                .sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(dump))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sCleanTool-" + UUID.randomUUID()).build();

            agent.call(List.of(new UserMessage("帮我查一下 005827 这只基金今天的净值")), ctx).block();
        }
    }
}
