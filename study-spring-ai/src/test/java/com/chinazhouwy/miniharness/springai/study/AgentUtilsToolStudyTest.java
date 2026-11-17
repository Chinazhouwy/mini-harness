package com.chinazhouwy.miniharness.springai.study;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.util.JsonHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接入一个现成文件查找工具：固定工具请求，不调用真实模型或网络。 */
class AgentUtilsToolStudyTest {

    @TempDir
    Path workspace;

    @Test
    void executeGlobThroughSpringAiManager() throws Exception {
        Path source = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(source.resolve("Example.java"), "class Example {}\n");
        Files.writeString(source.resolve("notes.txt"), "study fixture\n");

        GlobTool glob = GlobTool.builder()
                .workingDirectory(workspace)
                .allowedDirectory(workspace)
                .maxResults(10)
                .build();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(glob))
                .build();
        Prompt prompt = new Prompt("查找工作目录里的 Java 文件", options);

        // 模拟模型提出的工具请求；参数解析、工具查找与文件遍历均实际执行。
        AssistantMessage request = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "glob-1", "function", "Glob", "{\"pattern\":\"**/*.java\"}")))
                .build();
        var result = ToolCallingManager.builder().build().executeToolCalls(
                prompt, new ChatResponse(List.of(new Generation(request))));

        var toolMessage = (ToolResponseMessage) result.conversationHistory().getLast();
        var response = toolMessage.getResponses().getFirst();
        assertEquals("glob-1", response.id());
        // 默认 ToolCallResultConverter 会将 Java String 编码成 JSON 字符串。
        assertEquals(new JsonHelper().toJson(source.resolve("Example.java").toString()),
                response.responseData());
        assertTrue(result.conversationHistory().contains(request));
        System.out.println("[Glob 工具结果] " + response.responseData());
    }
}
