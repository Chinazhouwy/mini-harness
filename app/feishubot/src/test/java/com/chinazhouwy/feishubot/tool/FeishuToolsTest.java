package com.chinazhouwy.feishubot.tool;

import com.chinazhouwy.feishubot.bot.AgentWorkspace;
import com.chinazhouwy.feishubot.monitor.AgentEventStore;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuToolsTest {
    @TempDir
    Path home;

    @Test
    void registersAndRunsWorkspaceTools() {
        FeishuTools tools = new FeishuTools(home);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder("read", "write", "edit");

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("test-key").baseUrl("https://example.invalid/v1").modelName("test-model").build();
        var webTools = new DeepSeekWebTools("test-key");
        toolkit.registerTool(webTools);
        toolkit.registerAgentTool(tools.spawnAgent(model, new InMemoryAgentStateStore(), webTools,
                new AgentEventStore(home.resolve("logs"))));
        assertThat(toolkit.getToolNames()).contains("spawn_agent", "web_search", "web_fetch").doesNotContain("bash");

        String file = "notes.txt";
        assertThat(output(tools.write(null, file, "hello pi"))).contains("Written to");
        assertThat(output(tools.read(null, file, null, null, null, null))).contains("hello pi");
        assertThat(output(tools.edit(null, file, "pi", "agent", false))).contains("Edited");
        assertThat(output(tools.read(null, file, null, null, null, null))).contains("hello agent");
    }

    @Test
    void deniesProtectedMarkdownAndAliasesOutsideTheWorkspace() throws Exception {
        FeishuTools tools = new FeishuTools(home);
        Path root = Files.createDirectories(home.resolve(".feishu-agent-workspace"));
        for (String name : AgentWorkspace.FILES) {
            Path path = root.resolve(name);
            Files.writeString(path, "protected");
            assertDenied(tools, name);
            assertDenied(tools, "nested/" + name.toLowerCase(java.util.Locale.ROOT));
            assertThat(Files.readString(path)).isEqualTo("protected");
        }

        Path protectedDirectory = Files.createDirectories(home.resolve("data/feishubot/evolution"));
        Path protectedFile = Files.writeString(protectedDirectory.resolve("USER.md"), "private");
        Files.createSymbolicLink(root.resolve("alias.txt"), protectedFile);
        Files.createSymbolicLink(root.resolve("linked-directory"), protectedDirectory);
        assertDenied(tools, "alias.txt");
        assertDenied(tools, "linked-directory/ordinary.txt");
        assertThat(Files.readString(protectedFile)).isEqualTo("private");
        assertThat(protectedDirectory.resolve("ordinary.txt")).doesNotExist();
    }

    private void assertDenied(FeishuTools tools, String path) {
        assertThat(tools.read(null, path, null, null, null, null).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(tools.write(null, path, "changed").getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(tools.edit(null, path, "protected", "changed", false).getState()).isEqualTo(ToolResultState.ERROR);
    }

    private static String output(ToolResultBlock result) {
        return result.getOutput().toString();
    }
}
