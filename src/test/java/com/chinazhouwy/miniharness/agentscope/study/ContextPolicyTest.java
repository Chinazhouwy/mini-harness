package com.chinazhouwy.miniharness.agentscope.study;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextManifest;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中文文档「上下文管理」→「检查模型实际拿到了什么」。
 *
 * <p>只演示一行 builder.contextPolicy(new ContextPolicy(...)) 带来的效果：
 * 每准备一次模型请求，框架回调一次，交出一份「上下文构建清单」。
 * 清单只有元数据（用了多少预算、留了哪些材料、做过什么裁剪），没有提示词正文。
 */
public class ContextPolicyTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = ContextPolicyTest.class.getClassLoader()
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
    @DisplayName("contextPolicy：每轮请求各生成一份上下文构建清单")
    public void testContextPolicyObserver() throws IOException {

        Path ws = Path.of("./data/policy-demo").toAbsolutePath().normalize();
        Files.createDirectories(ws.resolve("zhouwy"));
        Files.writeString(ws.resolve("zhouwy").resolve("note.txt"), "上下文清单演示探针");

        AtomicInteger seq = new AtomicInteger();

        // 文档原样：三个预算 + 估算器 + 观察回调
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("policy-demo").agentId("policy-demo")
                .sysPrompt("你是一个助手。需要知道文件内容时必须调用 read_file 工具，不要凭空猜。")
                .model(model)
                .workspace(ws)
                .contextPolicy(new ContextPolicy(
                        24000,   // 最大输入预算
                        4096,    // 为模型输出预留
                        1024,    // 安全余量
                        ContextTokenEstimator.approximate(),
                        manifest -> printManifest(seq.incrementAndGet(), manifest)))
                .build()) {

            RuntimeContext rc = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sPolicy-" + UUID.randomUUID()).build();
            agent.call(List.of(new UserMessage("读一下 note.txt，告诉我里面写了什么。")), rc).block();
        }

        System.out.println("\n===== 这一趟共回调 " + seq.get() + " 次，即准备了 " + seq.get()
                + " 轮模型请求 =====");
    }

    /** 清单只含元数据，逐项摊开；材料太多时只列前几条，避免刷屏。 */
    private static void printManifest(int seq, ContextManifest m) {
        System.out.println("\n===== 第 " + seq + " 份上下文构建清单 =====");
        System.out.println("  callId=" + m.callId()
                + " | purpose=" + m.purpose()
                + " | validation=" + m.validation());
        System.out.println("  model=" + m.model()
                + " | 估算 token=" + m.estimatedInputTokens() + " / 上限=" + m.inputLimit()
                + " | 计数方式=" + m.countingMethod() + " | 精确=" + m.exact());
        System.out.println("  policyVersion=" + m.policyVersion()
                + " | taskRevision=" + m.taskRevision());

        List<ContextManifest.Item> items = m.items();
        System.out.println("  【保留的材料】共 " + items.size() + " 条"
                + (items.size() > 6 ? "，只列前 6 条" : ""));
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (ContextManifest.Item it : items) {
            byType.merge(it.sourceType(), 1, Integer::sum);
        }
        System.out.println("  【按类别数】" + byType);
        for (int i = 0; i < Math.min(6, items.size()); i++) {
            ContextManifest.Item it = items.get(i);
            System.out.println("    " + (i + 1) + ". type=" + it.sourceType()
                    + " | id=" + it.sourceId()
                    + " | placement=" + it.placement()
                    + " | required=" + it.required()
                    + " | priority=" + it.priority()
                    + " | revision=" + it.revision());
        }

        List<String> changes = m.transforms();
        System.out.println("  【处理记录】共 " + changes.size() + " 条"
                + (changes.isEmpty() ? "（没裁剪过）" : ""));
        for (int i = 0; i < Math.min(3, changes.size()); i++) {
            System.out.println("    " + headTail(changes.get(i), 80));
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
