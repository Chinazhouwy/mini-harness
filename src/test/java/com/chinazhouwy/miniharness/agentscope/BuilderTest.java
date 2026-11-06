package com.chinazhouwy.miniharness.agentscope;

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
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;


public class BuilderTest {

        public static HarnessAgent.Builder agentBuilder;

        @BeforeAll
       public  static void before() throws IOException {
          Properties props = loadLocalProperties();
          String apiKey = props.getProperty("openrouter.api.key");
          String baseUrl = props.getProperty("openrouter.base.url", "https://openrouter.ai/api/v1");
          String modelName = props.getProperty("openrouter.model", "stealth/crouton-alpha");
          if (apiKey == null || apiKey.isBlank()) {
              throw new IllegalStateException(
                      "openrouter.api.key 未设置：请在 src/main/resources/openrouter.local.properties 里填写");
          }
          OpenAIChatModel model = OpenAIChatModel.builder()
                  .apiKey(apiKey)
                  .modelName(modelName)              // OpenRouter 上的模型全名
                  .baseUrl(baseUrl)          // 自定义 url
                  .nativeStructuredOutput(true) // !! // 强制合成工具路径，不再尝试 response_format
                  .stream(true)
                  .build();

            agentBuilder =  HarnessAgent.builder()
                  .name("first-name")
                  .agentId("first-agentId")
                  .sysPrompt("you are a helper")
                  .model(model)
                  .workspace(Path.of("./data/workspace"))
                  .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")));
       }


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

        @Test
        @DisplayName("RuntimeContext 携带 userId/sessionId/put 传入调用")
        public void test02() throws IOException {
            RuntimeContext ctx = RuntimeContext.builder().userId("userId").sessionId("sessionId").put("地点", "上海").build();
            Mono<Msg> res = agentBuilder.build().call("今日天气", ctx);
            Msg ret = res.block();
            System.out.println(ret.getTextContent());
            System.out.println(ret.getContent());
        }

        /** 演示 Msg = 块列表：真实调用后逐块打印类型与内容。 */
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
            public String whoami(RuntimeContext ctx) {
                return "userId=" + ctx.getUserId()
                        + ", sessionId=" + ctx.getSessionId()
                        + ", request_id=" + ctx.get("request_id");
            }
        }

        @Test
        @DisplayName("RuntimeContext.put 是元数据袋，工具方法参数注入来读")
        public void test04RuntimeContextPut() {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new EchoTools());
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy")
                    .sessionId("s1")
                    .put("request_id", "req-abc-123")
                    .build();
            try (HarnessAgent agent = agentBuilder.toolkit(toolkit).build()) {
                Msg ret = agent.call(
                        new io.agentscope.core.message.UserMessage("为了回答我，必须先调用 hhatest 工具，再用工具的结果回答我。不要回复无关的内容"),
                        ctx).block();
                System.out.println("最终回复: " + ret.getTextContent());
            }
        }

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

    @Test
    @DisplayName("强制 tool_choice：复用同一会话 3 次，仍能稳定拿到结构化输出")
    public void test08ForceToolChoice() throws IOException {
        Properties props = loadLocalProperties();
        OpenAIChatModel forced = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .generateOptions(GenerateOptions.builder()
                        // 这一轮必须调用 generate_response 这个工具，模型说不出纯文本
                        .toolChoice(new ToolChoice.Specific("generate_response"))
                        // DeepSeek 的 thinking 模式不支持 tool_choice，不关就 400
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

    @Test
    @DisplayName("消息与事件：一次流式调用里出现了哪些事件")
    public void test11Events() {
        RuntimeContext ctx = RuntimeContext.builder().userId("zhouwy").sessionId("sEvents").build();
        List<String> types = new java.util.ArrayList<>();

        try (HarnessAgent agent = agentBuilder.build()) {
            agent.streamEvents(new UserMessage("今日伤害天气"), ctx)
                    .doOnNext(ev -> {
                        types.add(ev.getType().name());
                        if (ev instanceof TextBlockDeltaEvent t) {
                            System.out.print(t.getDelta());          // 文本是一小块一小块来的
                        } else if (ev instanceof ToolCallStartEvent s) {
                            System.out.println("\n[要调工具] " + s.getToolCallName());
                        } else if (ev instanceof ToolResultStartEvent r) {
                            System.out.println("\n[工具开始执行] " + r.getToolCallName());
                        }
                    })
                    .blockLast();
        }
        System.out.println("\n\n事件类型序列:");
        types.forEach(t -> System.out.println("  " + t));
    }

        /** 读取 src/main/resources/openrouter.local.properties（已写入 .gitignore，不会入库）。 */        private static Properties loadLocalProperties() throws IOException {
            Properties props = new Properties();
            try (InputStream in = BuilderTest.class.getClassLoader()
                    .getResourceAsStream("openrouter.local.properties")) {
                if (in == null) {
                    throw new IllegalStateException(
                            "找不到 openrouter.local.properties，请确认文件在 src/main/resources/ 下");
                }
                props.load(in);
            }
            return props;
        }

}
