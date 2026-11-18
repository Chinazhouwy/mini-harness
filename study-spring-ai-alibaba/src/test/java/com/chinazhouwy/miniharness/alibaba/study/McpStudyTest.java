package com.chinazhouwy.miniharness.alibaba.study;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.chinazhouwy.miniharness.model.ModelConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MCP 同题对照：文件服务与 Spring AI 示例相同，工具循环改由 Alibaba Graph 执行。 */
@Tag("live")
class McpStudyTest {

    /**
     * 观察 MCP 工具如何进入 ReactAgent 的模型／工具节点循环。
     * <p>关键点：MCP 仍由 Spring AI 的 Provider 适配为 ToolCallback；ReactAgent.tools 注册回调，invoke 返回图状态。
     * <p>观察：服务发现的工具、提供给模型的工具，以及 messages 中配对的工具请求／结果和最终回答。
     */
    @Test
    @DisplayName("Alibaba MCP：Graph 调用文件工具，并在状态中保留工具请求与结果")
    void chatWithMcpFileTool(@TempDir Path directory) throws Exception {
        // 校验码只存在于临时文件，不写入提示词；JUnit 在测试后清理目录。
        Path note = directory.resolve("note.txt");
        String marker = "MCP-" + UUID.randomUUID();
        Files.writeString(note, "今日学习任务：理解 MCP 工具调用链。校验码：" + marker);

        Path npx = findNpx();
        // 与 Spring AI demo 连接同一种、同版本的服务；每次测试各自启动子进程，仅允许读取本次临时目录。
        var parameters = ServerParameters.builder(npx.toString())
                .args("-y", "@modelcontextprotocol/server-filesystem@2.2.0", "--read-only",
                        directory.toRealPath().toString())
                // npx 通过 PATH 查找 node；兼容 IDE 没有加载 nvm 环境的情况。
                .env(Map.of("PATH", npx.getParent() + File.pathSeparator + System.getenv().getOrDefault("PATH", "")))
                .build();
        // transport 负责通过子进程 stdin/stdout 传输 JSON-RPC；mapper 负责协议对象与 JSON 互转。
        // 本模块 MCP SDK 0.17.0 的默认转换器入口是 getDefault，与 Spring AI 2.x 示例的命名不同。
        var transport = new StdioClientTransport(parameters, McpJsonMapper.getDefault());
        // 这是 MCP 请求超时，不是模型网络超时；try 结束时关闭客户端及 stdio 连接。
        try (var mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60)).build()) {
            System.out.println("[MCP 初始化] " + mcp.initialize().serverInfo());
            System.out.println("[MCP 发现的工具] " + mcp.listTools().tools().stream().map(tool -> tool.name()).toList());

            // 发现全部工具后只选择文本读取；模型看到的是转换后的工具定义，实际执行时回调再请求 MCP 服务。
            var callbacks = SyncMcpToolCallbackProvider.builder().mcpClients(mcp)
                    .toolFilter((connection, tool) -> tool.name().equals("read_text_file"))
                    .build().getToolCallbacks();
            System.out.println("[提供给模型的工具] " + Arrays.stream(callbacks)
                    .map(callback -> callback.getToolDefinition().name()).toList());

            // 本模块使用 Spring AI 1.1.2 的模型构造入口；配置仍取自根目录的本地配置文件。
            ModelConfig config = ModelConfig.loadProperties();
            var model = OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl()).build())
                    .defaultOptions(OpenAiChatOptions.builder().model(config.model()).build()).build();
            var agent = ReactAgent.builder().name("alibaba-mcp-study").model(model)
                    .systemPrompt("请调用提供的文件读取工具，原样返回文件内容，包括校验码，不要猜测。")
                    .tools(callbacks).build();

            // invoke 执行完整工具循环，不是只请求模型一次；MCP 客户端须保持打开直到整趟执行结束。
            var state = agent.invoke("读取这个文件：" + note.toRealPath()).orElseThrow();
            List<Message> messages = state.<List<Message>>value("messages").orElseThrow();
            // 这里在结束后按顺序打印状态，不是逐个流式片段打印；id 可用于配对工具请求和返回结果。
            for (Message message : messages) {
                if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                    System.out.println("[Graph 工具请求] " + assistant.getToolCalls());
                } else if (message instanceof ToolResponseMessage result) {
                    System.out.println("[Graph 工具结果] " + result.getResponses());
                } else {
                    System.out.println("[Graph " + message.getMessageType() + "] " + message.getText());
                }
            }
            System.out.println("[文件实际校验码] " + marker);
        }
        System.out.println("[MCP 连接] 已关闭");
    }

    /** 保持模块独立：先找 PATH，再兼容 nvm；这里不引入另一学习模块的依赖。 */
    private static Path findNpx() throws IOException {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path command = Path.of(entry, "npx");
            if (Files.isExecutable(command)) return command.toAbsolutePath();
        }
        Path versions = Path.of(System.getProperty("user.home"), ".nvm/versions/node");
        if (Files.isDirectory(versions)) {
            try (var paths = Files.list(versions)) {
                // 与原 demo 一致，按路径字符串选版本，不是语义版本比较。
                return paths.map(version -> version.resolve("bin/npx")).filter(Files::isExecutable)
                        .max(Comparator.naturalOrder()).orElseThrow(() -> new IOException("请先安装 Node.js/npm"));
            }
        }
        throw new IOException("找不到 npx，请安装 Node.js/npm 并配置 PATH");
    }
}
