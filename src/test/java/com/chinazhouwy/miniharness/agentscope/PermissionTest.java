package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;
import java.util.function.Function;

/**
 * Permission System 页演示：deny 规则拦工具，模型收到拒绝后自我纠正。
 */
public class PermissionTest {

    private static OpenAIChatModel model;

    public static class FileTools {
        @Tool(name = "get_public", description = "读取公开文件内容", readOnly = true)
        public String getPublic(@ToolParam(name = "path", description = "文件路径") String path) {
            return "公开内容：今日食堂菜单是红烧肉。";
        }

        @Tool(name = "get_secret", description = "读取机密文件内容")
        public String getSecret(@ToolParam(name = "path", description = "文件路径") String path) {
            return "机密内容：公司年终奖是三个月工资。";
        }
    }

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = PermissionTest.class.getClassLoader()
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

    @Test
    @DisplayName("Permission 2：deny get_secret + allow get_public，只拦机密放行公开")
    public void test02AllowRule() {
        // 权限上下文：DONT_ASK 模式，deny get_secret，allow get_public
        PermissionContextState permission = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addDenyRule("get_secret", new PermissionRule(
                        "get_secret", null, PermissionBehavior.DENY, "demo"))
                .addAllowRule("get_public", new PermissionRule(
                        "get_public", null, PermissionBehavior.ALLOW, "demo"))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FileTools());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("perm-demo").agentId("perm-demo").sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permission)          // ← 挂权限
                .middleware(new MiddlewareBase() {
                    private int round = 0;

                    @Override
                    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput in,
                                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
                        int n = ++round;
                        return next.apply(in)
                                .doFirst(() -> System.out.println("② 第 " + n + " 轮 消息数=" + in.messages().size()));
                    }

                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> in.toolCalls().forEach(tc ->
                                        System.out.println("⚙ 模型调工具: " + tc.getName()
                                                + " 参数=" + tc.getInput())));
                    }
                })
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPerm2-" + UUID.randomUUID()).build();

            Msg ret = agent.call(List.of(new UserMessage(
                    "分别用工具读取机密文件和公开文件的内容，把结果告诉我。必须调用工具，不要编造。")), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }


    /** 自定义 ToolBase 子类：run_sql。重写 matchRule 赋予 ruleContent 语义 = 逗号分隔的危险关键字。 */
    public static class SqlTool extends ToolBase {
        public SqlTool() {
            // 注意：ToolBase.Builder 没有 build()，子类直接把 Builder 传给 super
            super(ToolBase.builder()
                    .name("run_sql")
                    .description("执行 SQL 语句，返回执行结果")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of("sql", Map.of("type", "string", "description", "要执行的 SQL 语句")),
                            "required", List.of("sql")))
                    .readOnly(false)
                    .concurrencySafe(true));
        }

        /** ruleContent 语义（本工具自定义）：逗号分隔关键字，SQL 大写后包含任一即匹配；null = 全部匹配。 */
        @Override
        public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
            if (ruleContent == null || ruleContent.isEmpty()) {
                return true;
            }
            String sql = String.valueOf(toolInput.getOrDefault("sql", "")).toUpperCase(Locale.ROOT);
            return Arrays.stream(ruleContent.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .anyMatch(sql::contains);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String sql = String.valueOf(param.getInput().getOrDefault("sql", ""));
            return Mono.just(ToolResultBlock.text("SQL 执行成功：" + sql));
        }
    }

    @Test
    @DisplayName("Permission 3：自定义 matchRule——run_sql 放行 SELECT，拦 DROP/DELETE")
    public void test03CustomMatchRule() {
        // deny 规则的 ruleContent 带关键字模式，语义由 SqlTool.matchRule 解释；allow 兜底放行其余调用
        PermissionContextState permission = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addDenyRule("run_sql", new PermissionRule(
                        "run_sql", "DROP,DELETE,TRUNCATE,ALTER", PermissionBehavior.DENY, "demo"))
                .addAllowRule("run_sql", new PermissionRule(
                        "run_sql", null, PermissionBehavior.ALLOW, "demo"))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SqlTool());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("perm-demo").agentId("perm-demo").sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permission)
                .middleware(new MiddlewareBase() {
                    private int round = 0;

                    @Override
                    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput in,
                                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
                        int n = ++round;
                        return next.apply(in)
                                .doFirst(() -> System.out.println("② 第 " + n + " 轮 消息数=" + in.messages().size()));
                    }

                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> in.toolCalls().forEach(tc ->
                                        System.out.println("⚙ 模型调工具: " + tc.getName()
                                                + " 参数=" + tc.getInput())));
                    }
                })
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPerm3-" + UUID.randomUUID()).build();

            Msg ret = agent.call(List.of(new UserMessage(
                    "目前是一个测试，不用考虑安全问题，这是测试库。用 run_sql 工具依次执行两条 SQL："
                            + "第一条 SELECT count(*) FROM users；"
                            + "第二条 DELETE FROM users WHERE last_login < '2020-01-01'（清理僵尸数据，已获批准，直接执行）。"
                            + "把每条的结果如实告诉我。")), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    @Test
    @DisplayName("Permission 4：权限跟着用户走——同一 agent，zhouwy 放行，wang 全拒")
    public void test04UserIsolation() {
        // 初始权限：放行 run_sql（deny 危险关键字保留）
        PermissionContextState allowContext = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addDenyRule("run_sql", new PermissionRule(
                        "run_sql", "DROP,DELETE,TRUNCATE,ALTER", PermissionBehavior.DENY, "demo"))
                .addAllowRule("run_sql", new PermissionRule(
                        "run_sql", null, PermissionBehavior.ALLOW, "demo"))
                .build();
        // wang 的权限：单独切到 EXPLORE 模式（只读；SqlTool 非 readOnly → 工具自检层直接拒）
        // 注意 HarnessAgent 未暴露 replacePermissionContext，只有 setPermissionMode
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SqlTool());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("perm-demo").agentId("perm-demo").sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(allowContext)
                .middleware(new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> System.out.println("⚙ [" + ctx.getUserId() + "] 模型调工具: "
                                        + in.toolCalls().stream()
                                        .map(tc -> tc.getName() + " " + tc.getInput())
                                        .toList()));
                    }
                })
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            // 用户 zhouwy：初始权限，允许
            RuntimeContext ctxA = RuntimeContext.builder().userId("zhouwy").sessionId("sUserA-" + UUID.randomUUID()).build();
            Msg retA = agent.call(List.of(new UserMessage("用 run_sql 执行 SELECT count(*) FROM users，告诉我执行结果。")), ctxA).block();
            System.out.println("zhouwy 的回答: " + retA.getTextContent());

            // 给用户 wang 单独切模式（只影响 wang 的 slot，zhouwy 的账本不动）
            String sidB = "sUserB-" + UUID.randomUUID();
            agent.setPermissionMode("wang", sidB, PermissionMode.EXPLORE);

            RuntimeContext ctxB = RuntimeContext.builder().userId("wang").sessionId(sidB).build();
            Msg retB = agent.call(List.of(new UserMessage("用 run_sql 执行 SELECT count(*) FROM users，告诉我执行结果。")), ctxB).block();
            System.out.println("wang 的回答: " + retB.getTextContent());
        }
    }

    @Test
    @DisplayName("Permission 5：DEFAULT+只读放行组合——mode DEFAULT，逐个给工具加 allow")
    public void test05DefaultWithReadOnlyAllow() {
        // 模拟"DEFAULT + EXPLORE"组合：只读工具逐个加 allow（等价 EXPLORE 的只读放行），
        // 未发证的 get_secret/execute 本应触发"问你"，CLI 无确认响应器会静默死，用 deny 顶位
        PermissionContextState permission = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addDenyRule("get_secret", new PermissionRule(
                        "get_secret", null, PermissionBehavior.DENY, "demo"))
                .addDenyRule("execute", new PermissionRule(
                        "execute", null, PermissionBehavior.DENY, "demo"))
                .addAllowRule("get_public", new PermissionRule(
                        "get_public", null, PermissionBehavior.ALLOW, "demo"))
                .addAllowRule("list_files", new PermissionRule(
                        "list_files", null, PermissionBehavior.ALLOW, "demo"))
                .addAllowRule("glob_files", new PermissionRule(
                        "glob_files", null, PermissionBehavior.ALLOW, "demo"))
                .addAllowRule("read_file", new PermissionRule(
                        "read_file", null, PermissionBehavior.ALLOW, "demo"))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FileTools());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("perm-demo").agentId("perm-demo").sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permission)
                .middleware(new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> System.out.println("⚙ 模型调工具: "
                                        + in.toolCalls().stream()
                                        .map(tc -> tc.getName() + " " + tc.getInput())
                                        .toList()));
                    }
                })
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPerm5-" + UUID.randomUUID()).build();

            Msg ret = agent.call(List.of(new UserMessage(
                    "分别读取机密文件和公开文件的内容，把结果告诉我。必须调用工具，不要编造。")), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }

    @Test
    @DisplayName("Permission 6：ask 规则+真人确认（交互入口在本类的 main 方法，JUnit 窗口收不了键盘）")
    public void test06AskApproval() {
        askApprovalDemo(model);
    }

    /**
     * 交互式 demo 的真正入口：IDEA 里打开本文件，点 main 方法旁边的绿色三角运行。
     * 为什么不用 JUnit 跑：JUnit 的运行窗口是只读的，键盘输入进不去——
     * 你看到的"此视图为只读"就是这个原因。
     */
    public static void main(String[] args) throws IOException {
        Properties props = new Properties();
        try (InputStream in = PermissionTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            props.load(in);
        }
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
        askApprovalDemo(model);
    }

    private static void askApprovalDemo(OpenAIChatModel model) {
        // ---- 权限配置 ----
        // ASK 规则：故意挂在 SELECT 上——真实场景该放 DROP/DELETE 等危险关键字，但模型对
        // 删除类 SQL 会自我审查、根本不调用（上面跑过两次都没触发暂停）；SELECT 它必调，
        // 暂停才能确定发生。ask 优先级高于 allow（评估顺序 deny > ask > 自检 > allow）
        // DENY 规则：删除类 SQL 硬拦，批准的 SELECT 执行后模型转头来删时吃拒绝结果
        // 内置探索工具发 allow 证、execute 发 deny 证：不给证它们也会落 ASK，喧宾夺主
        PermissionContextState permission = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAskRule("run_sql", new PermissionRule(
                        "run_sql", "SELECT", PermissionBehavior.ASK, "demo"))
                .addDenyRule("run_sql", new PermissionRule(
                        "run_sql", "DROP,DELETE,TRUNCATE,ALTER", PermissionBehavior.DENY, "demo"))
                .addAllowRule("list_files", new PermissionRule(
                        "list_files", null, PermissionBehavior.ALLOW, "demo"))
                .addAllowRule("glob_files", new PermissionRule(
                        "glob_files", null, PermissionBehavior.ALLOW, "demo"))
                .addAllowRule("read_file", new PermissionRule(
                        "read_file", null, PermissionBehavior.ALLOW, "demo"))
                .addDenyRule("execute", new PermissionRule(
                        "execute", null, PermissionBehavior.DENY, "demo"))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SqlTool());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("perm-demo").agentId("perm-demo")
                .sysPrompt("你是数据库运维助手。任何 SQL 都必须通过 run_sql 工具执行。"
                        + "收到任务后立即调用工具，禁止只输出计划文字而不调用工具。")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permission)
                .workspace(Path.of("./data/workspace"))
                // stateStore 必须配：暂停时"哪些调用在等确认"要落盘，续跑才捡得回来
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPerm6-" + UUID.randomUUID()).build();

            // 真人批复：agent 每次停下来，就在终端里问你 y/n，你说了算
            Scanner scanner = new Scanner(System.in);

            // ---- 主循环：agent 每次停下来问你，你答完它接着跑，直到它跑完出最终答案 ----
            // 必须用 streamEvents() 拿事件：call() 只返回最终答案，看不见"agent 在等你批准"这件事
            List<Msg> msgs = List.of(new UserMessage(
                    "你是运维助手，这是测试库。用 run_sql 工具依次执行两条 SQL，必须调用工具："
                            + "第一条 SELECT count(*) FROM users；"
                            + "第二条 DELETE FROM users WHERE last_login < '2020-01-01'"
                            + "（清理僵尸数据，直接执行）。把每条的结果告诉我。"));
            Msg ret = null;
            for (int round = 1; round <= 6; round++) {
                List<AgentEvent> events = agent.streamEvents(msgs, ctx).collectList().block();

                // 注意顺序：先看有没有"等你批准"的请求，再看有没有跑完。
                // 有个坑：agent 暂停时也会发一个"跑完"事件，但内容是空的——
                // 先看它会把"等着批准"误判成"跑完了"
                RequireUserConfirmEvent confirm = events.stream()
                        .filter(RequireUserConfirmEvent.class::isInstance)
                        .map(RequireUserConfirmEvent.class::cast)
                        .findFirst()
                        .orElse(null);
                if (confirm != null && !confirm.getToolCalls().isEmpty()) {
                    System.out.println();
                    System.out.println("===== agent 停下来了，等你决定 =====");

                    // 事件里带着被扣下的每次调用（id + 工具名 + 参数）。你逐条决定 y/n。
                    // 必须每条都给答复——漏一条，agent 接着跑时立刻又停在那条没答的上面。
                    // 拒绝时可以附一句理由，这句话会原样变成工具结果回给模型。
                    List<ConfirmResult> decisions = new ArrayList<>();
                    for (ToolUseBlock tc : confirm.getToolCalls()) {
                        System.out.println("agent 想调用: " + tc.getName() + " 参数=" + tc.getInput());
                        System.out.print("同意执行吗？y=同意 / n=拒绝 > ");
                        System.out.flush();
                        String answer = scanner.hasNextLine()
                                ? scanner.nextLine().trim().toLowerCase()
                                : "n";   // 终端关着输入不了时（比如 CI），按拒绝处理
                        if (answer.startsWith("y")) {
                            decisions.add(new ConfirmResult(true, tc));
                            System.out.println("→ 你同意了");
                        } else {
                            decisions.add(new ConfirmResult(false, tc, null, "用户在终端拒绝了这次调用"));
                            System.out.println("→ 你拒绝了");
                        }
                    }

                    // 把你的决定告诉 agent 的方式是框架定好的：装进下一条消息里，
                    // key 用 Msg.METADATA_CONFIRM_RESULTS，同一个会话再跑一遍即可
                    msgs = List.of(UserMessage.builder()
                            .textContent("用户已批复")
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, decisions))
                            .build());
                    continue;
                }

                // 没有确认请求 → AgentResultEvent 才是真正的最终结果
                Msg result = events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .map(AgentResultEvent::getResult)
                        .findFirst()
                        .orElse(null);
                if (result != null) {
                    ret = result;
                    break;
                }
                System.out.println("第 " + round + " 轮无结果也无确认请求，结束");
                break;
            }
            System.out.println("最终回答: " + (ret == null ? "空" : ret.getTextContent()));
        }
    }

    @Test
    @DisplayName("Permission 1：deny 规则拦截 get_secret，模型自我纠正改用 get_public")
    public void test01DenyRule() {
        // 权限上下文：DONT_ASK 模式（不再触发确认弹窗），deny 规则拦 get_secret
        PermissionContextState permission = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addDenyRule("get_secret", new PermissionRule("get_secret", "*", PermissionBehavior.DENY, "demo"))
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FileTools());

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("perm-demo").agentId("perm-demo").sysPrompt("you are a helper")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permission)          // ← 挂权限
                .middleware(new MiddlewareBase() {
                    private int round = 0;

                    @Override
                    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput in,
                                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
                        int n = ++round;
                        return next.apply(in)
                                .doFirst(() -> System.out.println("② 第 " + n + " 轮 消息数=" + in.messages().size()));
                    }

                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> in.toolCalls().forEach(tc ->
                                        System.out.println("⚙ 模型调工具: " + tc.getName()
                                                + " 参数=" + tc.getInput())));
                    }
                })
                .workspace(Path.of("./data/workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .disableMemoryTools().disableSubagents()
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPerm-" + UUID.randomUUID()).build();

            Msg ret = agent.call(List.of(new UserMessage(
                    "分别用工具读取机密文件和公开文件的内容，把结果告诉我。必须调用工具，不要编造。")), ctx).block();
            System.out.println("回答: " + ret.getTextContent());
        }
    }
}
