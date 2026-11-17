package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.model.ModelCreator;
import com.chinazhouwy.miniharness.model.ModelConfig;
import com.chinazhouwy.miniharness.interceptor.ModelHttpTraceInterceptor;
import com.openai.errors.OpenAIIoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@org.junit.jupiter.api.Tag("live")
public class HarnessCoreDemoTest {

    // 应用自己维护的一趟执行状态；不是 Spring AI 的内置状态，也不是会话记忆或检查点。
    static class RunState {
        final List<Message> messages = new ArrayList<>();
        int modelCalls;
        String answer;

        @Override
        public String toString() {
            return messages.toString() + ", modelCalls=" + modelCalls + ", answer=" + answer;
        }
    }

    @Test
    @DisplayName("执行状态：工具循环结束后读取完整消息与最终答案")
    void chatWithRunState() throws Exception {
        RunState state = new RunState();
        CallAdvisor recorder = new CallAdvisor() {
            public String getName() { return "run-state-recorder"; }
            public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER + 1; }

            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                state.modelCalls++;
                // 下一轮请求已包含上一轮工具请求与结果，因此覆盖快照，避免重复累加历史。
                state.messages.clear();
                state.messages.addAll(request.prompt().getInstructions());
                ChatClientResponse response = chain.nextCall(request);
                state.messages.add(response.chatResponse().getResult().getOutput());
                return response;
            }
        };
        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultAdvisors(ToolCallingAdvisor.builder().build(), recorder).build();
        state.answer = client.prompt()
                .user("请调用一次 ping 工具，根据返回结果用中文简短回答。")
                .tools(new CounterTool()).call().content();

        // 执行结束后，调用方仍然可以读取状态；Recorder 只观察，循环仍由 ToolCallingAdvisor 执行。
        System.out.println("[执行状态] 模型调用次数=" + state.modelCalls);
        for (Message message : state.messages) {
            Object content = message instanceof ToolResponseMessage tool ? tool.getResponses()
                    : message instanceof AssistantMessage assistant && assistant.hasToolCalls()
                    ? assistant.getToolCalls() : message.getText();
            System.out.println("[状态消息 " + message.getMessageType() + "] " + content);
        }
        System.out.println("[状态中的最终答案] " + state.answer);
    }

    @Test
    @DisplayName("模型请求超时：网络异常抛给调用方，不生成工具结果")
    void chatWithModelRequestTimeout() throws Exception {
        ModelConfig config = ModelConfig.loadProperties();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(config.apiKey()).baseUrl(config.baseUrl()).model(config.model())
                        .timeout(Duration.ofMillis(1)) // 故意设极短，方便观察超时；实际值按业务配置。
                        .maxRetries(0) // 关闭 SDK 网络重试，单独观察一次请求失败。
                        .build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(new ModelHttpTraceInterceptor()))
                .build();
        CounterTool tool = new CounterTool();
        try {
            String answer = ChatClient.create(model).prompt()
                    .user("请调用 ping 工具，根据结果回答。")
                    .tools(tool).call().content();
            System.out.println("[正常回答] " + answer);
        } catch (OpenAIIoException exception) {
            System.out.println("[模型请求失败] " + exception.getClass().getSimpleName());
            System.out.println("[底层原因] " + exception.getCause());
        }
        System.out.println("[工具执行次数] " + tool.calls);
    }

    public static class TimedReportTool {
        @Tool(name = "query_report", description = "查询报告，超时会返回失败信息")
        public String queryReport() throws Exception {
            // 应用自己给工具查询设置一秒时限；不是 Spring AI 的内置超时配置。
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var task = executor.submit(() -> new SlowReportTool().slowReport());
                try {
                    return task.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException exception) {
                    // 本例的 Thread.sleep 响应中断；真实数据库/HTTP 调用需核对取消行为。
                    task.cancel(true);
                    System.out.println("[工具超时] 将错误交给 Spring AI 工具异常处理器");
                    throw new IllegalStateException("报告查询超过一秒，查询失败，未取得报告内容");
                }
            }
        }
    }

    @Test
    @DisplayName("Agent 工具超时：错误回填给模型，由模型解释失败")
    void chatWithToolTimeout() throws Exception {
        String answer = ChatClient.create(new ModelCreator().getModel()).prompt()
                .system("请调用工具查询报告；如果工具返回失败，如实说明原因，不重试，不编造报告。")
                .user("请查询我的学习报告。")
                .tools(new TimedReportTool())
                .call().content();
        System.out.println("[Agent 最终回答] " + answer);
    }

    public static class SlowReportTool {
        @Tool(name = "slow_report", description = "模拟耗时三秒的报告查询")
        public String slowReport() throws InterruptedException {
            System.out.println(LocalTime.now() + " [工具] 开始查询");
            Thread.sleep(3000);
            System.out.println(LocalTime.now() + " [工具] 查询完成");
            return "报告已生成";
        }
    }

    @Test
    @DisplayName("等待工具超时：调用方超时后，工具仍继续执行")
    void toolWaitTimeoutDoesNotStopExecution() throws Exception {
        // 直接经过 Spring AI ToolCallback 调用工具，排除模型响应耗时的干扰。
        // 本例不请求模型，只观察 Java 工具执行与调用方等待的边界。
        var callback = ToolCallbacks.from(new SlowReportTool())[0];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> callback.call("{}"));
            try {
                System.out.println("[调用方结果] " + task.get(1, TimeUnit.SECONDS));
            } catch (TimeoutException exception) {
                System.out.println(LocalTime.now() + " [调用方] 等待一秒超时");
            }

            // get(timeout) 只结束这次等待，不会自动 cancel 或中断后台任务。
            // 为了在控制台观察后续完成，demo 再等待一次；业务代码不一定这样做。
            System.out.println(LocalTime.now() + " [调用方] 继续观察后台任务");
            String result = task.get(5, TimeUnit.SECONDS);
            System.out.println(LocalTime.now() + " [调用方] 稍后仍拿到结果：" + result);
        }
    }

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
