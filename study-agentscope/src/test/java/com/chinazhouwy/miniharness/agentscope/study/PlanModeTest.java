package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * 中文文档「计划模式（Plan Mode）」。
 *
 * <p>只演示一个小点：开了开关之后，程序化进入只读阶段，
 * 普通写文件工具会被拦下来，而专用的 plan_write 能把方案写进 plans/PLAN.md。
 * 用 agent.enterPlanMode(ctx) 是为了让结果稳定，不依赖模型自己决定要不要进 plan。
 */
@org.junit.jupiter.api.Tag("live")
public class PlanModeTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = ModelConfig.loadLocalProperties();
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

    @Test
    @DisplayName("计划模式：写文件被拦，plan_write 能写计划文件")
    public void testPlanModeReadOnly() throws IOException {

        Path ws = Path.of("./data/plan-demo").toAbsolutePath().normalize();
        Path note = ws.resolve("zhouwy").resolve("note.txt");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "原始内容");

        // 跑前清场：只删本 demo 工作区里名字精确为 PLAN.md 的旧文件
        for (Path p : findPlanFiles(ws)) {
            Files.deleteIfExists(p);
        }

        RoundLog log = new RoundLog();
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("plan-demo").agentId("plan-demo")
                .sysPrompt("你是一个助手。必须实际调用工具完成任务，不要只用文字回答。")
                .model(model)
                .workspace(ws)
                .enablePlanMode()
                .planFileDirectory("plans")
                .middleware(log)
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPlan-" + UUID.randomUUID()).build();

            for (String name : List.of("plan_enter", "plan_write", "plan_exit")) {
                System.out.println("【工具】" + name + " = "
                        + (agent.getToolkit().getTool(name) != null));
            }

            agent.enterPlanMode(ctx);
            System.out.println("【程序化进入】isPlanModeActive=" + agent.isPlanModeActive(ctx));

            System.out.println("\n----- 一次 call：让它改 note.txt -----");
            Msg a1 = agent.call(List.of(new UserMessage(
                    "把 hello 追加写入 note.txt。必须实际调用工具。")), ctx).block();
            String answer = a1 == null ? "" : a1.getTextContent();
            System.out.println("  【模型最终答复】" + (answer == null || answer.isBlank()
                    ? "（这趟没输出文字，最后一步停在工具调用）" : headTail(answer, 100)));
            System.out.println("  【文件现状】note.txt = " + Files.readString(note));

            List<Path> plans = findPlanFiles(ws);
            System.out.println("\n【计划文件】找到 " + plans.size() + " 个 PLAN.md");
            for (Path p : plans) {
                System.out.println("  " + p);
                System.out.println("  内容：" + headTail(Files.readString(p), 200));
            }
            System.out.println("【结束时】isPlanModeActive=" + agent.isPlanModeActive(ctx));
        }
    }

    private static List<Path> findPlanFiles(Path ws) throws IOException {
        List<Path> found = new ArrayList<>();
        try (var stream = Files.walk(ws)) {
            stream.filter(p -> p.getFileName().toString().equals("PLAN.md")).forEach(found::add);
        }
        return found;
    }

    /** 只盯每轮新增消息里的工具调用和工具结果，看得到那条拒绝原文 */
    private static final class RoundLog implements MiddlewareBase {

        private int round;
        private int lastCount;

        @Override
        public Flux<AgentEvent> onModelCall(Agent a, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            int r = ++round;
            return next.apply(in).doFirst(() -> {
                List<Msg> msgs = in.messages();
                System.out.println("\n  ===== 第 " + r + " 轮 | 消息 " + msgs.size() + " 条 =====");
                for (int i = Math.min(lastCount, msgs.size()); i < msgs.size(); i++) {
                    Msg m = msgs.get(i);
                    for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                        System.out.println("    → " + u.getName() + " "
                                + headTail(String.valueOf(u.getInput()), 100));
                    }
                    for (ToolResultBlock res : m.getContentBlocks(ToolResultBlock.class)) {
                        StringBuilder out = new StringBuilder();
                        res.getOutput().forEach(b -> out.append(b.toString()));
                        System.out.println("    ← " + res.getName() + " "
                                + headTail(out.toString(), 160));
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
