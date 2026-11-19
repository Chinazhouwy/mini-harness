package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;


import java.util.List;
import java.util.function.Function;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;


/** AgentScope 构建参数与事件学习入口。共享 Builder 和固定会话会累积配置或历史，建议逐方法运行；问题中的天气只用于观察调用，不承诺实时数据。 */
@org.junit.jupiter.api.Tag("live")
public class BuilderTest {

        public static HarnessAgent.Builder agentBuilder;

        // JUnit 在本类测试开始前执行一次初始化；沿用根 config 的本地配置，不把密钥写到代码或日志。
        @BeforeAll
       public  static void before() throws IOException {
          Properties props = loadLocalProperties();
          String apiKey = props.getProperty("openrouter.api.key");
          String baseUrl = props.getProperty("openrouter.base.url", "https://openrouter.ai/api/v1");
          String modelName = props.getProperty("openrouter.model", "stealth/crouton-alpha");
          if (apiKey == null || apiKey.isBlank()) {
              throw new IllegalStateException(
                      "openrouter.api.key 未设置：请在 config/openrouter.local.properties 里填写");
          }
          OpenAIChatModel model = OpenAIChatModel.builder()
                  .apiKey(apiKey)
                  .modelName(modelName)              // OpenRouter 上的模型全名
                  .baseUrl(baseUrl)          // 自定义 url
                  .nativeStructuredOutput(true) // 尝试提供商原生结构化输出；是否可用取决于当前模型服务。
                  // 这里控制模型响应的流式接收；外层 agent.call 仍可聚合为一个最终 Msg，事件流用 streamEvents 观察。
                  .stream(true)
                  .build();

            agentBuilder =  HarnessAgent.builder()
                  .name("first-name")
                  .agentId("first-agentId")
                  .sysPrompt("you are a helper")
                  .model(model)
                  .workspace(Path.of("./data/workspace"))
                  // 文件 Store 按调用的 userId/sessionId 保存和读取状态；新 Java 对象不等于新会话。
                  .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")));
       }


       /**
        * 从最小 call 认识返回的 Msg。
        * <p>关键点：AgentScope call 返回 Mono，block 订阅并等待；Msg 包含块列表、文字提取结果与使用量元数据。
        * <p>观察：getTextContent 只便于读文字，完整工具/思考等信息应从对应内容块或事件查看。
        */
       @Test
       @DisplayName("call 基本问答：getTextContent / getContent / ChatUsage / getFirstContentBlock")
       public void test01(){
          Mono<Msg> res = agentBuilder.build().call("我是上海，今日天气");
           Msg ret = res.block();
           System.out.println(ret.getTextContent());
           System.out.println(ret.getContent());
           System.out.println(ret.getChatUsage().getCacheCreationTokens());
           System.out.println(ret.getFirstContentBlock());
       }

        /**
         * 观察 RuntimeContext 中固定字段与自由元数据的区别。
         * <p>关键点：userId/sessionId 参与会话定位；put 写入应用元数据，不会自动把地点追加到提示词。
         * <p>观察：本例仅打印模型回答；若要证明地点被使用，需在工具或 Middleware 中显式读取并注入。
         */
        @Test
        @DisplayName("RuntimeContext 携带 userId/sessionId/put 传入调用")
        public void test02() throws IOException {
            RuntimeContext ctx = RuntimeContext.builder().userId("userId").sessionId("sessionId").put("地点", "上海").build();
            Mono<Msg> res = agentBuilder.build().call("今日天气", ctx);
            Msg ret = res.block();
            System.out.println(ret.getTextContent());
            System.out.println(ret.getContent());
        }

