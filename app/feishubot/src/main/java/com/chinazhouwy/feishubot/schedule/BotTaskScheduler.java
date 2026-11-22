package com.chinazhouwy.feishubot.schedule;

import com.chinazhouwy.feishubot.ProjectPaths;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/** 保存任务定义，并在当前进程中注册计时器。 */
@Component
public class BotTaskScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BotTaskScheduler.class);

    private final ScheduledTaskStore store;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, SchedTask> tasks = new LinkedHashMap<>();
    private final Map<String, Registration> active = new LinkedHashMap<>();

    public BotTaskScheduler() {
        this(ProjectPaths.home().resolve("data/feishubot/scheduled-tasks.json"));
    }

    BotTaskScheduler(Path file) {
        this.store = new ScheduledTaskStore(file);
        for (SchedTask task : store.load()) {
            if (tasks.putIfAbsent(task.taskId(), task) != null) {
                throw new IllegalStateException("任务文件中的 ID 重复 " + task.taskId());
            }
        }
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("feishu-task-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
    }

    /** 启动后重建启用任务的计时器，cron 从当前时间计算下一次触发。 */
    public synchronized void restore(Consumer<SchedTask> execute) {
        for (SchedTask task : tasks.values()) {
            if (task.enabled() && !active.containsKey(task.taskId())) {
                try {
                    active.put(task.taskId(), schedule(task, () -> execute.accept(task)));
                } catch (IllegalArgumentException e) {
                    log.warn("跳过无法注册的定时任务 {}", task.taskId(), e);
                }
            }
        }
    }

    public synchronized void register(SchedTask task, Runnable action) {
        if (tasks.containsKey(task.taskId())) {
            throw new IllegalArgumentException("定时任务已存在 " + task.taskId());
        }
        if (!task.enabled()) {
            throw new IllegalArgumentException("新注册的定时任务必须启用");
        }
        var registration = schedule(task, action);
        try {
            save(task);
        } catch (RuntimeException e) {
            registration.future().cancel(false);
            throw e;
        }
        active.put(task.taskId(), registration);
    }

    private Registration schedule(SchedTask task, Runnable action) {
        var trigger = new CronTrigger(task.cron(), task.zoneId());
        Object token = new Object();
        var future = scheduler.schedule(() -> {
            if (isActive(task.taskId(), token)) {
                action.run();
            }
        }, trigger);
        if (future == null) {
            throw new IllegalArgumentException("定时任务没有下一次触发时间 " + task.taskId());
        }
        return new Registration(future, token);
    }

    /** 回调在任务落盘并激活后才放行，失败或停止的注册不会继续触发。 */
    private synchronized boolean isActive(String taskId, Object token) {
        Registration registration = active.get(taskId);
        return registration != null && registration.token() == token;
    }

    private void save(SchedTask task) {
        Map<String, SchedTask> updated = new LinkedHashMap<>(tasks);
        updated.put(task.taskId(), task);
        store.save(updated.values());
        tasks.put(task.taskId(), task);
    }

    /** 停止后续触发，允许已经开始的这次执行结束。 */
    public synchronized boolean stop(String taskId) {
        SchedTask task = tasks.get(taskId);
        if (task == null || !task.enabled()) {
            return false;
        }
        save(task.disabled());
        var registration = active.remove(taskId);
        if (registration != null) {
            registration.future().cancel(false);
        }
        return true;
    }

    synchronized boolean stopForChat(String taskId, String botId, String chatId) {
        SchedTask task = tasks.get(taskId);
        return task != null && task.botId().equals(botId) && task.chatId().equals(chatId) && stop(taskId);
    }

    public synchronized List<SchedTask> list() {
        return active.keySet().stream().map(tasks::get).toList();
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        active.values().forEach(registration -> registration.future().cancel(false));
        active.clear();
        tasks.clear();
        scheduler.shutdown();
    }

    private record Registration(ScheduledFuture<?> future, Object token) {}
}
