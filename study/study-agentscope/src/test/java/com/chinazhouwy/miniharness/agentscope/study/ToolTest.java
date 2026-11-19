package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * tool 页演示：自己写一个 @Tool 工具，注册进 Toolkit，让模型调用。
 * 全局中间件打日志，观察工具被调用的全过程。
 */
@org.junit.jupiter.api.Tag("live")
public class ToolTest {

    private static OpenAIChatModel model;

    /** 全局中间件（和 ModelTest 里一样）：5 个钩子全打摘要。 */
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

    /** 自定义工具：一个普通 Java 类，方法上标 @Tool。 */
    public static class MyTools {

        @Tool(name = "query_fund_nav", description = "查询基金最新净值（估值）。当用户问某个基金的净值/涨跌时必须调用此工具。")
        // 工具名虽然叫最新净值，返回值其实是固定演示 JSON；这里没有访问行情服务。
        public String queryFundNav(
                @ToolParam(name = "fund_code", description = "基金六位代码，例如 005827") String fundCode) {
            // 真实场景这里是调基金 API；demo 返回写死数据
            return "{\"code\":\"" + fundCode + "\",\"name\":\"易方达蓝筹精选\",\"nav\":2.3456,\"day_change\":\"+1.23%\",\"date\":\"2026-11-06\"}";
        }

        @Tool(name = "sum", description = "计算两个整数的和")
        public int sum(
                @ToolParam(name = "a", description = "第一个整数") int a,
                @ToolParam(name = "b", description = "第二个整数") int b) {
            return a + b;
        }

        @Tool(name = "create_ticket", description = "创建一个工单，返回新工单号")
        // 这里只模拟返回工单号，不创建真实工单；后续查询是否带上这个号是本用例的观察点。
        public String createTicket(
                @ToolParam(name = "title", description = "工单标题") String title) {
            String id = "TK-" + System.currentTimeMillis() % 100000;
            System.out.println("      [create_ticket 执行] 生成工单号=" + id);
            return "{\"ticket_id\":\"" + id + "\",\"title\":\"" + title + "\",\"status\":\"OPEN\"}";
        }

        @Tool(name = "query_ticket", description = "按工单号查询工单状态。ticket_id 必须是 create_ticket 返回的真实工单号。")
        public String queryTicket(
                @ToolParam(name = "ticket_id", description = "工单号，来自 create_ticket 的返回值") String ticketId) {
            System.out.println("      [query_ticket 执行] 收到的工单号=" + ticketId);
            return "{\"ticket_id\":\"" + ticketId + "\",\"status\":\"OPEN\",\"owner\":\"support-team\"}";
        }
    }

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

