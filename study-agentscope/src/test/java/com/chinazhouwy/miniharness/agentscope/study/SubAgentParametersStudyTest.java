package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 显式传参的小实验：Java 构造参数，真实子 Agent 回答，不包含主管模型的自动委派。 */
@Tag("live")
class SubAgentParametersStudyTest {
    @Test
    @DisplayName("子 Agent 参数：打印配置、内置 Schema，比较携带与漏传 session_id")
    void showParametersAndSession() throws Exception {
        var settings = ModelConfig.loadProperties();
        var model = OpenAIChatModel.builder().apiKey(settings.apiKey()).baseUrl(settings.baseUrl())
                .modelName(settings.model()).nativeStructuredOutput(false).stream(true).build();

        // 一、这五项是 Java 侧的配置，不是模型调用工具时填写的入参。
        var config = SubAgentConfig.builder()
                .toolName("ask_checker")
                .description("记录和查询工单资料，可通过 session_id 继续此前会话")
                .stateStore(new InMemoryAgentStateStore()) // 内存中保存子会话历史，退出 JVM 后丢失。
                .forwardEvents(false) // 只返回最终工具结果，不转发子 Agent 中间事件。
                .streamOptions(null) // 使用默认选项；forwardEvents=false 时不使用这项。
                .build();
        System.out.println("\n========== 1. Java 侧配置（全部五项） ==========\n"
                + "toolName = " + config.getToolName() + "\n"
                + "description = " + config.getDescription() + "\n"
                + "stateStore = " + config.getStateStore().getClass().getSimpleName() + "\n"
                + "forwardEvents = " + config.isForwardEvents() + "\n"
                + "streamOptions = " + config.getStreamOptions());

        var children = new ArrayList<ReActAgent>();
        try {
            var tool = new SubAgentTool(() -> {
                var child = ReActAgent.builder().name("资料助手").model(model)
                        .sysPrompt("仅依据本会话已收到的资料，用一句中文回答；没有验收码就回答不知道，禁止猜测。")
                        .build();
                children.add(child);
                return child;
            }, config);

            // 二、直接读取真实工具定义；parameters 是框架生成的，没有手写 Schema。
            var definition = Map.of("name", tool.getName(), "description", tool.getDescription(),
                    "parameters", tool.getParameters());
            System.out.println("\n========== 2. 框架生成的工具定义（本例只打印） ==========\n" + json(definition));
            System.out.println("outputSchema = " + tool.getOutputSchema()
                    + "（本工具没有声明输出 Schema，下面查看实际返回文本）");

            // userId/sessionId 是调用方身份与会话；SubAgentTool 会另建或恢复自己的子会话。
            var parent = RuntimeContext.builder().userId("demo-user").sessionId("parent-demo").build();

            // 三、新会话只传 message：返回文本由框架附上新生成的 session_id。
            var firstInput = Map.<String, Object>of("message",
                    "工单 DEMO-001 处理中，验收码 DEMO-7319。请记住资料，本次只回复已记录。");
            String firstResult = invoke("3. 第一次调用：不传 session_id，新建子会话", tool, firstInput, parent);

            // 当前框架返回的是文本，并非包含 session_id 字段的 JSON；本例按该文本格式提取。
            String sessionId = firstResult.lines().filter(line -> line.startsWith("session_id: "))
                    .map(line -> line.substring("session_id: ".length()).trim()).findFirst().orElseThrow();
            System.out.println("[Java 从第一次结果提取的 session_id] " + sessionId);

            // 四、明确把值放进工具入参的 session_id 字段；这里没有让主管模型代填。
            var secondInput = new LinkedHashMap<String, Object>();
            secondInput.put("message", "刚才的验收码是什么？");
            secondInput.put("session_id", sessionId); // 关键赋值就在这一行。
            invoke("4. 第二次调用：携带 session_id，恢复历史后追问", tool, secondInput, parent);

            // 五、同一个工具、同一份状态存储、同一个调用方，漏传子会话 ID 仍会新建子会话。
            var missingIdInput = Map.<String, Object>of("message", "刚才的验收码是什么？");
            invoke("5. 对照调用：漏传 session_id，观察新 ID 和不知道的回答", tool, missingIdInput, parent);
        } finally {
            // 构造 SubAgentTool 时也会创建样例实例，一起关闭。
            for (var child : children) {
                child.close();
            }
        }
    }

    /** 真正交给 SubAgentTool 的就是打印出的 input Map；没有额外替它补参数。 */
    private String invoke(String title, SubAgentTool tool, Map<String, Object> input, RuntimeContext parent)
            throws Exception {
        System.out.println("\n========== " + title + " ==========\n[实际入参]\n" + json(input));
        var result = tool.callAsync(ToolCallParam.builder().input(input).runtimeContext(parent).build())
                .block(Duration.ofMinutes(2));
        String text = text(result);
        System.out.println("[实际返回文本]\n" + text);
        return text;
    }

    private String text(ToolResultBlock result) {
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).collect(Collectors.joining("\n"));
    }

    private String json(Object value) throws Exception {
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
