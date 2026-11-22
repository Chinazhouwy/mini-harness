package com.chinazhouwy.feishubot.schedule;

import java.time.ZoneId;
import java.util.Objects;

/** 可以落盘并在重启后重新注册的任务定义。 */
public record SchedTask(String taskId, String botId, String cron, ZoneId zoneId,
                        String instruction, String chatId, boolean enabled) {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public SchedTask(String taskId, String botId, String cron, String instruction, String chatId) {
        this(taskId, botId, cron, DEFAULT_ZONE, instruction, chatId, true);
    }

    public SchedTask {
        requireText(taskId, "taskId");
        requireText(botId, "botId");
        requireText(cron, "cron");
        Objects.requireNonNull(zoneId, "zoneId");
        requireText(instruction, "instruction");
        requireText(chatId, "chatId");
    }

    SchedTask disabled() {
        return new SchedTask(taskId, botId, cron, zoneId, instruction, chatId, false);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
