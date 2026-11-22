package com.chinazhouwy.feishubot.schedule;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 一个飞书聊天回合里的定时任务工具。Bot 和投递会话由调用方绑定。 */
public final class ScheduleTaskTool {
    private static final Logger log = LoggerFactory.getLogger(ScheduleTaskTool.class);

    private final BotTaskScheduler scheduler;
    private final String botId;
    private final String chatId;
    private final Consumer<SchedTask> execute;

    public ScheduleTaskTool(BotTaskScheduler scheduler, String botId, String chatId, Consumer<SchedTask> execute) {
        this.scheduler = scheduler;
        this.botId = botId;
        this.chatId = chatId;
        this.execute = execute;
    }

    @Tool(name = "schedule_task", description = "当用户明确要求定期执行一项任务时，创建定时任务并返回任务 ID。"
            + "任务由当前 Bot 执行，结果发回当前飞书会话。")
    public ToolResultBlock scheduleTask(
            @ToolParam(name = "cron", description = "Spring 六段 cron，时区为 Asia/Shanghai。每天 9 点是 0 0 9 * * *")
                    String cron,
            @ToolParam(name = "instruction", description = "每次触发时交给 Bot 的完整任务指令") String instruction) {
        if (cron == null || cron.isBlank() || instruction == null || instruction.isBlank()) {
            return ToolResultBlock.error("请提供 cron 和任务指令。");
        }

        var task = new SchedTask(UUID.randomUUID().toString(), botId, cron, instruction, chatId);
        try {
            scheduler.register(task, () -> execute.accept(task));
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.error("创建定时任务失败：" + e.getMessage());
        } catch (UncheckedIOException e) {
            log.error("定时任务落盘失败 taskId={}", task.taskId(), e);
            return ToolResultBlock.error("定时任务保存失败，请稍后重试。");
        }
        return ToolResultBlock.success("已创建任务 " + task.taskId()
                + "，cron 为 " + task.cron() + "，时区为 " + task.zoneId() + "。已保存，重启后会恢复。");
    }

    @Tool(name = "stop_task", description = "当用户要求停止当前聊天的定时任务时使用。"
            + "只有一个启用任务时可以省略任务 ID，有多个时按工具返回的候选任务确认。"
            + "根据工具结果告诉用户是否已停止。")
    public ToolResultBlock stopTask(
            @ToolParam(name = "task_id", description = "要停止的定时任务 ID。未明确指定时可以省略",
                    required = false) String taskId) {
        if (taskId == null || taskId.isBlank()) {
            var candidates = scheduler.list().stream()
                    .filter(task -> task.botId().equals(botId) && task.chatId().equals(chatId))
                    .toList();
            if (candidates.isEmpty()) {
                return ToolResultBlock.error("当前聊天没有启用的定时任务。");
            }
            if (candidates.size() > 1) {
                String choices = candidates.stream()
                        .map(task -> task.taskId() + "（" + task.instruction() + "）")
                        .collect(Collectors.joining("\n"));
                return ToolResultBlock.error("当前聊天有多个启用的定时任务，请指定任务 ID。\n" + choices);
            }
            taskId = candidates.getFirst().taskId();
        }

        String id = taskId.strip();
        try {
            if (!scheduler.stopForChat(id, botId, chatId)) {
                return ToolResultBlock.error("当前聊天中找不到该 ID 的启用任务，任务可能已停止。");
            }
        } catch (UncheckedIOException e) {
            log.error("定时任务停用状态落盘失败 taskId={}", id, e);
            return ToolResultBlock.error("保存停用状态失败，定时任务仍然启用。");
        }
        return ToolResultBlock.success("已停止定时任务 " + id + "。后续不会再触发，已开始的这次执行可以跑完。");
    }
}
