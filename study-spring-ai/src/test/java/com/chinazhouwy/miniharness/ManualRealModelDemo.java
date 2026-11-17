package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.model.ModelConfig;
import com.chinazhouwy.miniharness.tool.FileSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;

import java.util.List;

/** 真实模型实验：由应用显式控制模型调用、工具执行和消息回填。 */
@org.junit.jupiter.api.Tag("live")
class ManualRealModelDemo {

    @Test
    void manualToolLoopWithRealModel() throws Exception {
        ModelConfig config = ModelConfig.loadProperties();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(config.apiKey())
                        .baseUrl(config.baseUrl())
                        .model(config.model())
                        .build())
//                .httpClientBuilderCustomizer(
//                        builder -> builder.interceptor(new ModelHttpTraceInterceptor())
//                )
                .build();

        FileSearchTool fileTool = new FileSearchTool(
                ModelConfig.findProjectFile(
                                "study-spring-ai/src/test/java/com/chinazhouwy/miniharness/springai/study").toString());
        ToolCallback[] callbacks = ToolCallbacks.from(fileTool);
        OpenAiChatOptions options = model.getOptions().mutate()
                .toolCallbacks(List.of(callbacks))
                .build();
        ToolCallingManager toolManager = ToolCallingManager.builder().build();
        List<Message> messages = List.of(
                new SystemMessage("请根据工具结果，用中文简短回答。"),
                new UserMessage("请调用 find_files 查找工作目录中的 Java 文件，列出文件名。"));

        // 本例把上限定义为最多请求模型的次数，防止模型持续请求工具。
        int maxRounds = 4;
        for (int round = 1; round <= maxRounds; round++) {
            Prompt prompt = new Prompt(messages, options);
            System.out.printf("%n[第 %d 轮] 请求模型，消息数=%d%n", round, messages.size());
            ChatResponse response = model.call(prompt);

            if (!response.hasToolCalls()) {
                System.out.println("[最终回答] " + response.getResult().getOutput().getText());
                return;
            }

            // 留出下一次模型请求的机会，避免工具执行后没有预算回传结果。
            if (round == maxRounds) {
                throw new IllegalStateException("已达到模型请求次数上限：" + maxRounds);
            }
            System.out.println("[模型请求工具] " + response.getResult().getOutput().getToolCalls());
            ToolExecutionResult result = toolManager.executeToolCalls(prompt, response);
            if (result.returnDirect()) {
                throw new IllegalStateException("这个学习示例暂不处理 returnDirect 工具");
            }
            messages = result.conversationHistory();
            System.out.println("[工具执行完成] 已把工具请求和结果加入下一轮消息" + messages.toString());
        }
        throw new IllegalStateException("模型调用循环未正常结束");
    }
}
