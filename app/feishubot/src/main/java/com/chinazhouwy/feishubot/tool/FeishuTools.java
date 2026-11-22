package com.chinazhouwy.feishubot.tool;

import com.chinazhouwy.feishubot.ProjectPaths;
import com.chinazhouwy.feishubot.bot.AgentWorkspace;
import com.chinazhouwy.feishubot.monitor.AgentEventStore;
import com.chinazhouwy.feishubot.monitor.ModelRequestLogMiddleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/** 普通 Bot 的工作区文件工具和子 Agent 委派。 */
@Component
public class FeishuTools {
    private static final String WORKSPACE = ".feishu-agent-workspace";
    private static final String WRITE_LOG = "feishu-write-record.log";
    private static final Object WRITE_LOCK = new Object();

    private final FilesystemTool fileTools;

    private final Path workspace;

    private final Path writeLog;

    public FeishuTools() {
        this(ProjectPaths.home());
    }

    FeishuTools(Path home) {
        this.workspace = home.resolve(WORKSPACE).toAbsolutePath().normalize();
        this.writeLog = home.resolve("logs").resolve(WRITE_LOG);
        this.fileTools = new FilesystemTool(new LocalFilesystem(workspace, LocalFsMode.SANDBOXED, null, 10, null) {
            @Override
            protected Path resolvePath(RuntimeContext context, String key) {
                Path target = super.resolvePath(context, key);
                Path relative = workspace.relativize(target);
                Path entry = workspace;
                if (Files.isSymbolicLink(entry)) {
                    throw new SecurityException("工作区不能是符号链接");
                }
                for (Path part : relative) {
                    entry = entry.resolve(part);
                    if (AgentWorkspace.FILES.stream().anyMatch(part.toString()::equalsIgnoreCase)
                            || Files.isSymbolicLink(entry)) {
                        throw new SecurityException("工作区保留文件或符号链接不可访问");
                    }
                }
                return target;
            }
        });
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
        return withWorkspaceAccess(() -> fileTools.readFile(context, path, offset, limit, charOffset, maxChars));
    }

    /** 新建文件；已存在的文件会失败，修改已有内容请用 edit。 */
    @Tool(name = "write",
            description = "新建文件并写入完整内容。已存在的文件会失败，修改现有文件请用 edit。")
    public ToolResultBlock write(
            RuntimeContext context,
            @ToolParam(name = "path", description = "目标文件路径，相对 workspace") String path,
            @ToolParam(name = "content", description = "要写入的完整内容") String content) {
        ToolResultBlock result = withWorkspaceAccess(() -> fileTools.writeFile(context, path, content));
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
        return withWorkspaceAccess(() -> fileTools.editFile(context, path, oldString, newString, replaceAll));
    }

    private ToolResultBlock withWorkspaceAccess(Supplier<ToolResultBlock> operation) {
        try {
            return operation.get();
        } catch (SecurityException e) {
            return ToolResultBlock.error(e.getMessage());
        }
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
    public AgentTool spawnAgent(OpenAIChatModel model, AgentStateStore stateStore, DeepSeekWebTools webTools, AgentEventStore eventStore) {
        SubAgentProvider<ReActAgent> provider = () -> ReActAgent.builder()
                .name("feishu-sub-agent")
                .description("处理一个独立任务的子助手")
                .model(model)
                .toolkit(subAgentToolkit(webTools))
                .sysPrompt("你是飞书主助手委派的子 Agent，直接完成分配的任务，并用简洁中文汇报结果。需要联网时用 web_search 搜索、用 web_fetch 读原文。")
                .middleware(new ModelRequestLogMiddleware()).middleware(eventStore.middleware())
                .build();

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName("spawn_agent")
                .description("创建子 Agent 处理独立任务；参数 message 写清任务，可选 session_id 表示追问同一子 Agent。")
                .stateStore(stateStore)
                .build();

        return new SubAgentTool(provider, config);
    }

    /** 子 Agent 使用同样的工作区文件工具和 DeepSeek 联网工具，避免把 spawn_agent 再传给下一层。 */
    private Toolkit subAgentToolkit(DeepSeekWebTools webTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(this);
        toolkit.registerTool(webTools);
        return toolkit;
    }
}