    /**
     * 观察有先后依赖的多个工具如何被模型串联。
     * <p>关键点：模型先取得模拟工单号，再把它作为查询参数；一次 call 内由框架继续组织后续轮次。
     * <p>观察：按参数与结果日志确认使用了刚返回的工单号；工单是本地固定演示数据。
     */
    @Test
    @DisplayName("工具 2：有先后依赖的两个工具（先建工单 → 再用工单号查状态）")
    public void test02DependentTools() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MyTools());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("tool-demo").agentId("tool-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                // 文件 Store 按调用的 userId/sessionId 保存和读取状态；新 Java 对象不等于新会话。
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                // 仅关闭这两类预置能力以减少干扰；聊天历史、文件工具等其他能力仍看各自配置。
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sTool-" + UUID.randomUUID()).build();

            Msg ret = agent.call(new UserMessage(
                    "帮我创建一个工单，标题是\u201c登录页报错\u201d，创建成功后查一下它的状态告诉我"), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    /**
     * 观察同一批自定义工具如何暴露给模型。
     * <p>关键点：Toolkit 注册工具与 Schema，模型自行选择净值查询、加法等方法；注册不等于执行。
     * <p>观察：核对工具事件和最终回答；净值是固定样例值，加法由本地 Java 实际计算。
     */
    @Test
    @DisplayName("工具 1：自定义 @Tool 工具，模型自动调用并消费返回值")
    public void test01CustomTool() {
        // 1. 建 Toolkit，把自己的工具类注册进去
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MyTools());

        // 2. agent 挂上这个 toolkit（注意：全局中间件也在）
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("tool-demo").agentId("tool-demo")
                .sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .middleware(GLOBAL)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sTool-" + UUID.randomUUID()).build();

            // 3. 问一个必须用工具才能答的问题
            Msg ret = agent.call(new UserMessage("帮我查一下 005827 这只基金今天的净值，顺便算一下 128+374 等于多少"), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    // ================= 2.0.4 的"参数级拦截"：在工具方法内部自己校验 =================

    /** 带参数校验的工具：/etc 下的路径直接拒绝，拒绝理由写给模型看。 */
    public static class GuardedFileTools {
        private final java.util.Map<String, String> fs = java.util.Map.of(
                "/data/report.txt", "季度报告：营收 100 万，同比增长 12%",
                "/etc/hosts", "127.0.0.1 localhost");

        @Tool(name = "get_file", description = "读取服务器上的文件内容。只允许访问 /data 目录。")
        public String getFile(@ToolParam(name = "path", description = "文件绝对路径") String path) {
            // 这是受控示例的参数拦截；真实路径权限还应处理规范化、符号链接和目录边界，不能只照抄前缀判断。
            if (path.startsWith("/etc")) {
                // 这就是 2.0.4 上的"拦截"：返回错误文本，模型会自我纠正
                return "Error: PolicyGuard 禁止访问 /etc 下的文件，只允许访问 /data 目录。";
            }
            return fs.getOrDefault(path, "文件不存在: " + path);
        }
    }

    /**
     * 在工具方法中执行参数级校验。
     * <p>关键点：@ToolParam 说明参数含义，路径限制则由 Java 代码检查；本例从内存 Map 返回模拟文件。
     * <p>观察：拒绝的路径返回失败信息，模型可据此调整；字符串前缀判断不是完整文件系统隔离。
     */
    @Test
    @DisplayName("参数级拦截（2.0.4 做法）：工具内部校验参数，模型收到拒绝后自我纠正")
    public void test03ParamValidation() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new GuardedFileTools());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("guarded").agentId("guarded")
                .sysPrompt("you are a helper")
                .model(model)
                .middleware(GLOBAL)
                .toolkit(toolkit)
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sTool-" + UUID.randomUUID()).build();

            // 故意让它读一个允许的 + 一个禁止的
            Msg ret = agent.call(new UserMessage(
                    "帮我读一下 /data/report.txt 和 /etc/hosts 这两个文件的内容"), ctx).block();
            System.out.println("最终回答: " + ret.getTextContent());
        }
    }

    // ================= MCP 接入：把外部 MCP server 的工具挂给 agent =================

    /**
     * 通过 AgentScope 客户端接入外部文件工具。
     * <p>关键点：stdioTransport 指定子进程命令、参数和环境；initialize 后发现工具，再 registerMcpClient 适配进 Toolkit。
     * <p>观察：工具应读取授权目录的探针文件；需 Node/npm，try-with-resources 负责关闭连接与 Agent。
     */
    @Test
    @DisplayName("MCP 接入：stdio 拉起官方 filesystem server，它的工具直接挂进 toolkit")
    public void test04McpTools() throws Exception {
        // 0. 本机 node 是 nvm 装的：npx 是个脚本，内部执行 env node，
        //    所以要么 node 在 PATH 上，要么把 nvm 的 bin 目录塞进 MCP 子进程的 PATH
        Path nvmBin;
        try (var dirs = Files.list(Path.of(System.getProperty("user.home"), ".nvm/versions/node"))) {
            nvmBin = dirs.filter(d -> Files.exists(d.resolve("bin/npx")))
                    .max(Comparator.naturalOrder())
                    .orElseThrow(() -> new IllegalStateException("nvm 下没找到 node"))
                    .resolve("bin");
        }
        Map<String, String> env = Map.of("PATH", nvmBin + ":/usr/bin:/bin");

        // 1. 授权目录 + 一个待会儿让模型去读的文件（filesystem server 只能碰这个目录）
        Path dir = Path.of("./data/mcp-demo").toAbsolutePath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("note.txt"),
                "MCP 演示：这段内容只有通过 filesystem server 的 read_file 工具才能读到。");

        // 2. stdio 方式拉起 MCP server（一个 npx 子进程），初始化，列出它带来的工具
        // 启动本地服务子进程并走 stdio，不是模型直接访问文件；此旧示例未固定服务包版本。
        try (McpClientWrapper mcp = McpClientBuilder.create("fs")
                .stdioTransport(nvmBin.resolve("npx").toString(),
                        List.of("-y", "@modelcontextprotocol/server-filesystem", dir.toString()), env)
                .buildSync()) {

            mcp.initialize().block();
            System.out.println("== MCP server 启动成功，带来的工具：");
            for (var t : mcp.listTools().block()) {
                System.out.println("   " + t.name() + " — "
                        + (t.description() == null ? "" : t.description().split("\n")[0]) + t.toString());
            }

            // 3. 注册进 toolkit：框架把 MCP 工具转成普通工具，模型用起来和自己写的 @Tool 没区别
            Toolkit toolkit = new Toolkit();
            // 把 MCP 工具定义与远程调用适配为 AgentScope 工具；与 Spring AI 的 ToolCallbackProvider 是同一层职责。
            toolkit.registerMcpClient(mcp).block();
            System.out.println("== 已注册进 toolkit");

            // 4. agent 只挂这一个 toolkit（ReActAgent 零注入，没有别的内置工具搅局）
            try (ReActAgent agent = ReActAgent.builder()
                    .name("mcp-demo")
                    .sysPrompt("you are a helper")
                    .model(model)
                    .toolkit(toolkit)
                    .middlewares(List.of(GLOBAL))
                    .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                    .build()) {

                RuntimeContext ctx = RuntimeContext.builder()
                        .userId("zhouwy").sessionId("sTool-" + UUID.randomUUID()).build();

                Msg ret = agent.call(List.of(new UserMessage(
                        "读一下 " + dir + "/note.txt 这个文件，告诉我里面写了什么")), ctx).block();
                System.out.println("回答: " + ret.getTextContent());
            }
        }
    }
}
