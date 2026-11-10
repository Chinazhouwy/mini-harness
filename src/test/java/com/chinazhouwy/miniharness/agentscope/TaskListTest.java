package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/**
 * 演示 enableTaskList()：给模型一张能自己写、自己更新的待办清单。
 *
 * <p>只做三件事：
 * 1. 不开这个开关时，工具列表里没有 todo_write；
 * 2. 开了之后有，且提示词里多出一段「## Task List」说明；
 * 3. 跑一个三步任务，看模型怎么用 todo_write 推进度、每轮又怎么被提醒。
 */
public class TaskListTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = TaskListTest.class.getClassLoader()
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
    @DisplayName("enableTaskList：待办清单开关与用法")
    public void testTaskList() throws IOException {

        Path ws = Path.of("./data/task-demo").toAbsolutePath().normalize();
        Files.createDirectories(ws.resolve("zhouwy"));
        Files.writeString(ws.resolve("zhouwy").resolve("note.txt"), "待办演示用的探针文件");

        // 一、先不开开关，看工具列表里有没有 todo_write
        try (HarnessAgent plain = HarnessAgent.builder()
                .name("task-demo").agentId("task-demo")
                .sysPrompt("你是一个助手。")
                .model(model)
                .workspace(ws)
                .build()) {
            System.out.println("【不开 enableTaskList】工具列表里有 todo_write："
                    + (plain.getToolkit().getTool("todo_write") != null));
        }

        // 二、开开关，同样看一遍
        RoundLog log = new RoundLog();
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("task-demo").agentId("task-demo")
                .sysPrompt("你是一个助手。凡是需要多步完成的任务，先用 todo_write 把步骤列出来，"
                        + "再按顺序做，每完成一步就更新清单。")
                .model(model)
                .workspace(ws)
                .enableTaskList()
                .middleware(log)
                .build()) {

            System.out.println("【开了 enableTaskList】工具列表里有 todo_write："
                    + (agent.getToolkit().getTool("todo_write") != null));

            RuntimeContext rc = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sTask-" + UUID.randomUUID()).build();
            agent.call(List.of(new UserMessage(
                    "请分三步完成：1) 读 note.txt，2) 数出内容有多少个字，3) 把结果告诉我。"
                            + "先把这三步用 todo_write 记下来，再一步步做完。")), rc).block();
        }

        System.out.println("\n===== 一趟共请求模型 " + log.roundCount() + " 轮 =====");
    }

    /** 开头 n 字 + 末尾 n 字，中间 ....；换行压成空格，保证只占一行 */
    private static String headTail(String s, int n) {
        String one = s == null ? "" : s.replace("\n", " ").replace("\r", " ").trim();
        if (one.length() <= n * 2) {
            return one;
        }
        return one.substring(0, n) + " .... " + one.substring(one.length() - n);
    }

    /** 逐轮打一问一答 + 工具细节；待办清单的每次变化单独摊开。 */
    private static final class RoundLog implements MiddlewareBase {

        private int round;
        private int lastMsgCount;

        @Override
        public Flux<AgentEvent> onModelCall(Agent a, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            int r = ++round;
            StringBuffer reply = new StringBuffer();
            List<String> tools = new ArrayList<>();
            return next.apply(in)
                    .doFirst(() -> printRound(in, r))
                    .doOnNext(ev -> {
                        if (ev instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                            reply.append(d.getDelta());
                        } else if (ev instanceof ToolCallEndEvent t) {
                            tools.add(t.getToolCallName());
                        }
                    })
                    .doFinally(sig -> {
                        String t = reply.toString().trim();
                        System.out.println("    【模型回复】"
                                + (t.isEmpty() ? "这一轮没有文字" : t.length() + "字 | " + headTail(t, 50))
                                + (tools.isEmpty() ? "" : " | 决定调用" + tools));
                    });
        }

        private void printRound(ModelCallInput in, int r) {
            List<Msg> msgs = in.messages();
            int from = Math.min(lastMsgCount, msgs.size());
            lastMsgCount = msgs.size();

            System.out.println("\n===== 第 " + r + " 轮 | 共 " + msgs.size()
                    + " 条消息，新增 " + (msgs.size() - from) + " 条 =====");

            // 工具清单：能看出这一轮模型手上有没有 todo_write
            List<String> names = new ArrayList<>();
            for (ToolSchema s : in.tools()) {
                names.add(s.getName());
            }
            System.out.println("  【工具清单】共 " + names.size() + " 个 | 含 todo_write："
                    + names.contains("todo_write") + " | " + names);

            for (int i = from; i < msgs.size(); i++) {
                Msg m = msgs.get(i);
                if (String.valueOf(m.getRole()).contains("SYSTEM")) {
                    String t = m.getTextContent() == null ? "" : m.getTextContent();
                    System.out.println("  【系统提示词】" + t.length() + "字 | 含 Task List 说明："
                            + t.contains("## Task List") + " | " + headTail(t, 50));
                    continue;
                }
                // 每轮都跟着的那条运行时状态不是人说的话，单独标出来，别和提问混在一起
                String t = m.getTextContent();
                if (t != null && t.startsWith("<RUNTIME_STATE>")) {
                    System.out.println("  【运行时状态】" + t.length() + "字 | " + headTail(t, 40));
                    continue;
                }
                System.out.println("  " + describe(m));
            }

            // 本轮清单里的待办快照只打最新的一份，避免把历史几次都重复贴出来
            String latest = null;
            for (Msg m : msgs) {
                for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                    if ("todo_write".equals(u.getName())) {
                        latest = renderTodos(u.getInput());
                    }
                }
            }
            if (latest != null) {
                System.out.println("  【清单最新】" + latest);
            }
        }

        /** 把 todo_write 的参数摊成一行：序号 状态 内容 */
        private static String renderTodos(Object input) {
            if (!(input instanceof Map<?, ?> map)) {
                return String.valueOf(input);
            }
            Object raw = map.get("todos");
            if (!(raw instanceof List<?> list)) {
                return String.valueOf(input);
            }
            List<String> one = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Map<?, ?> item) {
                    one.add((i + 1) + "." + item.get("status") + " " + item.get("content"));
                }
            }
            return String.join(" | ", one);
        }

        private static String describe(Msg m) {
            StringBuilder line = new StringBuilder("[").append(m.getRole()).append("]");
            for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                line.append(" → ").append(u.getName()).append(' ')
                        .append(headTail(String.valueOf(u.getInput()), 100));
            }
            for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                StringBuilder out = new StringBuilder();
                r.getOutput().forEach(b -> out.append(b.toString()));
                line.append(" ← ").append(r.getName()).append(' ')
                        .append(headTail(out.toString(), 100));
            }
            String text = m.getTextContent();
            if (text != null && !text.isBlank()) {
                line.append(' ').append(text.length()).append("字 | ").append(headTail(text, 60));
            }
            return line.toString();
        }

        int roundCount() {
            return round;
        }
    }
}
