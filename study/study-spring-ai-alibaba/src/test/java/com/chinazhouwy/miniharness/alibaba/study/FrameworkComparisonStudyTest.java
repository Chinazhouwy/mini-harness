package com.chinazhouwy.miniharness.alibaba.study;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.chinazhouwy.miniharness.model.ModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.UUID;

/** 三框架同题：Alibaba 使用 Spring AI 1.1.2 的模型和工具适配，循环由 ReactAgent 的图驱动。 */
@Tag("live")
class FrameworkComparisonStudyTest {
    @Test
    @DisplayName("三框架同题 · Alibaba：查工单、查规则，再核对建议与核验码")
    void compareTicketTask() throws Exception {
        var config = ModelConfig.loadProperties();
        var model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl()).build())
                .defaultOptions(OpenAiChatOptions.builder().model(config.model()).build()).build();
        for (String status : List.of("处理中", "已完成")) {
            var tools = new ComparisonTicketTools(status);
            var agent = ReactAgent.builder().name("ticket-comparison").model(model)
                    .systemPrompt("先调用 query_ticket 查询工单，再把查询返回的状态原样传给"
                            + " query_handling_rule。收到两份结果后简短回答，原样保留工单号、状态、建议和核验码。不要猜测。")
                    .tools(ToolCallbacks.from(tools)).build();
            // invoke 执行完整工具循环，返回的是图状态，不是只请求模型一次。
            var state = agent.invoke("请查询 DEMO-001，告诉我当前状态和下一步建议，并附核验码。").orElseThrow();
            List<Message> messages = state.<List<Message>>value("messages").orElseThrow();
            System.out.println("\n【Alibaba · 完整消息链 · " + status + "】");
            for (Message message : messages) {
                Object content = message instanceof ToolResponseMessage result ? result.getResponses()
                        : message instanceof AssistantMessage assistant && assistant.hasToolCalls()
                        ? assistant.getToolCalls() : message.getText();
                System.out.println("[" + message.getMessageType() + "] " + content);
            }
            String answer = messages.getLast().getText();
            long replies = messages.stream().filter(AssistantMessage.class::isInstance).count();
            System.out.println("【应用核对】助手消息=" + replies + "，查工单=" + tools.ticketCalls + "，查规则=" + tools.ruleCalls
                    + "，规则参数匹配=" + status.equals(tools.ruleStatus) + "，核验码保留=" + answer.contains(tools.code)
                    + "，建议匹配=" + answer.contains(tools.action));
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
        public String queryTicket(@ToolParam(description = "工单编号，当前只支持 DEMO-001") String ticketId) {
            ticketCalls++;
            if (!"DEMO-001".equals(ticketId)) throw new IllegalArgumentException("未知演示工单");
            String result = "工单=" + ticketId + "；状态=" + status + "；核验码=" + code;
            System.out.println("【Java query_ticket】" + result);
            return result;
        }

        @Tool(name = "query_handling_rule", description = "按查询到的实际工单状态，查询下一步处理建议")
        public String queryRule(@ToolParam(description = "query_ticket 返回的状态，必须原样传入") String status) {
            ruleCalls++;
            if (!this.status.equals(status)) throw new IllegalArgumentException("请使用工单查询返回的实际状态");
            ruleStatus = status;
            System.out.println("【Java query_handling_rule】状态=" + status + "；建议=" + action);
            return "状态=" + status + "；建议=" + action;
        }
    }
}
