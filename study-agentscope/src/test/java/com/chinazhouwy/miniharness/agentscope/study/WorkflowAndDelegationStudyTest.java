package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 工作流小对照：应用固定顺序、并行后汇总，以及主管通过工具委派子 Agent。 */
@Tag("live")
class WorkflowAndDelegationStudyTest {
    // 虚构业务资料，不访问真实工单系统；回答和委派决策使用真实模型。
    private static final String FACT = "工单 DEMO-001 当前状态是处理中，尚未完成。";
    // 同一测试中父子 Agent 共用请求序号，按日志顺序阅读，不会因创建新实例又从 1 开始。
    private final AtomicInteger modelRequests = new AtomicInteger();

    /** 先整理、后复核；依赖关系直接由 Java 代码决定。 */
    @Test
    @DisplayName("顺序工作流：整理 Agent 输出，交给复核 Agent")
    void sequentialWorkflow() throws Exception {
        var model = model();
        try (var researcher = ReActAgent.builder()
                .name("整理助手")
                .model(model)
                .middleware(trace())
                .sysPrompt("请用一行中文整理输入中的工单编号和当前状态，不补充事实。")
                .build();
             var reviewer = reviewer(model)) {
            String facts = researcher.call(FACT).block(Duration.ofMinutes(2)).getTextContent();
            System.out.println("[步骤一：整理结果] " + facts);
            // 传递上一步的输出，不会自动把整理助手的整个聊天历史复制给复核助手。
            String result = reviewer.call("请判断能否宣布结案，依据如下：" + facts)
                    .block(Duration.ofMinutes(2)).getTextContent();
            System.out.println("[步骤二：复核结果] " + result);
        }
    }

    /** 两个独立任务并行执行；调度由应用中的 Reactor 安排，不是主管模型决定并发。 */
    @Test
    @DisplayName("并行工作流：复核与风险助手各自执行，结果齐后再汇总")
    void parallelThenSummarize() throws Exception {
        var model = model();
        try (
                var checker = reviewer(model);

                var risk = ReActAgent.builder().name("风险助手").model(model).middleware(trace())
                         .sysPrompt("只依据收到的工单事实，用一句中文说明提前宣布结案的风险，不补充事实。").build();

                var summary = ReActAgent.builder().name("汇总助手").model(model).middleware(trace())
                         .sysPrompt("根据收到的两份意见，用一句中文给出结论，不补充新事实。").build()
        ) {

            var checkTask = checker.call(FACT)
                    .doOnSubscribe(s -> System.out.println("[订阅复核任务] " + Thread.currentThread().getName()))
                    .doOnNext(reply -> System.out.println("[复核完成] " + reply.getTextContent()))
                    .subscribeOn(Schedulers.boundedElastic());

            var riskTask = risk.call(FACT)
                    .doOnSubscribe(s -> System.out.println("[订阅风险任务] " + Thread.currentThread().getName()))
                    .doOnNext(reply -> System.out.println("[风险分析完成] " + reply.getTextContent()))
                    .subscribeOn(Schedulers.boundedElastic());

            // call 返回 Mono 时还未完成执行；zip 订阅两条分支，等两个结果都到齐才向下游发出元组。
            // subscribeOn 为两条分支安排订阅线程；网络等待期间不代表一直占用这两个线程。
            var results = Mono.zip(checkTask, riskTask).block(Duration.ofMinutes(2));

            System.out.println("[汇合点] 两份结果已到齐，开始汇总");

            // T1/T2 按 zip 的参数顺序排列，不按谁先完成排列。
            String answer = summary.call("复核意见：" + results.getT1().getTextContent()
                    + "\n风险意见：" + results.getT2().getTextContent())
                    .block(Duration.ofMinutes(2)).getTextContent();

            System.out.println("[汇总结果] " + answer);
            // 一条分支失败，zip 默认报错并取消其他分支的订阅；不保证已经发生的外部操作会停止。
        }
    }

    /** 主管看到一个普通工具定义；该工具执行时由框架创建并调用子 Agent，结果作为工具结果返回。 */
    @Test
    @DisplayName("子 Agent 委派：主管请求 ask_checker，子 Agent 复核后返回")
    void delegateAsTool() throws Exception {
        var model = model();
        Toolkit toolkit = new Toolkit();
        toolkit.registration()
                .subAgent(
                        () -> reviewer(model),
                        SubAgentConfig.builder()
                                .toolName("ask_checker")
                                .description("委派工单复核助手，判断提供的事实是否支持结案")
                                .forwardEvents(false)
                                .build())
                .apply();
        try (var supervisor = ReActAgent.builder().name("主管").model(model).toolkit(toolkit).middleware(trace())
                .sysPrompt("请先调用 ask_checker 委派工单复核，将完整工单事实写入 message 参数，"
                        + "收到子助手结果后再用中文简短回答，不要自行代替复核。").build()) {
            String answer = supervisor.call(FACT + "请委派复核助手判断能否宣布结案。")
                    .block(Duration.ofMinutes(2)).getTextContent();
            System.out.println("[主管最终回答] " + answer);
        }
    }