        /**
         * 演示 Msg = 块列表：真实调用后逐块打印类型与内容。
         *
         * <p>关键点：TextBlock、ThinkingBlock、ToolUseBlock 含义不同；getTextContent 不能替代按类型读取。
         * <p>观察：看每块类型与文字拼接结果；实际出现哪些块由提供商与框架处理路径决定。
         */
        @Test
        @DisplayName("Msg 是块列表：逐块打印类型与内容")
        public void test03Blocks() {
            Msg ret = agentBuilder.build()
                    .call("上海今天天气怎么样？用一句话回答。").block();
            System.out.println("块数量: " + ret.getContent().size());
            for (ContentBlock block : ret.getContent()) {
                String desc;
                if (block instanceof io.agentscope.core.message.TextBlock t) {
                    desc = "text: " + t.getText();
                } else if (block instanceof io.agentscope.core.message.ThinkingBlock th) {
                    desc = "thinking: " + th.getThinking();
                } else if (block instanceof io.agentscope.core.message.ToolUseBlock tu) {
                    desc = "tool: " + tu.getName() + " input=" + tu.getInput();
                } else {
                    desc = String.valueOf(block);
                }
                System.out.println(block.getClass().getSimpleName() + " -> " + desc);
            }
            System.out.println("拼接的文字: " + ret.getTextContent());
        }

        /** 演示 RuntimeContext.put 的真实用法：put 是元数据袋，工具方法参数注入来读。 */
        public static class EchoTools {
            @Tool(name = "hhatest", description = "返回这次调用的信息")
            // RuntimeContext 参数由框架注入；模型只提供工具定义中的业务参数，不能自行伪造这个 Java 上下文对象。
            public String whoami(RuntimeContext ctx) {
                return "userId=" + ctx.getUserId()
                        + ", sessionId=" + ctx.getSessionId()
                        + ", request_id=" + ctx.get("request_id");
            }
        }

        /**
         * 把请求级元数据传给 Java 工具。
         * <p>关键点：RuntimeContext 类型参数由框架注入，不是模型生成的 JSON 参数；工具显式读取 request_id。
         * <p>观察：核对工具返回的 userId/sessionId/request_id，模型只有消费工具结果后才能看到这些信息。
         */
        @Test
        @DisplayName("RuntimeContext.put 是元数据袋，工具方法参数注入来读")
        public void test04RuntimeContextPut() {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new EchoTools());
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy")
                    .sessionId("s1")
                    // put 保存调用元数据，只有工具/Middleware 显式读取后才产生作用；不会自动进入模型提示词或日志 MDC。
                    .put("request_id", "req-abc-123")
                    .build();
            try (HarnessAgent agent = agentBuilder.toolkit(toolkit).build()) {
                Msg ret = agent.call(
                        new io.agentscope.core.message.UserMessage("为了回答我，必须先调用 hhatest 工具，再用工具的结果回答我。不要回复无关的内容"),
                        ctx).block();
                System.out.println("最终回复: " + ret.getTextContent());
            }
        }

    /**
     * 验证同一 Agent 可按 sessionId 区分历史。
     * <p>关键点：A 的输入写入 A 的状态槽；B 复用 Agent 对象但使用不同会话标识。
     * <p>观察：观察 B 的输入历史是否含 A 的暗号；持久目录已有旧会话时也会影响实验。
     */
    @Test
    @DisplayName("多会话隔离：不同 sessionId 互不串扰")
    public void test05Sessions() {
        RuntimeContext ctxA = RuntimeContext.builder().userId("zhouwy").sessionId("sA").build();
        RuntimeContext ctxB = RuntimeContext.builder().userId("zhouwy").sessionId("sB").build();

        try (HarnessAgent a = agentBuilder.build()) {
            a.call(new UserMessage("只在会话 A 里说的秘密是：暗号是蓝苹果。"), ctxA).block();
            Msg ret = a.call(new UserMessage("会话 A 里有没有提到什么暗号？如果没有就说不知道。"), ctxB).block();
            System.out.println("会话 B 回复: " + ret.getTextContent());
        }
    }



    /**
     * 演示字段名错位：把 location/temperature 的含义写反（描述错位）。
     * 模型按描述填，但取的键还是类字段名——结果会是名字和内容错位。
     */
    record SwappedWeather(
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("温度，例如 18°C") String location,
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("地点，文昌门地址") String temperature,
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("天气状况，如：多云") String condition) {}

    /**
     * 观察结构化字段名与描述冲突的影响。
     * <p>关键点：注解描述用于生成 Schema，而 Java 取值仍按 location/temperature 字段；本例故意把描述写反。
     * <p>观察：比较键名与内容是否错位；这是模型行为实验，不保证每次产生相同错误。
     */
    @Test
    @DisplayName("结构化输出演示：字段名错位会让内容跟着填错")
    public void test07StructuredOutputSwappedNames() {
        try (HarnessAgent agent = agentBuilder.build()) {
            Msg ret = agent.call(
                    List.of(new UserMessage("上海天气如何？")),
                    SwappedWeather.class,
                    RuntimeContext.builder().userId("zhouwy").sessionId("sStructuredSwapped").build()
            ).block();

            SwappedWeather data = ret.getStructuredData(SwappedWeather.class);
            System.out.println("location(字段名 location, 描述却是温度): " + data.location());
            System.out.println("temperature(字段名 temperature, 描述却是地点): " + data.temperature());
            System.out.println("condition: " + data.condition());
        }
    }


    record WeatherResponse( @com.fasterxml.jackson.annotation.JsonPropertyDescription("地点，文昌门地址") String location,
                            @com.fasterxml.jackson.annotation.JsonPropertyDescription("温度，例如 18°C") String temperature,
                            @com.fasterxml.jackson.annotation.JsonPropertyDescription("天气状况，如：多云")String condition) {}


