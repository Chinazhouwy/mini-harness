package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** MCP 同题对照：与另两个模块使用同版本文件服务，改由 Toolkit 适配、ReActAgent 执行循环。 */
@Tag("live")
class McpStudyTest {

    /**
     * 观察 MCP 工具从发现、筛选注册到模型调用、结果回填的完整过程。
     * <p>关键点：McpClientBuilder 封装协议客户端；Toolkit.registration 选择远程工具并适配为 AgentScope 工具。
     * <p>观察：只注册一个读取工具；结束后打印完整消息块，不逐个流式片段刷屏，用随机校验码核对真实读取结果。
     */
    @Test
    @DisplayName("AgentScope MCP：Toolkit 注册文件工具，ReActAgent 调用后读取完整消息")
    void chatWithMcpFileTool(@TempDir Path directory) throws Exception {
        runFileCase(directory, true);
    }

    /**
     * 只改变文件是否存在，观察工具失败如何回填模型。
     * <p>关键点：MCP 服务正常返回工具错误，AgentScope 转为 ERROR 结果块，随后模型继续解释失败。
     * <p>边界：本例不是服务断线或 JSON-RPC 协议异常，也不代表关闭异常路径已验证。
     */
    @Test
    @DisplayName("MCP 工具失败：文件不存在，错误回填模型后解释原因")
    void chatWithMissingMcpFile(@TempDir Path directory) throws Exception {
        runFileCase(directory, false);
    }

    /** 共用连接和观察代码，两个测试只切换是否创建目标文件。 */
    private void runFileCase(Path directory, boolean createFile) throws Exception {
        // 校验码仅写在文件中，不放入用户提示词；临时目录由 JUnit 在测试结束后清理。
        Path note = directory.resolve("note.txt");
        String marker = "MCP-" + UUID.randomUUID();
        if (createFile) {
            Files.writeString(note, "今日学习任务：理解 MCP 工具调用链。校验码：" + marker);
        }
        ModelConfig config = ModelConfig.loadProperties();
        var model = OpenAIChatModel.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl())
                .modelName(config.model()).nativeStructuredOutput(false).stream(true).build();

        Path npx = findNpx();
        // fs 是应用给客户端起的名字；stdioTransport 负责配置子进程，不是向模型发请求。
        try (McpClientWrapper mcp = McpClientBuilder.create("fs")
                .stdioTransport(npx.toString(),
                        List.of("-y", "@modelcontextprotocol/server-filesystem@2.2.0", "--read-only",
                                directory.toRealPath().toString()),
                        // npx 需要从 PATH 找到 node；服务端只允许读取本次测试目录。
                        Map.of("PATH", npx.getParent() + File.pathSeparator + System.getenv().getOrDefault("PATH", "")))
                .timeout(Duration.ofSeconds(60)).buildSync()) {
            // buildSync 创建同步客户端的包装；initialize 才进行协议初始化。包装 API 仍用 Mono，block 等待完成。
            mcp.initialize().block();
            System.out.println("[MCP 初始化] 客户端=" + mcp.getName() + "，已初始化=" + mcp.isInitialized());
            System.out.println("[MCP 发现的工具] " + mcp.listTools().block().stream().map(tool -> tool.name()).toList());

            Toolkit toolkit = new Toolkit();
            toolkit.registration().mcpClient(mcp)
                    // enableTools 填服务端的原始工具名；省略或传空列表会启用全部工具。
                    .enableTools(List.of("read_text_file"))
                    // 本例显式设置模型可见的名称前缀；真正发给 MCP 服务的名称仍是 read_text_file。
                    .mcpToolNamePrefix("mcp__fs__").apply();
            System.out.println("[提供给模型的工具] " + toolkit.getToolSchemas().stream().map(tool -> tool.getName()).toList());

            // 显式标识本例用户和会话，便于结束后读取对应状态；不配置文件 Store，也不演示跨进程恢复。
            RuntimeContext context = RuntimeContext.builder().userId("mcp-study")
                    .sessionId("session-" + UUID.randomUUID()).build();
            try (ReActAgent agent = ReActAgent.builder().name("agentscope-mcp-study").model(model)
                    .sysPrompt(createFile
                            ? "请调用提供的文件读取工具，原样返回文件内容，包括校验码，不要猜测。"
                            : "请只调用一次文件读取工具。若失败，用中文简短解释工具返回的错误，不要重试或编造内容。")
                    .toolkit(toolkit).build()) {
                // call 内部完成多轮模型／工具循环；block 让本测试等最终 Msg，最多等两分钟，不是 MCP 请求超时。
                // 不存在的文件不能调用 toRealPath；只解析已存在的目录，再拼接文件名，才能让错误发生在 MCP 工具内。
                agent.call("读取这个文件：" + directory.toRealPath().resolve("note.txt"), context)
                        .block(Duration.ofMinutes(2));
                // 读取本次会话的完整消息。ToolUseBlock / ToolResultBlock 的 id 对应，工具结果不一定是顶层 role=tool。
                var messages = agent.getAgentState(context.getUserId(), context.getSessionId()).getContext();
                for (var message : messages) {
                    for (var block : message.getContent()) {
                        if (block instanceof ToolUseBlock call) {
                            System.out.println("[AgentScope 工具请求] id=" + call.getId() + " name="
                                    + call.getName() + " arguments=" + call.getInput());
                        } else if (block instanceof ToolResultBlock result) {
                            String text = result.getOutput().stream().filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast).map(TextBlock::getText).collect(Collectors.joining("\n"));
                            System.out.println("[AgentScope 工具结果] id=" + result.getId() + " name="
                                    + result.getName() + " state=" + result.getState() + " output=" + text);
                        } else if (block instanceof TextBlock text) {
                            System.out.println("[AgentScope " + message.getRole() + "] " + text.getText());
                        }
                    }
                }
                if (createFile) {
                    System.out.println("[文件实际校验码] " + marker); // 与工具结果、模型回答对照。
                }
            }
        } // 外层明确管理 MCP 客户端生命周期；整个工具循环结束后再关闭连接及子进程。
        System.out.println("[MCP 连接] 已关闭");
    }

    /** 与另两个 demo 一样，先找 PATH，再兼容 nvm；各学习模块保持依赖隔离。 */
    private static Path findNpx() throws IOException {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path command = Path.of(entry, "npx");
            if (Files.isExecutable(command)) return command.toAbsolutePath();
        }
        Path versions = Path.of(System.getProperty("user.home"), ".nvm/versions/node");
        if (Files.isDirectory(versions)) {
            try (var paths = Files.list(versions)) {
                // 这里只按路径字符串选版本，不进行完整的 Node 语义版本比较。
                return paths.map(version -> version.resolve("bin/npx")).filter(Files::isExecutable)
                        .max(Comparator.naturalOrder()).orElseThrow(() -> new IOException("请先安装 Node.js/npm"));
            }
        }
        throw new IOException("找不到 npx，请安装 Node.js/npm 并配置 PATH");
    }
}
