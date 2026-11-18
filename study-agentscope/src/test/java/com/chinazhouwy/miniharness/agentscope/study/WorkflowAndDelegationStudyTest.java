package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Function;

/** 两种调度方式的小对照：固定工作流由应用安排顺序，Agent-as-tool 由主管输出委派请求。 */
@Tag("live")
class WorkflowAndDelegationStudyTest {
    // 虚构业务资料，不访问真实工单系统；回答和委派决策使用真实模型。
    private static final String FACT = "工单 DEMO-001 当前状态是处理中，尚未完成。";

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

    /** 每次模型请求只打印用户材料，工具请求打印名称与参数；不逐个流式片段刷屏。 */
    private MiddlewareBase trace() {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                               Function<ModelCallInput, Flux<AgentEvent>> next) {
                return next.apply(input)
                        .doFirst(() -> input.messages().stream()
                        .filter(message -> message.getRole() == MsgRole.USER)
                        .forEach(message -> System.out.println("[发给" + agent.getName() + "] " + message.getTextContent())));
            }
            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                            Function<ActingInput, Flux<AgentEvent>> next) {
                return next.apply(input)
                        .doFirst(() -> input.toolCalls().forEach(call ->
                        System.out.println("[" + agent.getName() + "委派] " + call.getName() + " " + call.getInput())));
            }
        };
    }
}
