package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.CommandValidator;
import io.agentscope.core.tool.coding.CommandValidator.ValidationResult;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.coding.UnixCommandValidator;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Pi 风格四件套工具：read / write / edit / bash。
 *
 * <p>文件操作直接复用 AgentScope Harness 的 FilesystemTool，命令执行复用 AgentScope Core 的
 * ShellCommandTool；这里只负责把工具名和参数收敛成四个，限制文件工作目录，并对命令只做基本安全校验。
 */
@Component
public class FeishuTools {
    private static final String WORKSPACE = ".feishu-agent-workspace";
    private static final String WRITE_LOG = "feishu-write-record.log";
    private static final Object WRITE_LOCK = new Object();

    // 自定义命令校验：去掉命令白名单，但保留两类明显违规检查：命令串联、越界相对路径。
    private static final CommandValidator SIMPLE_COMMAND_VALIDATOR = new CommandValidator() {
        // 具体怎么识别可执行程序、命令分隔符、../ 越界，继续复用 AgentScope 官方 Unix 实现。
        private final UnixCommandValidator unix = new UnixCommandValidator();

        // ShellCommandTool 执行前会调用这里：allowedCommands 参数虽然传进来，但这里故意不使用，
        // 所以 date、python、curl 等普通命令都能执行；只拦命令串联和 ./../ 越界。
        @Override
        public ValidationResult validate(String command, Set<String> allowedCommands) {
            // 取出命令的第一个词，比如 "date +%F" 取出 "date"，用于错误提示和路径检查。
            String executable = unix.extractExecutable(command);

            // 拒绝 &&、||、|、;、换行等拼接：一次只跑一条命令，避免模型写出复杂链式命令。
            if (unix.containsMultipleCommands(command)) {
                return ValidationResult.rejected("Command contains multiple command separators", executable);
            }

            // 对 ./ 开头的相对路径做越界检查：像 ./../secret 这种跳出当前目录的命令要拒绝。
            if (executable.startsWith("./") && !unix.isPathWithinCurrentDirectory(executable)) {
                return ValidationResult.rejected("Command escapes current directory", executable);
            }

            // 其它情况一律放行。
            return ValidationResult.allowed(executable);
        }

        // 下面两个方法不是主校验流程的入口，但 CommandValidator 接口要求实现；
        // 仍然委托给官方 UnixCommandValidator，保证和 validate() 里的判断口径一致。

        // 给外部调用：从命令文本里识别可执行程序名。
        @Override
        public String extractExecutable(String command) {
            return unix.extractExecutable(command);
        }

        // 给外部调用：判断命令里是否包含多条命令分隔符。
        @Override
        public boolean containsMultipleCommands(String command) {
            return unix.containsMultipleCommands(command);
        }
    };

    private final FilesystemTool fileTools;

    private final ShellCommandTool shell;

    private final Path workspace;

    private final Path writeLog;

    public FeishuTools() {
        Path home = ProjectPaths.home();
        this.workspace = home.resolve(WORKSPACE);
        this.writeLog = home.resolve("logs").resolve(WRITE_LOG);

        // SANDBOXED 会把相对路径和绝对路径都锚定到 workspace；namespaceFactory 为 null 表示不做会话隔离。
        LocalFilesystem filesystem = new LocalFilesystem(this.workspace, LocalFsMode.SANDBOXED, null, 10, null);
        this.fileTools = new FilesystemTool(filesystem);
        // Pi 风格：不限制具体命令，只拦命令串联、越界相对路径等违规写法。
        // bash 工作目录也锚到项目根，避免从 IntelliJ / 命令行不同位置启动时命令结果不同。
        this.shell = new ShellCommandTool(home.toString(), null, null, SIMPLE_COMMAND_VALIDATOR);
    }

    /** 读取文件；编辑前先读，大文件可用 offset、limit、char_offset、max_chars 分页。 */
    @Tool(name = "read", readOnly = true,
            description = "读取文件内容。编辑前先 read；大文件可用 offset、limit、char_offset、max_chars 分页。")
    public ToolResultBlock read(
            RuntimeContext context,
            @ToolParam(name = "path", description = "文件路径，相对 workspace") String path,
            @ToolParam(name = "offset", description = "起始行，0 开始，默认 0", required = false) Integer offset,
            @ToolParam(name = "limit", description = "最多读取多少行，默认全部", required = false) Integer limit,
            @ToolParam(name = "char_offset", description = "字符偏移，默认 0", required = false) Integer charOffset,
            @ToolParam(name = "max_chars", description = "单页最大字符数，默认 16000", required = false) Integer maxChars) {
        return fileTools.readFile(context, path, offset, limit, charOffset, maxChars);
    }

