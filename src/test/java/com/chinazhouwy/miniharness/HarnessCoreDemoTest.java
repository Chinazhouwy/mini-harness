package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.model.ModelCreator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;

public class HarnessCoreDemoTest {

    public static class CounterTool {
        private int calls;

        @Tool(name = "ping", description = "执行一次连通性检查，返回 pong")
        public String ping() {
            calls++;
            return "pong";
        }
    }


    public static class TransientReportTool {
        private int calls;

        @Tool(name = "load_report", description = "读取学习报告；临时故障时可以重试一次")
        public String loadReport() {
            System.out.println("[工具执行] 第 " + (++calls) + " 次");
            // 模拟临时故障和成功结果，不连接真实报告服务。
            if (calls == 1) {
                throw new IllegalStateException("报告服务临时繁忙，请重试一次");
            }
            return "学习报告：今天完成了 Java 并发复习。";
        }
    }

    @Test
    @DisplayName("工具临时失败后，模型决定重试一次")
    void chatWithModelDecidedToolRetry() throws Exception {
        var manager = ToolCallingManager.builder().maxCallsPerTool("load_report", 2).build();
        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                .build();
        TransientReportTool tool = new TransientReportTool();
        String answer = client.prompt()
                .system("请调用工具读取报告。若返回临时故障，请再调用一次；仍失败就说明失败，不再重试。")
                .user("请读取我的学习报告，并用中文简短回答。")
                .tools(tool).call().content();
        System.out.println("[最终回答] " + answer);
        System.out.println("[工具执行次数] " + tool.calls);
    }

    @Test
    @DisplayName("模型请求预算：第二轮请求发出前终止")
    void chatWithModelCallLimit() throws Exception {
        // 教学用：Advisor 仅供下面这一趟请求使用，计数器不用于共享单例。
        CallAdvisor budget = new CallAdvisor() {
            private int modelCalls;

            public String getName() { return "model-call-budget-demo"; }
            public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER + 1; }

            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                if (modelCalls >= 1) {
                    throw new IllegalStateException("模型请求预算耗尽：最多允许请求模型 1 次");
                }
                System.out.println("[允许模型请求] 第 " + (++modelCalls) + " 次");
                return chain.nextCall(request);
            }
        };
        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultAdvisors(ToolCallingAdvisor.builder().build(), budget).build();
        CounterTool tool = new CounterTool();
        try {
            System.out.println(client.prompt().user("请调用 ping 工具，根据返回结果简短回答。")
                    .tools(tool).call().content());
        } catch (IllegalStateException exception) {
            System.out.println("[执行终止] " + exception.getMessage());
        }
        // 第一轮返回工具请求后，工具仍会执行；准备第二轮模型请求时才触发此预算。
        // 这是模型请求预算，不等于工具权限或工具执行预算。
        System.out.println("[工具实际执行次数] " + tool.calls);
    }

    @Test
    @DisplayName("工具执行预算：达到总次数上限后终止")
    void chatWithToolCallLimit() throws Exception {
        // 限制本趟用户请求的工具总次数，不是模型请求次数，也不是对象终生调用次数。
        var manager = ToolCallingManager.builder()
                .maxTotalToolCalls(1)
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                .build();
        CounterTool tool = new CounterTool();
        var response = client.prompt()
                .user("请分两步调用 ping：第一次得到结果后，再调用一次 ping，最后汇报两次结果。")
                .tools(tool).call().chatResponse();

        // Manager 抛出的限额异常会被 ToolCallingAdvisor 捕获，转换成终止响应。
        System.out.println("[工具实际执行次数] " + tool.calls);
        System.out.println("[结束原因] " + response.getResult().getMetadata().getFinishReason());
        System.out.println("[返回内容] " + response.getResult().getOutput().getText());
    }

}
