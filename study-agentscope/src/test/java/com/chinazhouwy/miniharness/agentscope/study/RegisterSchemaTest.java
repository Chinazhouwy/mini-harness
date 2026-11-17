package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;
import java.util.function.Function;

/**
 * 中文文档「工具」里的 registerSchema：只登记一份工具说明书，不提供执行代码。
 *
 * <p>Harness 侧的入口是 {@code builder.registerExternalSchemas(List)}，它内部走的就是
 * {@code Toolkit.registerSchemas -> registerSchema -> new SchemaOnlyTool(schema)}。
 *
 * <p>只演示一个小点：模型点到这个工具时框架不执行，而是把调用挂起交回给调用方；
 * 调用方在外面执行完，把结果回填，模型接着往下答。
 */
@org.junit.jupiter.api.Tag("live")
public class RegisterSchemaTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        initModel();
    }

    /**
     * 初始化抽成普通静态方法：JUnit 走 @BeforeAll，直接跑 main 时不会执行 @BeforeAll，
     * 所以 main 和测试方法里都再兜一次。已初始化就直接返回。
     */
    private static void initModel() throws IOException {
        if (model != null) {
            return;
        }
        Properties props = ModelConfig.loadLocalProperties();
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

    public static void main(String[] args) throws IOException {
        initModel();
        new RegisterSchemaTest().testExternalSchemaSuspends();
    }

    @Test
    @DisplayName("registerSchema：说明书工具调用时挂起，外部回填后继续")
    public void testExternalSchemaSuspends() throws IOException {
        initModel();   // 直接被 main 调用时不经过 @BeforeAll，这里兜底

        // 一份「只有说明书」的工具：名字、描述、参数，没有任何实现代码
        ToolSchema schema = ToolSchema.builder()
                .name("query_inventory")
                .description("查询仓库库存。该工具由外部系统执行，框架内部没有实现。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of("sku", Map.of("type", "string")),
                        "required", List.of("sku")))
                .build();

        Path ws = Path.of("./data/schema-demo").toAbsolutePath().normalize();
        Files.createDirectories(ws.resolve("zhouwy"));

        RoundLog log = new RoundLog();
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("schema-demo").agentId("schema-demo")
                .sysPrompt("你是一个仓库助手。查询库存时必须调用 query_inventory，"
                        + "不要使用其他工具，也不要凭空编造库存数字。")
                .model(model)
                .workspace(ws)
                .registerExternalSchemas(List.of(schema))
                .middleware(log)
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sSchema-" + UUID.randomUUID()).build();

            System.out.println("===== 登记之后 =====");
            var tool = agent.getToolkit().getTool("query_inventory");
            System.out.println("  【说明书】name=" + schema.getName() + " | " + schema.getDescription());
            System.out.println("  【参数】" + schema.getParameters());
            System.out.println("  【注册结果】getTool=" + (tool == null ? "null" : tool.getClass().getSimpleName()) + " | isExternalTool=" + agent.getToolkit().isExternalTool("query_inventory"));

            log.phase = "第 1 次 call";
            System.out.println("\n----- 第 1 次 call：让模型查库存 -----");
            Msg suspended = agent.call(List.of(new UserMessage("查一下 SKU-9527 的库存，把结果告诉我。")), ctx).block();

            System.out.println("  【call 返回】generateReason=" + (suspended == null ? "null" : suspended.getGenerateReason()));
            List<ToolUseBlock> uses = suspended == null ? List.of() : suspended.getContentBlocks(ToolUseBlock.class);
            List<ToolResultBlock> placeholders = suspended == null ? List.of() : suspended.getContentBlocks(ToolResultBlock.class);
            for (ToolUseBlock u : uses) {
                System.out.println("  【挂起的调用】id=" + u.getId() + " | name=" + u.getName() + " | input=" + u.getInput());
            }
            for (ToolResultBlock r : placeholders) {
                System.out.println("  【框架给的占位结果】state=" + r.getState()
                        + " | isSuspended=" + r.isSuspended()
                        + " | output=" + (r.getOutput() == null || r.getOutput().isEmpty()
                                ? "空（框架没执行）" : headTail(r.getOutput().toString(), 80)));
            }

            if (uses.isEmpty()) {
                System.out.println("  模型这趟没点这个工具，就不回填了。");
                return;
            }
            ToolUseBlock call = uses.get(0);

            log.phase = "第 2 次 call";
            System.out.println("\n----- 外部执行 + 回填 -----");
            System.out.println("  【模型要查】" + call.getName() + "(" + call.getInput() + ")");
            System.out.println("  现在由你扮演外部系统：敲一行结果，回车后回填给模型。");
            String externalResult = readExternalResult();
            System.out.println("  【外部系统执行】" + call.getName() + "(" + call.getInput()
                    + ") → " + externalResult);

            Msg resume = ToolResultMessage.builder()
                    .result(call.getId(), call.getName(), externalResult)
                    .build();
            // 同一个 sessionId 接着聊，但必须换一个 runId，否则会撞上 "Run ID already used"
            RuntimeContext ctx2 = RuntimeContext.builder(ctx).runId(null).build();
            System.out.println("  【回填消息】role=" + resume.getRole()
                    + " | 结果挂回调用 id=" + call.getId());
            System.out.println("  【新执行】runId " + ctx.getRunId() + " → " + ctx2.getRunId());

            Msg answer = agent.call(List.of(resume), ctx2).block();
            System.out.println("  【模型最终答复】"
                    + headTail(answer == null ? "" : answer.getTextContent(), 120));
        }
    }

    /** 手工扮演外部系统：从控制台读一行结果；读到空（或 EOF）时给个占位值，避免无人值守时卡住 */
    private static String readExternalResult() {
        System.out.print("  > ");
        System.out.flush();
        Scanner scanner = new Scanner(System.in);
        String line = scanner.hasNextLine() ? scanner.nextLine() : null;
        if (line == null || line.isBlank()) {
            System.out.println("  （没读到输入，用占位值）");
            return "SKU-9527 当前库存 42 件（外部仓库系统返回）";
        }
        return line.trim();
    }

    /** 只打印每轮新增消息里的工具调用和工具结果，看得到「挂起」和「回填」分别在哪一轮出现 */
    private static final class RoundLog implements MiddlewareBase {

        private int round;
        private int lastCount;
        private String phase = "";

        @Override
        public Flux<AgentEvent> onModelCall(Agent a, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            int r = ++round;
            String tag = phase;
            return next.apply(in).doFirst(() -> {
                List<Msg> msgs = in.messages();
                System.out.println("\n  ===== " + tag + " / 第 " + r
                        + " 轮 | 消息 " + msgs.size() + " 条 =====");
                for (int i = Math.min(lastCount, msgs.size()); i < msgs.size(); i++) {
                    Msg m = msgs.get(i);
                    for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                        System.out.println("    → " + m.getRole() + " " + u.getName()
                                + " id=" + u.getId() + " " + headTail(String.valueOf(u.getInput()), 80));
                    }
                    for (ToolResultBlock res : m.getContentBlocks(ToolResultBlock.class)) {
                        System.out.println("    ← " + m.getRole() + " " + res.getName()
                                + " state=" + res.getState() + " suspended=" + res.isSuspended()
                                + " " + headTail(String.valueOf(res.getOutput()), 100));
                    }
                }
                lastCount = msgs.size();
            });
        }
    }

    /** 开头 n 字 + 末尾 n 字，中间 ....；换行压成空格，保证只占一行 */
    private static String headTail(String s, int n) {
        String one = s == null ? "" : s.replace("\n", " ").replace("\r", " ").trim();
        if (one.length() <= n * 2) {
            return one;
        }
        return one.substring(0, n) + " .... " + one.substring(one.length() - n);
    }
}
