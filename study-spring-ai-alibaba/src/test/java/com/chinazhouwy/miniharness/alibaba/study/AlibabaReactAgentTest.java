package com.chinazhouwy.miniharness.alibaba.study;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.chinazhouwy.miniharness.model.ModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/** 本模块使用 Spring AI 1.1.2，与 Spring AI 2.0.2 学习模块分别编译和运行。 */
class AlibabaReactAgentTest {

    /**
     * 区分定义图、编译图和执行图三个阶段。
     * <p>关键点：initGraph 只注册节点与边；compile 做检查与装配；invoke 才按条件路由执行节点并合并状态。
     * <p>观察：构图后执行记录为空，执行顺序应是 model/tool/model，最终累积四条消息。
     */
    @Test
    @DisplayName("initGraph：构图不执行，invoke 后按条件边驱动模型与工具循环")
    void initGraphDefinesToolLoop() throws Exception {
        List<String> executions = new ArrayList<>();
        StateGraph graph = initGraph(executions);
        System.out.println("[构图后] 节点执行记录=" + executions);
        // compile 校验并装配执行图；真正触发节点动作的是后面的 invoke。
        var compiled = graph.compile();
        assertTrue(executions.isEmpty(), "构图和编译阶段均不执行节点");

        var result = compiled.invoke(Map.of("messages", List.of(new UserMessage("请检查连通性"))))
                .orElseThrow();
        List<Message> messages = result.<List<Message>>value("messages").orElseThrow();
        System.out.println("[执行后] 节点顺序=" + executions + "，消息数=" + messages.size());
        System.out.println("[最终回答] " + messages.getLast().getText());
        assertEquals(List.of("model", "tool", "model"), executions);
        assertEquals(4, messages.size());
    }