    /** 主管通过真正的子 Agent 工具连续委派；第二次只传问题，用 session_id 恢复历史。 */
    @Test
    @DisplayName("子 Agent 多轮委派：携带 session_id，恢复独立会话历史")
    void delegateWithSession() throws Exception {
        var model = model();
        var children = new ArrayList<ReActAgent>();
        try {
            Toolkit toolkit = new Toolkit();
            toolkit.registration().subAgent(
                    () -> {
                            var child = reviewer(model);
                            children.add(child);
                            return child;
                            },
                    SubAgentConfig.builder()
                            .toolName("ask_checker")
                            .description("委派工单复核助手，可携带 session_id 继续此前会话")
                            // 默认也是内存存储；显式写出，方便看清 session_id 对应的历史存在哪里。
                            .stateStore(new InMemoryAgentStateStore())
                            .forwardEvents(false)
                            .build()
                    )
                    .apply();

            try (var supervisor = ReActAgent.builder()
                                        .name("主管")
                                        .model(model)
                                        .toolkit(toolkit)
                                        .middleware(trace())
                                        .sysPrompt("按顺序调用 ask_checker 两次，禁止合并或并行调用。"
                                                + "第一次不传 session_id，message 原文为："
                                                + "工单 DEMO-001 处理中，验收码 DEMO-7319。请记住验收码，本次只回复工单状态。"
                                                + "第二次必须提取第一次工具结果中的 session_id 原样传入，"
                                                + "message 只写：刚才的验收码是什么？ 不得重传验收码或补充资料。"
                                                + "收到第二次工具结果后用中文简短汇报。")
                                        .build()) {
                // 这句话只发给主管；子 Agent 的用户输入由主管实际生成的 message 决定。
                var answer = supervisor.call("内部备注：演示用途，不对外发布。请完成两次委派。")
                        .block(Duration.ofMinutes(2));
                System.out.println("[主管最终回答] " + answer.getTextContent());
            }
        } finally {
            // 注册时也会创建一个样例 Agent；一并关闭本例工厂创建的实例。
            for (var child : children) {
                child.close();
            }
        }
    }

    /** Java 只注册可选助手；由主管模型根据当前问题生成哪一个工具调用。 */
    @Test
    @DisplayName("模型选择子 Agent：工单问题与退款问题分别委派给不同助手")
    void routeToSpecialist() throws Exception {
        var model = model();
        var ticketTool = new TicketStatusTool();
        var refundTool = new RefundRuleTool();
        var children = new ArrayList<ReActAgent>();
        try {
            var toolkit = new Toolkit();

            toolkit.registration().subAgent(() -> specialist(model, "工单助手", ticketTool, children),
                    SubAgentConfig.builder().toolName("ask_ticket")
                            .description("查询演示工单 DEMO-001 的处理状态，不负责退款规则")
                            .forwardEvents(false).build()).apply();

            toolkit.registration().subAgent(() -> specialist(model, "退款助手", refundTool, children),
                    SubAgentConfig.builder().toolName("ask_refund")
                            .description("查询演示商店的退款到账规则，不负责工单状态")
                            .forwardEvents(false).build()).apply();

            try (var supervisor = ReActAgent.builder().name("主管").model(model).toolkit(toolkit)
                    .middleware(trace()).sysPrompt("根据当前用户问题选择一个最相关的子助手进行委派，"
                            + "不要调用不相关的助手。将问题写入 message，拿到结果后只回答当前问题，禁止猜测业务数据。")
                    .build()) {
                int questionNumber = 0;
                for (String question : new String[]{"DEMO-001 工单现在处理到哪了？", "演示商店退款多久到账？"}) {
                    System.out.println("\n【用户新问题】" + question);
                    // 两道独立问题使用不同主管会话，避免上一题资料影响下一题。
                    var context = RuntimeContext.builder().userId("demo-user").sessionId("routing-" + ++questionNumber).build();
                    var answer = supervisor.call(List.of(UserMessage.builder().textContent(question).build()), context).block(Duration.ofMinutes(2));
                    System.out.println("【本题最终回答】" + answer.getTextContent());
                    System.out.println("【累计 Java 工具执行次数】工单=" + ticketTool.calls + "，退款=" + refundTool.calls);
                }
            }
        } finally {
            for (var child : children) {
                child.close();
            }
        }
    }

    /** 每个子 Agent 只注册自己的 Java 工具；主管只能看见 ask_ticket、ask_refund。 */
    private ReActAgent specialist(OpenAIChatModel model, String name, Object dataTool, ArrayList<ReActAgent> children) {
        var toolkit = new Toolkit();
        toolkit.registerTool(dataTool);
        var child = ReActAgent.builder().name(name).model(model).toolkit(toolkit).middleware(trace())
                .sysPrompt("必须先调用自己的查询工具，再依据实际工具结果用一句中文回答，不编造事实。").build();
        children.add(child);
        return child;
    }

