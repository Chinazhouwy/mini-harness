package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIToolCall;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 「上下文管理」这一页的 demo，按页内目录一节一节往下走。
 *
 * <p>第一步「从一次推理开始理解上下文」只看一件事：一次 call 里每轮请求发出去之前，
 * Harness 都会重新组织一遍这轮要发的内容，不是开头拼一次提示词一直沿用。
 *
 * <p>演示逻辑全在 test 方法里，逐轮打印的杂活交给下面的 {@link RoundLog}。
 */
public class HarnessTestDemo {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        // 模型配置放在测试资源里，跟 HarnessTest 用的是同一份
        Properties props = new Properties();
        try (InputStream in = HarnessTestDemo.class.getClassLoader()
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
    @DisplayName("上下文管理 1：每轮请求前重新组织上下文")
    public void test01EveryRoundRebuildsContext() throws IOException {

        // workspace 一定用绝对路径。相对路径会踩 2.0.4 的 startsWith 守卫缺陷（PR #4079）
        Path ws = Path.of("./data/context-demo").toAbsolutePath().normalize();
        Files.createDirectories(ws);
        // 探针文件要放在按用户分出来的那一层 {ws}/zhouwy/。
        // 放 ws 根目录的话 read_file("note.txt") 找不到，模型得绕好几轮才摸对路径。
        Files.createDirectories(ws.resolve("zhouwy"));
        Files.writeString(ws.resolve("zhouwy").resolve("note.txt"), "上下文管理第一节的探针内容 42");

        // 逐轮打印的钩子，挂上就行
        RoundLog log = new RoundLog();

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("ctx-demo").agentId("ctx-demo")
                .sysPrompt("你是一个助手。需要文件内容时必须调用 read_file 工具，不要凭空猜。")
                .model(model)
                .workspace(ws)
                .middleware(log)
                .build()) {

            RuntimeContext rc = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sCtx-" + UUID.randomUUID()).build();
            // 最后一轮的文字也由【模型回复】打印，这里不再重复打一遍
            agent.call(List.of(new UserMessage(
                    "调用 read_file 读取 note.txt，然后把文件内容原样告诉我。")), rc).block();
        }

        System.out.println("\n===== 一次 call 共请求模型 " + log.roundCount() + " 轮 =====");
    }

