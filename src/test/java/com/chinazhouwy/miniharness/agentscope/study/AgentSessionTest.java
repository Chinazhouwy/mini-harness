package com.chinazhouwy.miniharness.agentscope.study;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * 中文文档「会话操作、事件与恢复」→「提交任务，让它在后台执行」。
 *
 * <p>只演示一个小点：agent.session(ctx) 拿到的是操作句柄，本身不启动模型；
 * session.submit(...) 交完就立刻返回一张回执，活由框架在后台继续跑；
 * await 只是旁观等待，transcript 能读回已保存的整段对话。
 */
public class AgentSessionTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = AgentSessionTest.class.getClassLoader()
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
    @DisplayName("session 模式：submit 交了就走，await 等结果，transcript 读回对话")
    public void testSubmitRunsInBackground() throws IOException {

        Path ws = Path.of("./data/session-demo").toAbsolutePath().normalize();
        Files.createDirectories(ws.resolve("zhouwy"));
        Files.writeString(ws.resolve("zhouwy").resolve("note.txt"), "会话模式演示探针");

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("session-demo").agentId("session-demo")
                .sysPrompt("你是一个助手。需要知道文件内容时必须调用 read_file 工具，不要凭空猜。")
                .model(model)
                .workspace(ws)
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sSession-" + UUID.randomUUID()).build();

            try (var session = agent.session(ctx)) {
                System.out.println("【拿句柄】sessionId=" + session.sessionId()
                        + " | 此刻 transcript 已有 " + session.transcript().messages().size()
                        + " 条（拿句柄不会启动模型）");

                long t0 = System.currentTimeMillis();
                var task = session.submit("request-001", "读一下 note.txt，告诉我里面写了什么。");
                long submitMs = System.currentTimeMillis() - t0;
                System.out.println("【提交回执】" + submitMs + " ms 就返回了"
                        + " | turnId=" + task.turnId()
                        + " | runId=" + task.runId()
                        + " | status=" + task.status()
                        + " | settled=" + task.settled());

                var done = session.await(task).block(Duration.ofSeconds(180));
                System.out.println("【等待结果】从提交算起共 "
                        + (System.currentTimeMillis() - t0) + " ms"
                        + " | status=" + (done == null ? "超时/空" : done.status())
                        + " | error=" + (done == null ? "-" : done.error()));

                var transcript = session.transcript();
                List<Msg> msgs = transcript.messages();
                System.out.println("【对话记录】transcript 共 " + msgs.size()
                        + " 条 | asOfSeq=" + transcript.asOfSeq());
                for (int i = 0; i < msgs.size(); i++) {
                    System.out.println("    " + (i + 1) + ". " + describe(msgs.get(i)));
                }
                System.out.println("【执行错误】" + session.executionError());
            }
        }
    }

    /** 一条消息压成一行：角色 + 文字（首尾）+ 工具调用/结果的名字 */
    private static String describe(Msg m) {
        StringBuilder line = new StringBuilder("[").append(m.getRole()).append("]");
        for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
            line.append(" → ").append(u.getName()).append(' ')
                    .append(headTail(String.valueOf(u.getInput()), 60));
        }
        for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
            StringBuilder out = new StringBuilder();
            r.getOutput().forEach(b -> out.append(b.toString()));
            line.append(" ← ").append(r.getName()).append(' ')
                    .append(headTail(out.toString(), 60));
        }
        String text = m.getTextContent();
        if (text != null && !text.isBlank()) {
            line.append(' ').append(text.length()).append("字 | ").append(headTail(text, 60));
        }
        return line.toString();
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
