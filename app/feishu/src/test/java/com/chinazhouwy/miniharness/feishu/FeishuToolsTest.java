package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Pi 四工具、官方注册的 spawn_agent，以及一次最小读写链路。 */
class FeishuToolsTest {
    @Test
    void registersAndRunsPiStyleTools() {
        FeishuTools tools = new FeishuTools();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder("read", "write", "edit", "bash");

        // 只验证官方入口注册后的工具名，不触发真实模型调用。
        OpenAIChatModel model = OpenAIChatModel.builder().apiKey("test-key").baseUrl("https://example.invalid/v1").modelName("test-model").build();
        var spawnAgent = tools.spawnAgent(model, new io.agentscope.core.state.InMemoryAgentStateStore());
        Toolkit agentToolkit = new Toolkit();
        agentToolkit.registerTool(tools);
        agentToolkit.registerAgentTool(spawnAgent);
        assertThat(agentToolkit.getToolNames()).contains("spawn_agent", "read", "write", "edit", "bash");

        String file = "pi-" + UUID.randomUUID() + ".txt";
        try {
            assertThat(output(tools.write(null, file, "hello pi"))).contains("Written to");
            assertThat(output(tools.read(null, file, null, null, null, null))).contains("hello pi");
            assertThat(output(tools.edit(null, file, "pi", "agent", false))).contains("Edited");
            assertThat(output(tools.read(null, file, null, null, null, null))).contains("hello agent");

            ToolResultBlock bash = tools.bash("echo pi-ok", 5).block(Duration.ofSeconds(10));
            assertThat(bash).isNotNull();
            assertThat(output(bash)).contains("pi-ok");
        } finally {
            try {
                Files.deleteIfExists(Path.of(".feishu-agent-workspace", file));
            } catch (Exception ignored) {
                // 清理失败不影响本次工具行为验证。
            }
        }
    }

    private static String output(ToolResultBlock result) {
        return result == null ? "" : result.getOutput().toString();
    }
}