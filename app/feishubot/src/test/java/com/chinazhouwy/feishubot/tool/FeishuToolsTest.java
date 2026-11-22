package com.chinazhouwy.feishubot.tool;

import com.chinazhouwy.feishubot.ProjectPaths;
import com.chinazhouwy.feishubot.monitor.AgentEventStore;

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
        var webTools = new DeepSeekWebTools("test-key");
        var spawnAgent = tools.spawnAgent(model, new io.agentscope.core.state.InMemoryAgentStateStore(), webTools, new AgentEventStore(Path.of(System.getProperty("java.io.tmpdir"))));
        Toolkit agentToolkit = new Toolkit();
        agentToolkit.registerTool(tools);
        agentToolkit.registerTool(webTools);
        agentToolkit.registerAgentTool(spawnAgent);
        assertThat(agentToolkit.getToolNames()).contains("spawn_agent", "read", "write", "edit", "bash", "web_search", "web_fetch");

        String file = "pi-" + UUID.randomUUID() + ".txt";
        try {
            assertThat(output(tools.write(null, file, "hello pi"))).contains("Written to");
            assertThat(output(tools.read(null, file, null, null, null, null))).contains("hello pi");
            assertThat(output(tools.edit(null, file, "pi", "agent", false))).contains("Edited");
            assertThat(output(tools.read(null, file, null, null, null, null))).contains("hello agent");

            // 已放开命令白名单：date 这类普通命令可以直接执行。
            assertThat(output(tools.bash("date", 5).block(Duration.ofSeconds(10)))).contains("<returncode>0</returncode>");

            ToolResultBlock bash = tools.bash("echo pi-ok", 5).block(Duration.ofSeconds(10));
            assertThat(bash).isNotNull();
            assertThat(output(bash)).contains("pi-ok");

            // 仍然拦截明显违规写法（多条命令串联）。
            assertThat(output(tools.bash("echo one && echo two", 5).block(Duration.ofSeconds(10))))
                    .contains("SecurityError", "multiple command separators");
        } finally {
            try {
                Files.deleteIfExists(ProjectPaths.home().resolve(".feishu-agent-workspace").resolve(file));
            } catch (Exception ignored) {
                // 清理失败不影响本次工具行为验证。
            }
        }
    }

    private static String output(ToolResultBlock result) {
        return result == null ? "" : result.getOutput().toString();
    }
}
