package com.chinazhouwy.feishubot.schedule;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/** 管理进程内的定时任务，触发后执行注册时传入的回调。 */
@Component
public class BotTaskScheduler implements AutoCloseable {

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    private final Map<String, Registration> tasks = new LinkedHashMap<>();

    public BotTaskScheduler() {
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("feishu-task-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
    }

    public synchronized void register(SchedTask task, Runnable action) {
        if (tasks.containsKey(task.taskId())) {
            throw new IllegalArgumentException("定时任务已存在 " + task.taskId());
        }
        var trigger = new CronTrigger(task.cron(), task.zoneId());
        var future = scheduler.schedule(action, trigger);
        if (future == null) {
            throw new IllegalArgumentException("定时任务没有下一次触发时间 " + task.taskId());
        }
        tasks.put(task.taskId(), new Registration(task, future));
    }

    /** 停止后续触发，允许已经开始的这次执行结束。 */
    public synchronized boolean stop(String taskId) {
        Registration registration = tasks.remove(taskId);
        if (registration == null) {
            return false;
        }
        registration.future().cancel(false);
        return true;
    }

    public synchronized List<SchedTask> list() {
        return tasks.values().stream().map(Registration::task).toList();
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        tasks.values().forEach(task -> task.future().cancel(false));
        tasks.clear();
        scheduler.shutdown();
    }

    private record Registration(SchedTask task, ScheduledFuture<?> future) {}
}