    /** 只读本地虚构工单，演示真实的 Java 方法执行，不访问业务服务。 */
    public static class TicketStatusTool {
        int calls;

        @Tool(name = "query_ticket_status", description = "查询虚构工单 DEMO-001 的处理状态")
        public String query() {
            calls++;
            System.out.println("【Java 工具执行】query_ticket_status");
            return "演示工单 DEMO-001 当前状态：处理中。";
        }
    }

    /** 只读本地虚构规则，与工单查询是两个不同工具，不接真实支付或退款系统。 */
    public static class RefundRuleTool {
        int calls;

        @Tool(name = "query_refund_rule", description = "查询演示商店的虚构退款到账规则")
        public String query() {
            calls++;
            System.out.println("【Java 工具执行】query_refund_rule");
            return "演示商店规则：退款审核通过后，3 个工作日内到账。";
        }
    }

    /** 共用模型客户端，不等于共用 Agent 的会话；本例子助手只有收到的委派内容，没有业务工具。 */
    private ReActAgent reviewer(OpenAIChatModel model) {
        return ReActAgent.builder().name("复核助手").model(model).middleware(trace())
                .sysPrompt("仅根据收到的工单事实，判断能否宣布结案，给出一句中文结论和依据。缺少事实就明确说明。").build();
    }

    private OpenAIChatModel model() throws Exception {
        var config = ModelConfig.loadProperties();
        return OpenAIChatModel.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl())
                .modelName(config.model()).nativeStructuredOutput(false).stream(true).build();
    }

    /** 阅读用摘要：主管历史只报条数，子 Agent 展示历史与本次输入；不是完整 HTTP 报文。 */
    private MiddlewareBase trace() {
        return new MiddlewareBase() {
            private final AtomicInteger toolCalls = new AtomicInteger();

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                               Function<ModelCallInput, Flux<AgentEvent>> next) {
                return next.apply(input)
                        .doFirst(() -> {
                            var messages = input.messages().stream()
                                    .filter(message -> message.getRole() != MsgRole.SYSTEM)
                                    .toList();
                            var log = new StringBuilder("\n========== ").append(agent.getName())
                                    .append(" · 全程模型请求 #").append(modelRequests.incrementAndGet())
                                    .append(" ==========\n");
                            if (!"主管".equals(agent.getName())) {
                                log.append("实例 ID：").append(agent.getAgentId()).append('\n')
                                        .append("会话 ID：").append(context.getSessionId() == null
                                                ? "未显式指定" : context.getSessionId()).append('\n');
                            }
                            int historySize = Math.max(0, messages.size() - 1);
                            log.append("历史消息：").append(historySize)
                                    .append(" 条（随本轮请求发送，不代表再次执行）\n");
                            // 主管历史不重复展开；子 Agent 历史展开，专门观察 session_id 的恢复效果。
                            if (!"主管".equals(agent.getName())) {
                                for (int i = 0; i < historySize; i++) {
                                    var message = messages.get(i);
                                    log.append("  [历史 · ").append(message.getRole()).append("] ")
                                            .append(message.getTextContent().replace("\n", "\n    ")).append('\n');
                                }
                            }
                            if (!messages.isEmpty()) {
                                var latest = messages.getLast();
                                var results = latest.getContentBlocks(ToolResultBlock.class);
                                if (results.isEmpty()) {
                                    log.append("本次输入：").append(latest.getTextContent()).append('\n');
                                } else {
                                    // 只打印最后一条消息里的新结果，不再把此前结果叫作“收到”。
                                    results.forEach(result -> {
                                        log.append("本轮回填的工具结果（").append(result.getName()).append("）：\n");
                                        result.getOutput().stream().filter(TextBlock.class::isInstance)
                                                .map(TextBlock.class::cast).forEach(text -> log.append("  ")
                                                        .append(text.getText().replace("\n", "\n  ")).append('\n'));
                                    });
                                }
                            }
                            System.out.print(log);
                        });
            }
            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                            Function<ActingInput, Flux<AgentEvent>> next) {
                return next.apply(input)
                        .doFirst(() -> input.toolCalls().forEach(call -> {
                            // 本例 ask_* 是子 Agent 工具，其余是子 Agent 自己执行的业务查询工具。
                            boolean delegation = call.getName().startsWith("ask_");
                            String heading = "\n---------- " + agent.getName() + (delegation ? " · 委派 #" : " · 调用工具 #")
                                    + toolCalls.incrementAndGet() + " → " + call.getName() + " ----------\n";
                            if (delegation) {
                                var sessionId = call.getInput().get("session_id");
                                System.out.println(heading + "message：" + call.getInput().get("message") + "\n"
                                        + "session_id：" + (sessionId == null ? "未传（新建子会话）" : sessionId));
                            } else {
                                System.out.println(heading + "入参：" + call.getInput());
                            }
                        }));
            }
        };
    }
}
