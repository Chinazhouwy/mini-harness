package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 三框架同题：Toolkit 注册工具，由 ReActAgent 执行内部循环，最后读取会话状态。 */
@Tag("live")
class FrameworkComparisonStudyTest {
    @Test
    @DisplayName("三框架同题 · AgentScope：查工单、查规则，再核对建议与核验码")
    void compareTicketTask() throws Exception {
        var config = ModelConfig.loadProperties();
        var model = OpenAIChatModel.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl())
                .modelName(config.model()).nativeStructuredOutput(false).stream(true).build();
        for (String status : List.of("处理中", "已完成")) {
            var tools = new ComparisonTicketTools(status);
            var toolkit = new Toolkit();
            toolkit.registerTool(tools);
            var context = RuntimeContext.builder().userId("demo-user").sessionId(UUID.randomUUID().toString()).build();
            try (var agent = ReActAgent.builder().name("ticket-comparison").model(model).toolkit(toolkit)
                    .sysPrompt("先调用 query_ticket 查询工单，再把查询返回的状态原样传给"
                            + " query_handling_rule。收到两份结果后简短回答，原样保留工单号、状态、建议和核验码。不要猜测。")
                    .build()) {
                String answer = agent.call("请查询 DEMO-001，告诉我当前状态和下一步建议，并附核验码。", context)
                        .block(Duration.ofMinutes(2)).getTextContent();
                var messages = agent.getAgentState(context.getUserId(), context.getSessionId()).getContext();
                System.out.println("\n【AgentScope · 完整消息链 · " + status + "】");
                for (var message : messages) {
                    if (!message.getTextContent().isBlank()) {
                        System.out.println("[" + message.getRole() + "] " + message.getTextContent());
                    }
                    for (var call : message.getContentBlocks(ToolUseBlock.class)) {
                        System.out.println("[工具请求] id=" + call.getId() + " name=" + call.getName() + " arguments=" + call.getInput());
                    }
                    for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                        String text = result.getOutput().stream().filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast).map(TextBlock::getText).collect(Collectors.joining("\n"));
                        System.out.println("[工具结果] id=" + result.getId() + " name=" + result.getName() + " output=" + text);
                    }
                }
                long replies = messages.stream().filter(message -> message.getRole() == MsgRole.ASSISTANT).count();
                System.out.println("【应用核对】助手消息=" + replies + "，查工单=" + tools.ticketCalls + "，查规则=" + tools.ruleCalls
                        + "，规则参数匹配=" + status.equals(tools.ruleStatus) + "，核验码保留=" + answer.contains(tools.code)
                        + "，建议匹配=" + answer.contains(tools.action));
            }
        }
    }

    /** 本地虚构数据；与另外两个模块保持同题逻辑，只替换框架的工具注解。 */
    public static class ComparisonTicketTools {
        final String status;
        final String action;
        final String code = "CHECK-" + UUID.randomUUID();
        int ticketCalls;
        int ruleCalls;
        String ruleStatus;

        ComparisonTicketTools(String status) {
            this.status = status;
            this.action = status.equals("处理中") ? "继续跟进" : "可以结案";
        }

        @Tool(name = "query_ticket", description = "查询演示工单，取得实际状态和核验码")
        public String queryTicket(@ToolParam(name = "ticketId", description = "工单编号，当前只支持 DEMO-001") String ticketId) {
            ticketCalls++;
            if (!"DEMO-001".equals(ticketId)) throw new IllegalArgumentException("未知演示工单");
            String result = "工单=" + ticketId + "；状态=" + status + "；核验码=" + code;
            System.out.println("【Java query_ticket】" + result);
            return result;
        }

        @Tool(name = "query_handling_rule", description = "按查询到的实际工单状态，查询下一步处理建议")
        public String queryRule(@ToolParam(name = "status", description = "query_ticket 返回的状态，必须原样传入") String status) {
            ruleCalls++;
            if (!this.status.equals(status)) throw new IllegalArgumentException("请使用工单查询返回的实际状态");
            ruleStatus = status;
            System.out.println("【Java query_handling_rule】状态=" + status + "；建议=" + action);
            return "状态=" + status + "；建议=" + action;
        }
    }
}
