package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.UUID;
import java.util.function.BiConsumer;

/** 一个飞书聊天回合里的任务创建工具。Bot 和投递会话由调用方绑定。 */
public final class ScheduleTaskTool {
    private final BotTaskScheduler scheduler;
    private final String botId;
    private final BiConsumer<SchedTask, String> execute;

    ScheduleTaskTool(BotTaskScheduler scheduler, String botId, BiConsumer<SchedTask, String> execute) {
        this.scheduler = scheduler;
        this.botId = botId;
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

        var task = new SchedTask(UUID.randomUUID().toString(), botId, cron);
        try {
            scheduler.register(task, () -> execute.accept(task, instruction));
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.error("创建定时任务失败：" + e.getMessage());
        }
        return ToolResultBlock.success("已创建任务 " + task.taskId()
                + "，cron 为 " + task.cron() + "，时区为 " + task.zoneId() + "。任务在当前服务进程中有效。");
    }
}