    /** 新建文件；已存在的文件会失败，修改已有内容请用 edit。 */
    @Tool(name = "write",
            description = "新建文件并写入完整内容。已存在的文件会失败，修改现有文件请用 edit。")
    public ToolResultBlock write(
            RuntimeContext context,
            @ToolParam(name = "path", description = "目标文件路径，相对 workspace") String path,
            @ToolParam(name = "content", description = "要写入的完整内容") String content) {
        ToolResultBlock result = fileTools.writeFile(context, path, content);
        // 写入拦截器：单独记录到 logs/feishu-write-record.log，不混进主日志。
        recordWrite(context, path, result);
        return result;
    }

    /** 精确替换现有文件中的文本。 */
    @Tool(name = "edit",
            description = "精确替换现有文件中的文本；old_string 必须唯一，或用 replace_all=true 替换全部。")
    public ToolResultBlock edit(
            RuntimeContext context,
            @ToolParam(name = "path", description = "目标文件路径，相对 workspace") String path,
            @ToolParam(name = "old_string", description = "要被替换的原文，必须精确匹配") String oldString,
            @ToolParam(name = "new_string", description = "替换后的新文本") String newString,
            @ToolParam(name = "replace_all", description = "是否替换全部匹配，默认 false", required = false)
                    Boolean replaceAll) {
        return fileTools.editFile(context, path, oldString, newString, replaceAll);
    }

    /** 在服务进程当前目录执行单条命令；具体命令不设白名单，只拦命令拼接和越界相对路径。 */
    @Tool(name = "bash",
            description = "在服务进程当前目录执行单条命令；不限制具体命令，但禁止 &&、|、; 等命令拼接。")
    public Mono<ToolResultBlock> bash(
            @ToolParam(name = "command", description = "要执行的单条命令") String command,
            @ToolParam(name = "timeout", description = "可选超时秒数，默认 300", required = false) Integer timeout) {
        return shell.executeShellCommand(command, timeout);
    }

    /** 把每次 write 追加到独立文件：时间、sessionId、相对路径、绝对 fileUrl、结果状态。 */
    private void recordWrite(RuntimeContext context, String path, ToolResultBlock result) {
        String sessionId = context == null ? "-" : String.valueOf(context.getSessionId());
        String fileUrl = workspace.resolve(path).toAbsolutePath().normalize().toUri().toString();
        String line = LocalDateTime.now() + "\nsessionId=" + sessionId + "\npath=" + path + "\nfileUrl=" + fileUrl + "\nresult=" + result.getState() + System.lineSeparator();
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(writeLog.getParent());
                Files.writeString(writeLog, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                // 记录失败不影响工具本身的写入结果。
                System.err.println("WRITE 记录失败: " + e.getMessage());
            }
        }
    }

    /**
     * spawn_agent：复用 AgentScope 官方 SubAgentTool，由主 Agent 委派独立任务。
     * 官方协议参数是 message（必填）和 session_id（可选，用于追问同一子 Agent）。
     */
    public AgentTool spawnAgent(OpenAIChatModel model, AgentStateStore stateStore, DeepSeekWebTools webTools) {
        SubAgentProvider<ReActAgent> provider = () -> ReActAgent.builder()
                .name("feishu-sub-agent")
                .description("处理一个独立任务的子助手")
                .model(model)
                .toolkit(subAgentToolkit(webTools))
                .sysPrompt("你是飞书主助手委派的子 Agent，直接完成分配的任务，并用简洁中文汇报结果。需要联网时用 web_search 搜索、用 web_fetch 读原文。")
                .middleware(new ModelRequestLogMiddleware())
                .build();

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName("spawn_agent")
                .description("创建子 Agent 处理独立任务；参数 message 写清任务，可选 session_id 表示追问同一子 Agent。")
                .stateStore(stateStore)
                .build();

        return new SubAgentTool(provider, config);
    }

    /** 子 Agent 拿 Pi 四工具 + DeepSeek 联网工具，避免把 spawn_agent 再传给下一层。 */
    private Toolkit subAgentToolkit(DeepSeekWebTools webTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(this);
        toolkit.registerTool(webTools);
        return toolkit;
    }
}