//    @Test
    /**
     * 重复观察目标类型的结构化输出。
     * <p>关键点：call(msgs, WeatherResponse.class, ctx) 返回带结构化数据的 Msg；getStructuredData 再按类型提取。
     * <p>观察：这是 RepeatedTest，共运行五次且复用固定会话；输出策略与服务是否支持原生格式有关。
     */
    @RepeatedTest(value = 5)
    @DisplayName("结构化输出：call(msgs, SomeClass.class) 返回 record")
    public void test06StructuredOutput() {
        try (HarnessAgent agent = agentBuilder.build()) {
            Msg ret = agent.call(
                    List.of(new UserMessage("上海天气如何？")),
                    WeatherResponse.class,
                    RuntimeContext.builder().userId("zhouwy").sessionId("sStructured").build()
            ).block();

            WeatherResponse data = ret.getStructuredData(WeatherResponse.class);
            System.out.println("location: " + data.location());
            System.out.println("temperature: " + data.temperature());
            System.out.println("condition: " + data.condition());
        }
    }

    /**
     * 观察强制合成工具如何帮助提取结构化输出。
     * <p>关键点：nativeStructuredOutput=false 走合成工具路径；Specific(generate_response) 要求选择该工具，服务需支持该参数。
     * <p>观察：同会话连续三次检查 _structured_output；thinking 参数是提供商扩展，不能认为所有模型都通用。
     */
    @Test
    @DisplayName("强制 tool_choice：复用同一会话 3 次，仍能稳定拿到结构化输出")
    public void test08ForceToolChoice() throws IOException {
        Properties props = loadLocalProperties();
        OpenAIChatModel forced = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                // false 选择框架的非原生结构化输出路径；普通问答仍正常使用该模型配置。
                .nativeStructuredOutput(false)
                .generateOptions(GenerateOptions.builder()
                        // 这一轮必须调用 generate_response 这个工具，模型说不出纯文本
                        // toolChoice 请求提供商强制选择指定工具；这不是执行权限策略，服务可能因不支持该选项而拒绝请求。
                        .toolChoice(new ToolChoice.Specific("generate_response"))
                        // 针对该示例服务禁用 thinking 以配合强制工具选择；支持情况需按具体模型与接口版本核对。
                        .additionalBodyParam("thinking", java.util.Map.of("type", "disabled"))
                        .build())
                .stream(true)
                .build();

        // 故意复用同一个会话：正常写法这里是薄弱点
        RuntimeContext ctx = RuntimeContext.builder().userId("zhouwy").sessionId("sForce").build();

        try (HarnessAgent agent = agentBuilder.model(forced).build()) {
            for (int i = 1; i <= 3; i++) {
                Msg ret = agent.call(List.of(new UserMessage("上海天气如何？")),
                        WeatherResponse.class, ctx).block();
                Object so = ret.getMetadata() == null ? null : ret.getMetadata().get("_structured_output");
                System.out.println("原始内容："+ret.getTextContent()+"第 " + i + " 次: " + (so == null ? "没拿到结构化结果" : "拿到 -> " + so));
            }
        }
    }

    /**
     * 在类型转换前观察模型文本和工具参数。
     * <p>关键点：onModelCall 收集 TextBlockDeltaEvent 与 ToolCallDeltaEvent，到完成后汇总；这些是 SDK 事件，不是 HTTP 原始字节。
     * <p>观察：比较聚合的原始参数和 _structured_output 对象，定位问题发生在生成还是解析阶段。
     */
    @Test
    @DisplayName("Middleware 抓结构化前的原始输出：模型吐的 tool_call 参数 vs 框架解析后的对象")
    public void test09TapRawBeforeStructured() throws IOException {
        Properties props = loadLocalProperties();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .generateOptions(GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific("generate_response"))
                        .additionalBodyParam("thinking", java.util.Map.of("type", "disabled"))
                        .build())
                .stream(true)
                .build();

        // 拦截每一次模型调用的事件流：TextBlockDelta = 文本 token，ToolCallDelta = 工具入参 token
        MiddlewareBase tap = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                StringBuilder text = new StringBuilder();
                StringBuilder args = new StringBuilder();
                return next.apply(in)
                        .doOnNext(ev -> {
                            if (ev instanceof TextBlockDeltaEvent t && t.getDelta() != null) {
                                text.append(t.getDelta());
                            }
                            if (ev instanceof ToolCallDeltaEvent d && d.getDelta() != null) {
                                args.append(d.getDelta());
                            }
                        })
                        .doOnComplete(() -> {
                            System.out.println("[模型原始文本] " + text);
                            System.out.println("[模型原始工具入参] " + args);
                        });
            }
        };

        RuntimeContext ctx = RuntimeContext.builder().userId("zhouwy").sessionId("sRaw").build();
        try (HarnessAgent agent = agentBuilder.model(model).middleware(tap).build()) {
            Msg ret = agent.call(List.of(new UserMessage("上海天气如何？")),
                    WeatherResponse.class, ctx).block();
            System.out.println("[框架解析后 _structured_output] "
                    + (ret.getMetadata() == null ? null : ret.getMetadata().get("_structured_output")));
            System.out.println("[最终 Msg 文本] " + ret.getTextContent());
        }
    }

    /**
     * 观察一次调用内部的推理与工具循环。
     * <p>关键点：onModelCall 累计模型请求，onActing 按 toolCallId 归并工具结果；同一批可能含多个工具。
     * <p>观察：按轮次和调用 ID 对应请求与执行；模型未选工具时不会出现工具阶段。
     */
    @Test
    @DisplayName("ReAct 内循环：逐轮打印模型决策与工具结果，看清一次 call 内部跑了几轮")
    public void test10LoopTrace() {
        AtomicInteger round = new AtomicInteger();

        MiddlewareBase trace = new MiddlewareBase() {
            // 每触发一次模型调用 = 一轮
            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                int n = round.incrementAndGet();
                Map<String, String> names = new HashMap<>();
                Map<String, StringBuilder> args = new HashMap<>();
                StringBuilder text = new StringBuilder();
                System.out.println("\n===== 第 " + n + " 轮模型调用 =====");
                return next.apply(in).doOnNext(ev -> {
                    if (ev instanceof TextBlockDeltaEvent t && t.getDelta() != null) {
                        text.append(t.getDelta());
                    }
                    if (ev instanceof ToolCallStartEvent s) {
                        names.put(s.getToolCallId(), s.getToolCallName());
                        args.put(s.getToolCallId(), new StringBuilder());
                    }
                    if (ev instanceof ToolCallDeltaEvent d && d.getDelta() != null) {
                        // 参数是分段事件，要按 toolCallId 分别累积；同一模型响应中的多个工具可能交错到达。
                        args.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder()).append(d.getDelta());
                    }
                }).doOnComplete(() -> {
                    if (text.length() > 0) {
                        System.out.println("  模型文本: " + text);
                    }
                    args.forEach((id, sb) -> System.out.println("  模型发起工具: "
                            + names.get(id) + " 参数=" + sb));
                });
            }

            // 工具在框架侧执行的结果
            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                             Function<ActingInput, Flux<AgentEvent>> next) {
                Map<String, String> names = new HashMap<>();
                Map<String, StringBuilder> out = new HashMap<>();
                return next.apply(in).doOnNext(ev -> {
                    if (ev instanceof ToolResultStartEvent s) {
                        names.put(s.getToolCallId(), s.getToolCallName());
                        out.put(s.getToolCallId(), new StringBuilder());
                    }
                    if (ev instanceof ToolResultTextDeltaEvent d && d.getDelta() != null) {
                        out.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder()).append(d.getDelta());
                    }
                    if (ev instanceof ToolResultEndEvent e) {
                        String s = out.getOrDefault(e.getToolCallId(), new StringBuilder()).toString();
                        System.out.println("  工具结果[" + names.get(e.getToolCallId()) + "] state="
                                + e.getState() + " 长度=" + s.length()
                                + " 开头=" + s.substring(0, Math.min(80, s.length())).replace("\n", " "));
                    }
                });
            }
        };

        RuntimeContext ctx = RuntimeContext.builder().userId("zhouwy").sessionId("sLoop").build();
        try (HarnessAgent agent = agentBuilder.middleware(trace).build()) {
            Msg ret = agent.call(new UserMessage("上海天气如何？"), ctx).block();
            System.out.println("\n===== 总轮数: " + round.get() + " =====");
            System.out.println("最终回答: " + ret.getTextContent());
        }
    }

    /**
     * 把流式事件整理成可读的阶段日志。
     * <p>关键点：开始事件记录名称与 ID，增量事件累计片段，结束事件才输出完整内容。
     * <p>观察：区分模型请求工具与工具执行完成；不同工具参数不能混在同一个 StringBuilder。
     */
    @Test
    @DisplayName("消息与事件：按“每次调用”汇总展示，不逐 token 打印")
    public void test11Events() {
        RuntimeContext ctx = RuntimeContext.builder().userId("zhouwy").sessionId("sEvents3").build();

        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, String> toolNames = new HashMap<>();           // toolCallId -> 工具名
        Map<String, StringBuilder> pendingArgs = new HashMap<>();  // toolCallId -> 参数累积
        StringBuilder text = new StringBuilder();                  // 文本累积

        try (HarnessAgent agent = agentBuilder.build()) {
            agent.streamEvents(new UserMessage("上海天气如何？"), ctx)
                    .doOnNext(ev -> {
                        switch (ev.getType()) {
                            case MODEL_CALL_START -> System.out.println("\n--- 第 "
                                    + round.incrementAndGet() + " 次模型调用 ---");
                            case TEXT_BLOCK_DELTA -> text.append(((TextBlockDeltaEvent) ev).getDelta());
                            case TEXT_BLOCK_END -> {
                                if (text.length() > 0) {
                                    System.out.println("  [模型文本] " + text);
                                    text.setLength(0);
                                }
                            }
                            case TOOL_CALL_START -> {
                                ToolCallStartEvent s = (ToolCallStartEvent) ev;
                                toolNames.put(s.getToolCallId(), s.getToolCallName());
                                pendingArgs.put(s.getToolCallId(), new StringBuilder());
                            }
                            case TOOL_CALL_DELTA -> {
                                ToolCallDeltaEvent d = (ToolCallDeltaEvent) ev;
                                pendingArgs.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder())
                                        .append(d.getDelta());
                            }
                            case TOOL_CALL_END -> {
                                io.agentscope.core.event.ToolCallEndEvent e =
                                        (io.agentscope.core.event.ToolCallEndEvent) ev;
                                System.out.println("  [模型要调工具] " + toolNames.get(e.getToolCallId())
                                        + " 参数=" + pendingArgs.get(e.getToolCallId()));
                            }
                            case TOOL_RESULT_START -> {
                                ToolResultStartEvent r = (ToolResultStartEvent) ev;
                                System.out.println("  [开始执行] " + r.getToolCallName());
                            }
                            case TOOL_RESULT_END -> {
                                ToolResultEndEvent e = (ToolResultEndEvent) ev;
                                System.out.println("  [执行结束] " + e.getState());
                            }
                            default -> { }
                        }
                    })
                    .blockLast();
        }
        System.out.println("\n总模型调用次数: " + round.get());
    }

    /**
     * 观察提示词限制工具批次的实际效果。
     * <p>关键点：提示词请求每次最多一个工具，但没有配置执行器的硬并发上限，也没有移除其他工具。
     * <p>观察：看每轮工具数量；不能把模型一次遵守提示词当成强制串行保证。
     */
    @Test
    @DisplayName("提示词约束：一次只调一个工具（对比默认的并行工具调用）")
    public void test12SingleToolPerRound() {
        // 每次新建一个实例，跑两轮完整问答，看每轮模型要调几个工具
        for (int run = 1; run <= 2; run++) {
            System.out.println("\n########## 第 " + run + " 次完整问答 ##########");
            int finalRun = run;

            MiddlewareBase trace = new MiddlewareBase() {
                private final java.util.concurrent.atomic.AtomicInteger round =
                        new java.util.concurrent.atomic.AtomicInteger();

                @Override
                public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput in,
                                                    Function<ModelCallInput, Flux<AgentEvent>> next) {
                    int n = round.incrementAndGet();
                    Map<String, String> names = new HashMap<>();
                    Map<String, StringBuilder> args = new HashMap<>();
                    StringBuilder text = new StringBuilder();
                    return next.apply(in).doOnNext(ev -> {
                        if (ev instanceof TextBlockDeltaEvent t && t.getDelta() != null) {
                            text.append(t.getDelta());
                        }
                        if (ev instanceof ToolCallStartEvent st) {
                            names.put(st.getToolCallId(), st.getToolCallName());
                            args.put(st.getToolCallId(), new StringBuilder());
                        }
                        if (ev instanceof ToolCallDeltaEvent d && d.getDelta() != null) {
                            args.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder()).append(d.getDelta());
                        }
                    }).doOnComplete(() -> {
                        System.out.println("  第 " + n + " 轮模型调用：工具数=" + names.size());
                        args.forEach((id, sb) -> System.out.println("     调用 " + names.get(id)
                                + " 参数=" + sb));
                        if (text.length() > 0) {
                            System.out.println("     文本=" + text);
                        }
                    });
                }
            };

            HarnessAgent.Builder b = HarnessAgent.builder()
                    .name("single-tool")
                    .agentId("single-tool")
                    .sysPrompt("you are a helper。每次回复最多只能调用一个工具，禁止在同一次回复里并行调用多个工具。")
                    .model(agentBuilderModel())
                    .middleware(trace)
                    .workspace(Path.of("./data/workspace"))
                    .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")));

            RuntimeContext ctx = RuntimeContext.builder().userId("zhouwy")
                    .sessionId("sSingle-" + finalRun).build();
            try (HarnessAgent agent = b.build()) {
                Msg ret = agent.call(new UserMessage("上海天气如何？"), ctx).block();
                System.out.println("  最终回答: " + ret.getTextContent());
            }
        }
    }

    /** 复用 before() 里那套模型配置（读 properties 构造），给需要自定义 sysPrompt 的用例用。 */
    private static OpenAIChatModel agentBuilderModel() {
        try {
            Properties props = loadLocalProperties();
            return OpenAIChatModel.builder()
                    .apiKey(props.getProperty("openrouter.api.key"))
                    .modelName(props.getProperty("openrouter.model"))
                    .baseUrl(props.getProperty("openrouter.base.url"))
                    .nativeStructuredOutput(false)
                    .stream(true)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

        /** 读取 config/openrouter.local.properties（已写入 .gitignore，不会入库）。 */        private static Properties loadLocalProperties() throws IOException {
            Properties props = ModelConfig.loadLocalProperties();
            return props;
        }

}
