package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextBlock;
import io.agentscope.harness.agent.context.ContextSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * contextSource 最小可运行 demo —— 不联网、不需要 API Key。
 *
 * <p>一句话：你写一个「查余票的函数」，框架在<b>每次要问模型之前</b>都跑一遍它，
 * 把查到的数字临时贴进这次请求，贴完就扔，不写进聊天记录。
 *
 * <p>本 demo 用「卖票」讲清三件事：
 * <ol>
 *   <li>第 1 次问模型前，注入「余票数=2」；模型调 buy(1)，数据库变成 1。</li>
 *   <li>第 2 次问模型前，框架<b>重新</b>跑一次查票函数，注入「余票数=1」——数字变了。</li>
 *   <li>跑完后看会话历史：两行「余票数」都不在里面，证明它是临时的、没被保存。</li>
 * </ol>
 *
 * <p>运行：{@code mvn -o -q test -Dtest=ContextSourceDemoTest}
 */
public class ContextSourceDemoTest {

    /** 假装这是后台数据库：当前余票。 */
    static final AtomicInteger TICKETS = new AtomicInteger(2);

    /** 记录 contextSource 每次被调用的时间点与查到的值。 */
    static final List<String> LOAD_LOG = Collections.synchronizedList(new ArrayList<>());

    /** 从注入文本里抠出「余票数=N」的 N。 */
    static final Pattern STOCK = Pattern.compile("余票数=(\\d+)");

    @Test
    @DisplayName("contextSource：每步重新查一次，临时贴上去，不写进历史")
    void demo() {
        TICKETS.set(2);
        LOAD_LOG.clear();

        // ================= 1. 你唯一要写的东西：一个「查数据」的函数 =================
        // 返回值就是几行字；每行一个 ContextBlock。
        // ContextBlock.runtime(id, 内容) = 「临时状态」那一类，会被贴成一条临时 USER 消息。
        ContextSource 余票来源 = request -> {
            int n = TICKETS.get();
            LOAD_LOG.add("第 " + (LOAD_LOG.size() + 1) + " 次 load：余票数=" + n);
            return Mono.just(List.of(ContextBlock.runtime("ticket-stock", "余票数=" + n)));
        };

        // ================= 2. 定死的假模型（不联网，只按剧本走） =================
        // 关键点：第 2 轮回答时，它只读「本次注入的余票数」，不读工具结果、不读历史。
        // 所以如果框架没有重新注入，它就会答错。
        Model 假模型 = new Model() {
            final AtomicInteger round = new AtomicInteger();
            final List<String> 收到的请求 = new ArrayList<>();

            @Override
            public String getModelName() {
                return "定死的假模型";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                int r = round.incrementAndGet();
                String dump = messages.stream()
                        .map(m -> m.getRole() + " | " + m.getTextContent())
                        .collect(Collectors.joining("\n"));
                收到的请求.add(dump);

                System.out.println();
                System.out.println("========== 第 " + r + " 次「问模型之前」，框架把下面这些发给模型 ==========");
                for (Msg m : messages) {
                    System.out.println("  [" + m.getRole() + "] "
                            + m.getTextContent().replace("\n", " ⏎ "));
                }
                System.out.println("  ↑ 末尾那条带「余票数=」的就是 contextSource 临时贴的");

                Integer seen = 抠余票(dump);
                if (r == 1) {
                    System.out.println("  → 模型看到「余票数=" + seen + "」，决定调用工具 buy(1)");
                    ToolUseBlock use = ToolUseBlock.builder()
                            .id("call-buy-1")
                            .name("buy")
                            .input(Map.<String, Object>of("count", 1))
                            .content("{\"count\":1}")
                            .build();
                    return Flux.just(ChatResponse.builder().content(List.of(use)).build());
                }

                System.out.println("  → 模型只读本次注入的「余票数=" + seen + "」，据此回答");
                String answer = "买好了。按这次看到的最新余票，还剩 " + seen + " 张。";
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(answer).build()))
                        .build());
            }
        };

        // ================= 3. 工具：买票，会把数据库里的余票真的减掉 =================
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new 售票工具());

        // ================= 4. 把上面的东西装进真正的 HarnessAgent =================
        HarnessAgent agent = HarnessAgent.builder()
                .name("售票助手").agentId("ticket-demo")
                .sysPrompt("你是售票助手，用中文回答。")
                .model(假模型)
                .toolkit(toolkit)
                .contextSource("ticket-stock", 余票来源)   // ← 就是这一行
                // 下面这些只是把本 demo 用不到的预装功能关掉，减少噪音
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableDefaultWorkspaceSkills()
                .disableTranscript()
                .build();

        RuntimeContext rc = RuntimeContext.builder()
                .userId("u1").sessionId("s-demo-" + System.nanoTime()).build();

        try {
            System.out.println();
            System.out.println("############ 用户说：帮我买 1 张，告诉我还剩几张 ############");
            System.out.println("############ 数据库初始余票 = " + TICKETS.get() + " ############");

            Msg reply = agent.call(List.of(new UserMessage("帮我买 1 张，告诉我还剩几张")), rc)
                    .block(Duration.ofSeconds(20));

            System.out.println();
            System.out.println("========== 最终回答 ==========");
            System.out.println("  " + (reply == null ? "(null)" : reply.getTextContent()));

            // ================= 5. 取证：contextSource 被调了几次、每次查到什么 =================
            System.out.println();
            System.out.println("========== 证据 1：查票函数被调用了 " + LOAD_LOG.size() + " 次 ==========");
            LOAD_LOG.forEach(line -> System.out.println("  " + line));
            System.out.println("  （一次在买票前，一次在买票后 —— 所以数字才会从 2 变 1）");

            // ================= 6. 取证：那两行「余票数」没进会话历史 =================
            List<Msg> history = agent.getDelegate().getAgentState(rc).getContext();
            boolean 历史里有余票 = history.stream()
                    .anyMatch(m -> m.getTextContent() != null && m.getTextContent().contains("余票数="));

            System.out.println();
            System.out.println("========== 证据 2：保存下来的会话历史（共 " + history.size() + " 条）==========");
            for (Msg m : history) {
                System.out.println("  [" + m.getRole() + "] " + 摘要(m));
            }
            System.out.println("  历史里含「余票数=」吗？ " + 历史里有余票 + "   ← 必须是 false");
        } finally {
            agent.close();
        }
    }

    /** 真的会改数据库的买票工具。 */
    public static class 售票工具 {
        @Tool(name = "buy", description = "买票")
        public String buy(@ToolParam(name = "count", description = "买几张") int count) {
            int before = TICKETS.getAndAdd(-count);
            int after = before - count;
            System.out.println();
            System.out.println("  ⚙ 工具执行：buy(" + count + ")，数据库 " + before + " → " + after);
            return "出票成功 " + count + " 张。";
        }
    }

    /** 从消息 dump 里抠出「余票数=N」的 N；抠不到返回 null。 */
    private static Integer 抠余票(String dump) {
        Matcher m = STOCK.matcher(dump);
        Integer last = null;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    /** 打印一条消息的简摘要：有文字就显示文字，没有就显示它是什么类型的块。 */
    private static String 摘要(Msg m) {
        String text = m.getTextContent();
        if (text != null && !text.isBlank()) {
            return text.length() > 90 ? text.substring(0, 90) + "…" : text;
        }
        if (m.getContent() == null || m.getContent().isEmpty()) {
            return "（空消息）";
        }
        return "（" + m.getContent().stream()
                .map(b -> b.getClass().getSimpleName())
                .collect(Collectors.joining(", ")) + "）";
    }
}
