package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.model.ModelCreator;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/** MCP 专题：服务连接、工具发现与模型调用。 */
@Tag("live")
public class McpStudyTest {

    /**
     * 观察外部 MCP 工具如何接入模型的工具调用循环。
     * <p>关键点：stdio transport 负责管道通信，McpClient 负责协议操作，Provider 把 MCP 工具适配成 ToolCallback。
     * <p>观察：模型原样返回文件中的随机校验码；查看初始化、工具列表、HTTP 中的工具回填和连接关闭。
     */
    @Test
    @DisplayName("MCP 接入：发现外部文件工具，注册给模型并读取真实文件")
    // @TempDir 由 JUnit 创建独立临时目录，测试结束后清理，不需要手动建立测试文件夹。
    void chatWithMcpFileTool(@TempDir Path directory) throws Exception {
        Path note = directory.resolve("note.txt");
        // 随机校验码只写入文件，不放进发给模型的用户消息，用来核对模型是否实际读到了工具结果。
        String marker = "MCP-" + UUID.randomUUID();
        Files.writeString(note, "今日学习任务：理解 MCP 工具调用链。校验码：" + marker);

        // stdio 通过子进程的标准输入/输出通信，服务只允许读取本例的临时目录。
        Path npx = findNpx();
        // ServerParameters 是启动 MCP 服务子进程的配置：command 是 npx 的路径，不是模型地址。
        var parameters = ServerParameters.builder(npx.toString())
                // -y 允许 npx 自动安装/使用包；固定版本让三框架后续能连接同一实现。
                // --read-only 在服务端只启用读取工具；后面的目录参数决定 filesystem 服务允许访问的范围。
                .args("-y", "@modelcontextprotocol/server-filesystem@2.2.0", "--read-only",
                        directory.toRealPath().toString())
                // npx 脚本通过 PATH 查找 node；把同版本的 bin 放前面，避免 IDE 没加载 nvm 环境而启动失败。
                .env(Map.of("PATH", npx.getParent() + File.pathSeparator + System.getenv().getOrDefault("PATH", "")))
                .build();
        // StdioClientTransport 是传输层：Java 写子进程 stdin 发送请求，读它的 stdout 接收响应。
        // 管道里是一行一条的 JSON-RPC 消息；服务日志写 stderr，不能混入协议使用的 stdout。
        // McpJsonDefaults.getMapper() 提供 MCP JSON 转换器：Java 协议对象与 JSON 文本互转，不是模型输出转换器。
        // 这里只创建传输对象，MCP 协议初始化由下方 initialize 完成。
        var transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        // McpClient 负责协议操作，transport 负责消息运输；sync 表示调用方按同步接口等待结果。
        // requestTimeout 限制 MCP 请求的等待，不是模型 HTTP 超时，也不是整趟 Agent 的总执行预算。
        try (var mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60)).build()) {
            // 连接服务并完成协议初始化/能力协商；serverInfo 是服务身份，不是模型身份。
            System.out.println("[MCP 初始化] " + mcp.initialize().serverInfo());
            // listTools 向服务查询工具定义，尚未执行文件读取；定义包含名称、描述和参数 Schema。
            System.out.println("[MCP 发现的工具] " + mcp.listTools().tools().stream().map(tool -> tool.name()).toList());

            // 桥接层：把 MCP 服务公布的工具转换为 Spring AI ToolCallback。
            var callbacks = SyncMcpToolCallbackProvider.builder().mcpClients(mcp)
                    // 只向模型开放 read_text_file；这是客户端选择工具，与服务端的目录校验是两层不同约束。
                    .toolFilter((connection, tool) -> tool.name().equals("read_text_file"))
                    .build().getToolCallbacks();
            System.out.println("[提供给模型的工具] " + Arrays.stream(callbacks)
                    .map(callback -> callback.getToolDefinition().name()).toList());
            // 模型只收到工具定义，选择工具后输出名字与参数；Spring AI 执行对应回调，回调再调用 MCP 客户端。
            // 文件内容成为工具结果消息后，自动工具循环继续请求模型，模型才能生成最终回答。
            String answer = ChatClient.create(new ModelCreator().getModel()).prompt()
                    .system("请调用提供的文件读取工具，原样返回文件内容，包括校验码，不要猜测。")
                    .user("读取这个文件：" + note.toRealPath()).tools(callbacks).call().content();
            System.out.println("[模型最终回答] " + answer);
            System.out.println("[文件实际校验码] " + marker);
        } // 关闭 MCP 客户端及其 stdio 连接。
        System.out.println("[MCP 连接] 已关闭");
    }

    /** 优先从 PATH 找 npx，再兼容本机的 nvm 安装；IDE 也可以直接运行。 */
    private static Path findNpx() throws IOException {
        // PATH 是多个目录组成的列表；这里依次寻找可执行 npx，返回绝对路径给子进程配置。
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path command = Path.of(entry, "npx");
            if (Files.isExecutable(command)) return command.toAbsolutePath();
        }
        // 只在 PATH 没找到时回退到 macOS/Linux 常见的 nvm 安装目录；不下载或修改 Node 安装。
        Path versions = Path.of(System.getProperty("user.home"), ".nvm/versions/node");
        if (Files.isDirectory(versions)) {
            try (var paths = Files.list(versions)) {
                // 本例按路径字符串排序选一个版本，并不是完整的 Node 语义版本比较器。
                return paths.map(version -> version.resolve("bin/npx")).filter(Files::isExecutable)
                        .max(Comparator.naturalOrder()).orElseThrow(() -> new IOException("请先安装 Node.js/npm"));
            }
        }
        throw new IOException("找不到 npx，请安装 Node.js/npm 并配置 PATH");
    }

}
