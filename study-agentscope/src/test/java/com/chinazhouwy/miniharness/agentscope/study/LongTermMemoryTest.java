package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 长期记忆：使用框架提取／整理组件写文件，再由新 Harness 会话加载。 */
@Tag("live")
class LongTermMemoryTest {

    /**
     * 同一个案例观察保存、跨会话读取、明确纠错及不同用户隔离。
     * <p>为看清步骤，本例主动调用 flush/consolidate 并等待完成，关闭自动后台记忆任务，避免时序干扰。
     * <p>临时目录只保存虚构偏好；不修改仓库或 Codex 的记忆，也没有实际重启 JVM。
     */
    @Test
    @DisplayName("长期记忆：提取落盘、新会话读取、纠错后整理及用户隔离")
    void rememberCorrectAndRecall(@TempDir Path workspace) throws Exception {
        ModelConfig settings = ModelConfig.loadProperties();
        var model = OpenAIChatModel.builder()
                            .apiKey(settings.apiKey())
                            .baseUrl(settings.baseUrl())
                            .modelName(settings.model())
                            .nativeStructuredOutput(false)
                            .stream(true)
                            .build();
        String oldCode = "MEM-" + UUID.randomUUID().toString().substring(0, 8);
        String newCode = "MEM-" + UUID.randomUUID().toString().substring(0, 8);
        var first = context("user-A", "session-1");

        try (HarnessAgent writer = agent(workspace, model)) {
            WorkspaceManager memory = writer.getWorkspaceManager();
            // 手工提供待提取的历史，省去一轮无关的“已记住”问答；内容仍由真实模型提取，不是 Java 直接写 MEMORY.md。
            save(memory, model, first, "我偏好简洁的中文解释。我的项目代号是 " + oldCode + "，请长期记住。");
            System.out.println("[每日记忆文件] " + memory.listMemoryFilePaths(first));
            try (var paths = Files.list(memory.getMemoryDir(first))) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                    System.out.println("[每日记录 " + path.getFileName() + "]\n" + Files.readString(path));
                }
            }
            System.out.println("[首次 MEMORY.md]\n" + memory.readMemoryMd(first));
            assertTrue(memory.readMemoryMd(first).contains(oldCode), "整理后的记忆应包含首次项目代号");
        }

        // 新对象、新 sessionId，仅沿用工作区与 userId；本次请求不包含偏好和随机项目代号。
        try (HarnessAgent reader = agent(workspace, model)) {
            var second = context("user-A", "session-2");
            String question = "根据已有记忆，我偏好的回答语言和项目代号是什么？不知道就说不知道，不调用工具。";
            var answer = reader.call(question, second).block(Duration.ofMinutes(2));
            System.out.println("[新会话读取] " + answer.getTextContent());
            assertTrue(answer.getTextContent().contains(oldCode), "新会话应从工作区记忆读到随机代号");

            // 用户明确纠错：每日记录仍保留历史，MEMORY.md 应整理为新值；这是模型整理结果，不是硬编码覆盖字段。
            save(reader.getWorkspaceManager(), model, second,
                    "更正之前的项目代号：旧值 " + oldCode + " 已作废，现在是 " + newCode
                            + "。请以新值为准，我偏好简洁中文的习惯不变。");
            String corrected = reader.getWorkspaceManager().readMemoryMd(second);
            System.out.println("[纠错后的 MEMORY.md]\n" + corrected);
            assertTrue(corrected.contains(newCode), "整理后的记忆应包含新项目代号");
        }

        try (HarnessAgent reader = agent(workspace, model)) {
            var third = context("user-A", "session-3");
            var answer = reader.call("我的当前项目代号是什么？仅依据记忆回答，不调用工具。", third)
                    .block(Duration.ofMinutes(2));
            System.out.println("[纠错后新会话读取] " + answer.getTextContent());
            assertTrue(answer.getTextContent().contains(newCode) && !answer.getTextContent().contains(oldCode),
                    "当前回答应使用新值，而不是已作废的代号");

            // 同一工作区改 userId：直接检查实际读取内容，再观察模型回答，避免只靠一句“不知道”判断隔离。
            var other = context("user-B", "session-1");
            String otherMemory = reader.getWorkspaceManager().readMemoryMd(other);
            System.out.println("[另一用户的 MEMORY.md] " + (otherMemory.isBlank() ? "空" : otherMemory));
            assertTrue(otherMemory.isBlank(), "新用户不应读到 user-A 的记忆文件");
            System.out.println("[另一用户回答] " + reader.call("我的项目代号是什么？仅依据记忆回答，不调用工具。", other)
                    .block(Duration.ofMinutes(2)).getTextContent());
        }
    }

    /** flush 写每日账本，consolidate 合并到 MEMORY.md；这两步分别可能调用一次模型。 */
    private void save(WorkspaceManager workspace, OpenAIChatModel model, RuntimeContext context, String statement) {
        new MemoryFlushManager(workspace, model)
                .flushMemories(context, List.of(new UserMessage(statement)))
                .block(Duration.ofMinutes(2));
        // 沿用框架默认模板的长度参数；只补充本例关于明确纠错的整理要求。
        String prompt = MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT
                + "\n若用户明确纠正同一字段，仅保留最新有效值，删除已作废的旧值。不要补充事实。";
        new MemoryConsolidator(workspace, model, prompt, 1000)
                .consolidate(context)
                .block(Duration.ofMinutes(2));
    }

    private RuntimeContext context(String user, String session) {
        return RuntimeContext.builder().userId(user).sessionId(session).build();
    }

    /** 保留工作区记忆加载与记忆工具，关闭无关工具；后台提取关闭后，上面的 save 显式管理写入时机。 */
    private HarnessAgent agent(Path workspace, OpenAIChatModel model) {
        return HarnessAgent.builder()
                .name("long-term-memory-study")
                .agentId("long-term-memory-study")
                .model(model)
                .workspace(workspace)
                .sysPrompt("请用中文简短回答。当前用户的工作区记忆是用户声明的信息；没有相关信息就说不知道。")
                .disableMemoryHooks() // 跳过自动提取／后台整理，不等于关闭全部记忆读取；本例保留 memory 工具。
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                .disableSubagents()
                .disableDefaultWorkspaceSkills()
                .build();
    }
}
