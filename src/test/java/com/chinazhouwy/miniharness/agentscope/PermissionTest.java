package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
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
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
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