    /** 仿照 ReactAgent.initGraph 的关键结构手动搭图，省略 Hook、检查点等其他机制。 */
    private StateGraph initGraph(List<String> executions) throws Exception {
        // KeyStrategyFactory 给每个字段配置更新策略；messages 用 AppendStrategy，把节点返回的单条增量并入历史。
        StateGraph graph = new StateGraph(() -> Map.of("messages", new AppendStrategy()));
        var ping = ToolCallbacks.from(new PingTool())[0];

        // 模型用固定响应模拟：第一次请求工具，收到工具结果后给出答案，不访问模型服务。
        // addNode 注册动作，不立即执行；node_async 将同步动作适配为图节点接口，不保证每个节点独占线程。
        graph.addNode("model", node_async(state -> {
            executions.add("model");
            List<Message> messages = state.<List<Message>>value("messages").orElseThrow();
            AssistantMessage output = messages.getLast() instanceof ToolResponseMessage
                    ? new AssistantMessage("连通正常")
                    : AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("call-ping", "function", "ping", "{\"name\":\"study\"}")))
                            .build();
            System.out.println("[模型节点] 输入消息数=" + messages.size() + "，输出="
                    + (output.hasToolCalls() ? "请求 ping" : output.getText()));
            // 返回本次增量，框架按 AppendStrategy 追加到 messages。
            return Map.of("messages", output);
        }));
        graph.addNode("tool", node_async(state -> {
            executions.add("tool");
            List<Message> messages = state.<List<Message>>value("messages").orElseThrow();
            var call = ((AssistantMessage) messages.getLast()).getToolCalls().getFirst();
            // 模型返回 arguments 的 JSON，ToolCallback 负责反序列化参数并调用 Java 方法；id/name 用来配对工具结果。
            String output = ping.call(call.arguments()); // 实际执行 Java 工具及参数转换。
            System.out.println("[工具节点] 参数=" + call.arguments() + "，结果=" + output);
            return Map.of("messages", ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), output))).build());
        }));
        graph.addEdge(START, "model");
        // 第一参数是出发节点；路由函数返回标识，最后的 Map 把标识映射为实际目的节点。
        graph.addConditionalEdges("model", edge_async(state -> {
            List<Message> messages = state.<List<Message>>value("messages").orElseThrow();
            String next = ((AssistantMessage) messages.getLast()).hasToolCalls() ? "tool" : END;
            System.out.println("[条件路由] 下一节点=" + next);
            return next;
        }), Map.of("tool", "tool", END, END));
        // 固定边形成回路：工具结束后回到模型，模型再根据最新状态决定继续还是结束，不必手写 while。
        graph.addEdge("tool", "model");
        return graph;
    }

    /**
     * 观察 Graph 检查点与会话 ID 的关联。
     * <p>关键点：新 Agent 共享同一个 MemorySaver，再用相同 threadId 取回状态；threadId 与 Java 线程无关。
     * <p>观察：A 延续任务，B 没有 A 的历史；检查点包含工具结果，但 MemorySaver 随进程退出而丢失。
     */
    @Test
    @Tag("live")
    @DisplayName("Alibaba 会话状态：重建 Agent 后按 threadId 接续，检查点保留工具消息")
    void chatWithSessionCheckpoint() throws Exception {
        ModelConfig config = ModelConfig.loadProperties();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl()).build())
                .defaultOptions(OpenAiChatOptions.builder().model(config.model()).build()).build();
        // Saver 保存 Graph 检查点；MemorySaver 只在内存中保存，本例不演示跨进程恢复。
        // Saver 保存图检查点及状态；这里是 Java 内存对象，新 Agent 必须复用它才能读到之前的数据。
        MemorySaver saver = new MemorySaver();
        // threadId 是会话标识，与 Java 的线程 ID 无关。
        RunnableConfig sessionA = RunnableConfig.builder().threadId("session-A").build();
        RunnableConfig sessionB = RunnableConfig.builder().threadId("session-B").build();
        String system = "用中文简短回答，只依据当前会话记录；没有相关记录就明确说不知道。";

        ReactAgent firstAgent = ReactAgent.builder().name("session-study").model(model)
                .systemPrompt(system).tools(ToolCallbacks.from(new PingTool())).saver(saver).build();
        System.out.println("[A 第一次聊天] " + firstAgent.call(
                "我是 study，学习任务是阅读 ToolCallingAdvisor 源码。请先调用一次 ping 检查连通性，再确认记住任务。",
                sessionA).getText());

        // get 以 RunnableConfig 的 threadId 找到检查点；getState 是保存的数据 Map，messages 是其中一个字段。
        List<?> savedMessages = (List<?>) saver.get(sessionA).orElseThrow().getState().get("messages");
        System.out.println("[A 检查点] 消息数=" + savedMessages.size() + "，工具结果数="
                + savedMessages.stream().filter(ToolResponseMessage.class::isInstance).count());

        // 新 Agent 复用同一个 Saver；沿用 A 的 ID 才能读到 A 的状态。
        // 工具在新 Agent 上重新注册，历史来自 Saver；复用 ChatModel 本身不是会话记忆机制。
        ReactAgent restoredAgent = ReactAgent.builder().name("session-study").model(model)
                .systemPrompt(system).tools(ToolCallbacks.from(new PingTool())).saver(saver).build();
        System.out.println("[A 重建后聊天] " + restoredAgent.call("我刚才的学习任务是什么？", sessionA).getText());
        System.out.println("[B 新会话聊天] " + restoredAgent.call("我刚才的学习任务是什么？", sessionB).getText());
        System.out.println("[B 检查点消息] " + saver.get(sessionB).orElseThrow().getState().get("messages"));
    }

    public static class PingTool {
        int calls;

        @Tool(description = "执行连通性检查，返回 pong 与调用方名称")
        public String ping(@ToolParam(description = "调用方名称") String name) {
            calls++;
            return "pong: " + name;
        }
    }

    /**
     * 离线验证 ReactAgent 的真实 Graph 工具循环。
     * <p>关键点：仅模型输出固定；工具参数 Schema、方法执行、路由和 OverAllState 均由当前框架处理。
     * <p>观察：观察两轮模型、一次带参数工具以及 messages 中的 ToolResponseMessage。
     */
    @Test
    @DisplayName("Alibaba 离线验证：Graph 执行两轮模型、一次带参数工具并返回消息状态")
    void scriptedToolLoopReturnsState() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        PingTool tool = new PingTool();
        // 只替换模型服务；ReactAgent、参数 Schema、工具执行和状态累积均走真实框架。
        ChatModel model = prompt -> {
            AssistantMessage output = modelCalls.incrementAndGet() == 1
                    ? AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("call-ping", "function", "ping", "{\"name\":\"study\"}")))
                            .build()
                    : new AssistantMessage("连通正常");
            return new ChatResponse(List.of(new Generation(output)));
        };
        ReactAgent agent = ReactAgent.builder().name("alibaba-state-study")
                .model(model).tools(ToolCallbacks.from(tool)).build();
        var state = agent.invoke("请检查连通性").orElseThrow();
        List<?> messages = (List<?>) state.value("messages").orElseThrow();
        messages.forEach(message -> System.out.println("[Alibaba 状态消息] " + message));
        assertEquals(2, modelCalls.get());
        assertEquals(1, tool.calls);
        assertTrue(messages.stream().anyMatch(ToolResponseMessage.class::isInstance));
    }

    /**
     * 通过真实模型读取一趟 Agent 执行结束时的状态。
     * <p>关键点：invoke 和 call 都可执行多轮；invoke 返回 OverAllState，call 提取最终助手消息。
     * <p>观察：messages 应含用户、工具请求、工具结果和最终回答；实际路径取决于模型决策。
     */
    @Test
    @Tag("live")
    @DisplayName("Alibaba 真实模型：调用工具后读取整趟执行状态")
    void realModelReturnsState() throws Exception {
        ModelConfig config = ModelConfig.loadProperties();
        // 1.1.x 的 OpenAI 模型构造入口与 2.0.x 不同，仅在本模块使用。
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl()).build())
                .defaultOptions(OpenAiChatOptions.builder().model(config.model()).build()).build();
        ReactAgent agent = ReactAgent.builder().name("alibaba-live-study").model(model)
                .systemPrompt("请调用一次 ping 工具，再根据结果用中文简短回答。")
                .tools(ToolCallbacks.from(new PingTool())).build();
        var state = agent.invoke("我是 study，请检查连通性。").orElseThrow();
        ((List<?>) state.value("messages").orElseThrow())
                .forEach(message -> System.out.println("[Alibaba 状态消息] " + message));
    }
}
