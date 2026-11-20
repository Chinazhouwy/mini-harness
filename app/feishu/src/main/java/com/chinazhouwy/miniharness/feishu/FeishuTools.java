package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Set;

/**
 * Pi 风格四件套工具：read / write / edit / bash。
 *
 * <p>文件操作直接复用 AgentScope Harness 的 FilesystemTool，命令执行复用 AgentScope Core 的
 * ShellCommandTool；这里只负责把工具名和参数收敛成四个，并限制工作目录与命令白名单。
 */
@Component
public class FeishuTools {
    private static final String WORKSPACE = ".feishu-agent-workspace";
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "ls", "pwd", "cat", "find", "grep", "git", "java", "javac", "mvn", "echo");

    private final FilesystemTool fileTools;
    private final ShellCommandTool shell;

    public FeishuTools() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path workspace = cwd.resolve(WORKSPACE);

        // SANDBOXED 会把相对路径和绝对路径都锚定到 workspace；namespaceFactory 为 null 表示不做会话隔离。
        LocalFilesystem filesystem = new LocalFilesystem(workspace, LocalFsMode.SANDBOXED, null, 10, null);
        this.fileTools = new FilesystemTool(filesystem);
        this.shell = new ShellCommandTool(cwd.toString(), ALLOWED_COMMANDS, null);
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
        return fileTools.writeFile(context, path, content);
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

    /** 在服务进程当前目录执行白名单命令；命令拼接由 AgentScope 的校验器拦截。 */
    @Tool(name = "bash",
            description = "在服务进程当前目录执行白名单命令：ls/pwd/cat/find/grep/git/java/javac/mvn/echo；禁止拼接多条命令。")
    public Mono<ToolResultBlock> bash(
            @ToolParam(name = "command", description = "要执行的单条命令") String command,
            @ToolParam(name = "timeout", description = "可选超时秒数，默认 300", required = false) Integer timeout) {
        return shell.executeShellCommand(command, timeout);
    }

    /**
     * spawn_agent：复用 AgentScope 官方 SubAgentTool，由主 Agent 委派独立任务。
     * 官方协议参数是 message（必填）和 session_id（可选，用于追问同一子 Agent）。
     */
    public AgentTool spawnAgent(OpenAIChatModel model, AgentStateStore stateStore) {
        SubAgentProvider<ReActAgent> provider = () -> ReActAgent.builder()
                .name("feishu-sub-agent")
                .description("处理一个独立任务的子助手")
                .model(model)
                .toolkit(subAgentToolkit())
                .sysPrompt("你是飞书主助手委派的子 Agent，直接完成分配的任务，并用简洁中文汇报结果。")
                .build();

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName("spawn_agent")
                .description("创建子 Agent 处理独立任务；参数 message 写清任务，可选 session_id 表示追问同一子 Agent。")
                .stateStore(stateStore)
                .build();

        return new SubAgentTool(provider, config);
    }

    /** 子 Agent 只拿 Pi 四工具，避免把 spawn_agent 再传给下一层。 */
    private Toolkit subAgentToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(this);
        return toolkit;
    }
}