    /**
     * 第二步里的一个小点：工作区的规则文件在「一次调用开始时」读进来。
     * 同一个 agent、同一个会话，中间把 AGENTS.md 改掉，看两次调用各读到哪份。
     */
    @Test
    @DisplayName("上下文管理 2：规则文件在调用开始时加载")
    public void test02RulesLoadedAtCallStart() throws IOException {

        Path ws = Path.of("./data/context-demo").toAbsolutePath().normalize();
        Path agents = ws.resolve("zhouwy").resolve("AGENTS.md");
        Files.createDirectories(agents.getParent());
        Files.writeString(agents, "项目规则：回答里必须带上 ALPHA（标记 RULE_V1）。\n");

        RuleProbe probe = new RuleProbe();

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("ctx-demo").agentId("ctx-demo")
                .sysPrompt("你是一个助手，回答简短。")
                .model(model)
                .workspace(ws)
                .middleware(probe)
                .build()) {

            RuntimeContext rc = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sCtx-" + UUID.randomUUID()).build();

            probe.markCall(1);
            agent.call(List.of(new UserMessage("说一句自我介绍。")), rc).block();

            // 只改磁盘上的规则文件，agent 和会话都不换
            Files.writeString(agents, "项目规则：回答里必须带上 BETA（标记 RULE_V2）。\n");

            probe.markCall(2);
            agent.call(List.of(new UserMessage("再说一句自我介绍。")), rc).block();
        }
    }

    /** 开头 n 字 + 末尾 n 字，中间 ....；换行压成空格，保证只占一行 */
    private static String headTail(String s, int n) {
        String one = s.replace("\n", " ").replace("\r", " ").trim();
        if (one.length() <= n * 2) {
            return one;
        }
        return one.substring(0, n) + " .... " + one.substring(one.length() - n);
    }

    /** 只负责打一问一答和工具细节；默认提示词挪到末尾汇总，不糊在问答中间。 */
    private static final class RoundLog implements MiddlewareBase {

        private final AtomicInteger round = new AtomicInteger();
        private final AtomicInteger lastMsgCount = new AtomicInteger();
        private final AtomicReference<String> lastSystem = new AtomicReference<>();
        private final AtomicBoolean assemblyShown = new AtomicBoolean(false);
        private final AtomicBoolean addedShown = new AtomicBoolean(false);

        // demo 里没指定 formatter，OpenAIChatModel 默认就是它，拿它复现真正发出去的请求
        private static final OpenAIChatFormatter WIRE = new OpenAIChatFormatter();

        @Override
        public Flux<AgentEvent> onReasoning(Agent a, RuntimeContext ctx, ReasoningInput in,
                                            Function<ReasoningInput, Flux<AgentEvent>> next) {
            // 每轮一份收集器：模型这一轮吐出来的文字（流式增量）和它决定调的工具
            StringBuffer modelText = new StringBuffer();
            List<String> modelTools = new ArrayList<>();
            return next.apply(in)
                    .doFirst(() -> dump(in.messages()))
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent) {
                            modelText.append(((TextBlockDeltaEvent) event).getDelta());
                        } else if (event instanceof ToolCallEndEvent) {
                            modelTools.add(((ToolCallEndEvent) event).getToolCallName());
                        }
                    })
                    // 这一轮结束就紧跟一行【模型回复】，别等下一轮才当历史看到
                    .doFinally(signal -> printModelReply(modelText.toString(), modelTools));
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent a, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            // 这里才是真正发出去的请求：内部块已经过格式化，变了形状
            return next.apply(in).doFirst(() -> printWire(WIRE.format(in.messages()), in.messages()));
        }

        /** 格式化后的清单，逐条看哪些块留下来了、哪些被丢掉或换了字段 */
        private void printWire(List<OpenAIMessage> wire, List<Msg> internal) {
            System.out.println("    ---- 真正发出去的请求（格式化后）----");
            for (int i = 0; i < wire.size(); i++) {
                OpenAIMessage m = wire.get(i);
                String content = m.getContentAsString();
                StringBuilder line = new StringBuilder("      [").append(i + 1).append("] ")
                        .append(String.format("%-10s", m.getRole()))
                        .append("content=").append(content == null ? 0 : content.length()).append("字");
                if ("system".equals(m.getRole())) {
                    line.append("（折叠）");
                    // SYSTEM 内部不止 TextBlock，把块组成列出来，才看得清字数差在哪
                    if (i < internal.size()) {
                        line.append(" | 内部块：").append(blockSummary(internal.get(i)));
                        String before = lastSystem.get();
                        String after = textOf(internal.get(i));
                        if (before != null && after.length() > before.length()
                                && addedShown.compareAndSet(false, true)) {
                            printAddedSections(insertedPart(before, after));
                        }
                    }
                }
                if (m.getToolCallId() != null) {
                    line.append(" | tool_call_id=").append(m.getToolCallId());
                }
                if (m.getToolCalls() != null) {
                    for (OpenAIToolCall c : m.getToolCalls()) {
                        line.append(" | tool_calls → ").append(c.getFunction().getName())
                                .append("(id=").append(c.getId()).append(", args=")
                                .append(headTail(String.valueOf(c.getFunction().getArguments()), 60))
                                .append(")");
                    }
                }
                // 思考内容只有被格式化器保留下来，才会以这个字段回到模型
                if ("assistant".equals(m.getRole())) {
                    String reasoning = m.getReasoningContent();
                    line.append(" | reasoning_content=")
                            .append(reasoning == null || reasoning.isEmpty()
                                    ? "无" : reasoning.length() + "字");
                }
                System.out.println(line);
            }
        }

        /** 一条消息里装了哪些块、各多少字，用来解释「内部长度」和「发出去长度」的差 */
        private static String blockSummary(Msg m) {
            List<String> parts = new ArrayList<>();
            for (ContentBlock b : m.getContent()) {
                String type = b.getClass().getSimpleName();
                if (b instanceof TextBlock) {
                    String t = ((TextBlock) b).getText();
                    parts.add(type + "(" + (t == null ? 0 : t.length()) + "字)");
                } else if (b instanceof HintBlock) {
                    String t = ((HintBlock) b).getHint();
                    parts.add(type + "(" + (t == null ? 0 : t.length()) + "字)");
                } else if (b instanceof ToolResultBlock) {
                    parts.add(type + "(" + resultText((ToolResultBlock) b).length() + "字)");
                } else {
                    parts.add(type);
                }
            }
            return String.join(" + ", parts);
        }

        /** 公共前后缀之外，就是 after 比 before 多出来的那一段 */
        private static String insertedPart(String before, String after) {
            int p = 0;
            while (p < before.length() && p < after.length()
                    && before.charAt(p) == after.charAt(p)) {
                p++;
            }
            int s = 0;
            while (s < before.length() - p && s < after.length() - p
                    && before.charAt(before.length() - 1 - s)
                    == after.charAt(after.length() - 1 - s)) {
                s++;
            }
            return after.substring(p, after.length() - s);
        }

        /** 多出来的这段按小标题拆开，只列标题和字数，不刷全文 */
        private static void printAddedSections(String added) {
            System.out.println("      【系统提示词】比推理钩子多 " + added.length()
                    + "字，按小标题拆开是这些：");
            String title = null;
            StringBuilder body = new StringBuilder();
            for (String line : added.split("\\R")) {
                if (line.startsWith("## ") || line.startsWith("### ")) {
                    printAddedSection(title, body);
                    title = line.trim();
                    body = new StringBuilder();
                } else {
                    body.append(line).append(' ');
                }
            }
            printAddedSection(title, body);
        }

        private static void printAddedSection(String title, StringBuilder body) {
            String one = body.toString().trim().replaceAll("\\s+", " ");
            if (title == null || one.isEmpty()) {
                return;
            }
            String preview = one.length() <= 45 ? one : one.substring(0, 45) + "…";
            System.out.println("        " + title + " | " + one.length() + "字 | " + preview);
        }

        /** 模型这一轮到底回了什么：有文字给首尾，没文字就说没文字，顺带列它调的工具 */
        private static void printModelReply(String text, List<String> tools) {
            String t = text.trim();
            StringBuilder line = new StringBuilder("  【模型回复】");
            if (t.isEmpty()) {
                line.append("这一轮没有文字");
            } else {
                line.append(t.length()).append("字 | ").append(headTail(t, 50));
            }
            if (!tools.isEmpty()) {
                line.append(" | 调用工具").append(tools);
            }
            System.out.println(line);
        }

        int roundCount() {
            return round.get();
        }

        private void dump(List<Msg> msgs) {
            int n = round.incrementAndGet();
            int from = Math.min(lastMsgCount.getAndSet(msgs.size()), msgs.size());

            // 提示词悄悄记一份，用来比每轮是不是同一份
            StringBuilder sb = new StringBuilder();
            for (Msg m : msgs) {
                if (isSystem(m)) {
                    sb.append(textOf(m));
                }
            }
            String prev = lastSystem.getAndSet(sb.toString());

            System.out.println("\n===== 第 " + n + " 轮 | 共 " + msgs.size()
                    + " 条消息，新增 " + (msgs.size() - from) + " 条 =====");

            // 每轮只看新增的那几条：USER 问、ASSISTANT 答、TOOL 回，顺着看下来
            for (int i = from; i < msgs.size(); i++) {
                Msg m = msgs.get(i);
                if (isSystem(m)) {
                    // 默认提示词几千字，只留首尾一点，用【系统提示词】标出来，一眼能跳过
                    String t = textOf(m);
                    System.out.println("  【系统提示词】" + t.length() + "字 | " + headTail(t, 50));
                } else {
                    System.out.println(describe(i + 1, m));
                }
            }
            if (prev != null) {
                System.out.println("  【系统提示词】与上轮一致：" + prev.equals(sb.toString()));
            }
            // 第一次出现工具结果时，把整份请求按拼接顺序展开一次，看结果是怎么塞进去的
            if (hasToolResult(msgs) && assemblyShown.compareAndSet(false, true)) {
                printAssembly(msgs);
            }
        }

        private static boolean hasToolResult(List<Msg> msgs) {
            for (Msg m : msgs) {
                if (!m.getContentBlocks(ToolResultBlock.class).isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /** 本轮发给模型的完整清单，逐条列出里面装的内容块，重点看 TOOL 那条 */
        private static void printAssembly(List<Msg> msgs) {
            System.out.println("  ---- 框架内部的消息清单（发出去之前还要过格式化） ----");
            String useId = null;
            String resultId = null;
            for (int i = 0; i < msgs.size(); i++) {
                Msg m = msgs.get(i);
                String head = "    [" + (i + 1) + "] " + String.format("%-10s", m.getRole());
                if (isSystem(m)) {
                    System.out.println(head + "TextBlock…（" + textOf(m).length() + "字，已折叠）");
                    continue;
                }
                List<String> parts = new ArrayList<>();
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof ToolUseBlock) {
                        ToolUseBlock u = (ToolUseBlock) b;
                        useId = u.getId();
                        parts.add("ToolUseBlock(id=" + u.getId() + ", name=" + u.getName()
                                + ", input=" + headTail(String.valueOf(u.getInput()), 60) + ")");
                    } else if (b instanceof ToolResultBlock) {
                        ToolResultBlock r = (ToolResultBlock) b;
                        resultId = r.getId();
                        parts.add("ToolResultBlock(id=" + r.getId() + ", name=" + r.getName()
                                + ", output=TextBlock(" + headTail(resultText(r), 60) + "))");
                    } else if (b instanceof TextBlock) {
                        String t = ((TextBlock) b).getText();
                        parts.add("TextBlock(" + (t == null ? 0 : t.length()) + "字)");
                    } else if (b instanceof ThinkingBlock) {
                        String t = ((ThinkingBlock) b).getThinking();
                        parts.add("ThinkingBlock(" + (t == null ? 0 : t.length()) + "字)");
                    } else {
                        parts.add(b.getClass().getSimpleName());
                    }
                }
                System.out.println(head + String.join(" + ", parts));
            }
            System.out.println("    配对：ASSISTANT 调用 id=" + useId + " ↔ TOOL 结果 id=" + resultId
                    + "，相同=" + (useId != null && useId.equals(resultId)));
        }

        /** 一条消息写成一行：工具名、参数、结果首尾都留着，方便看细节。 */
        private static String describe(int index, Msg m) {
            List<ToolUseBlock> uses = m.getContentBlocks(ToolUseBlock.class);
            List<ToolResultBlock> results = m.getContentBlocks(ToolResultBlock.class);
            StringBuilder line = new StringBuilder("  [").append(index).append("] ").append(m.getRole());

            // getTextContent() 取不到结构化块，工具调用和结果得单独拿出来，否则会误以为消息是空的
            for (ToolUseBlock use : uses) {
                line.append(" → ").append(use.getName()).append(' ')
                        .append(headTail(String.valueOf(use.getInput()), 120));
            }
            for (ToolResultBlock res : results) {
                line.append(" ← ").append(res.getName()).append(" : ")
                        .append(headTail(resultText(res), 120));
            }
            String text = textOf(m);
            if (!text.isEmpty()) {
                line.append(' ').append(text.length()).append("字 | ").append(headTail(text, 50));
            }
            return line.toString();
        }

        private static boolean isSystem(Msg m) {
            return String.valueOf(m.getRole()).contains("SYSTEM");
        }

        private static String textOf(Msg m) {
            return m.getTextContent() == null ? "" : m.getTextContent();
        }

        /** 工具结果装在 ToolResultBlock 的 output 里，把里面的文字块拼出来 */
        private static String resultText(ToolResultBlock res) {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : res.getOutput()) {
                if (block instanceof TextBlock) {
                    String t = ((TextBlock) block).getText();
                    if (t != null) {
                        sb.append(t);
                    }
                }
            }
            return sb.length() == 0 ? "(结果里没有文字块)" : sb.toString();
        }
    }

    /** 只盯提示词里那句规则标记，顺带把模型回的话留个首尾，不刷问答全文。 */
    private static final class RuleProbe implements MiddlewareBase {

        private final AtomicInteger call = new AtomicInteger();
        private final AtomicInteger round = new AtomicInteger();

        void markCall(int n) {
            call.set(n);
            round.set(0);
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent a, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            int r = round.incrementAndGet();
            StringBuffer reply = new StringBuffer();
            return next.apply(in)
                    .doFirst(() -> printRule(in.messages(), r))
                    .doOnNext(ev -> {
                        if (ev instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                            reply.append(d.getDelta());
                        }
                    })
                    .doFinally(sig -> {
                        String t = reply.toString().trim();
                        System.out.println("    【模型回复】" + t.length() + "字 | "
                                + (t.isEmpty() ? "这一轮没有文字" : headTail(t, 50)));
                    });
        }

        private void printRule(List<Msg> msgs, int r) {
            StringBuilder sb = new StringBuilder();
            for (Msg m : msgs) {
                if (String.valueOf(m.getRole()).contains("SYSTEM") && m.getTextContent() != null) {
                    sb.append(m.getTextContent());
                }
            }
            String s = sb.toString();
            int p = s.indexOf("RULE_V1");
            if (p < 0) {
                p = s.indexOf("RULE_V2");
            }
            System.out.println("\n  第 " + call.get() + " 次 call · 第 " + r + " 轮请求");
            System.out.println("    本轮共 " + msgs.size() + " 条消息（上一轮的问答还在不在，看这个数）");
            System.out.println("    【系统提示词】" + s.length() + "字 | 含 RULE_V1："
                    + s.contains("RULE_V1") + " | 含 RULE_V2：" + s.contains("RULE_V2"));
            if (p >= 0) {
                String near = s.substring(Math.max(0, p - 35), Math.min(s.length(), p + 45))
                        .replaceAll("\\s+", " ");
                System.out.println("    规则所在处：…" + near + "…");
            }
        }
    }
}
