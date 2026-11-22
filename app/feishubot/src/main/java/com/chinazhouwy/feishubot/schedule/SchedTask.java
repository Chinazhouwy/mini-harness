package com.chinazhouwy.feishubot.schedule;

import java.time.ZoneId;
import java.util.Objects;

/** 一条定时任务有自己的 ID，并归属一个 Bot。 */
public record SchedTask(String taskId, String botId, String cron, ZoneId zoneId) {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public SchedTask(String taskId, String botId, String cron) {
        this(taskId, botId, cron, DEFAULT_ZONE);
    }

    public SchedTask {
        if (Objects.requireNonNull(taskId, "taskId").isBlank()
                || Objects.requireNonNull(botId, "botId").isBlank()) {
            throw new IllegalArgumentException("任务 ID 和 Bot ID 不能为空");
        }
        Objects.requireNonNull(cron, "cron");
        Objects.requireNonNull(zoneId, "zoneId");
    }
}
