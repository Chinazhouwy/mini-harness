package com.chinazhouwy.miniharness.runtime.task;

import com.chinazhouwy.miniharness.runtime.core.SessionId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 交互会话：一次 Chat 连续对话的载体。会话可以创建/关联任务，
 * 但任务状态独立于会话存在——关闭会话不取消任务。
 */
public class Session {

    private final SessionId id;
    private final Instant createdAt;
    private final List<TaskId> taskIds = new ArrayList<>();
    private final List<TaskMessage> messages = new ArrayList<>();

    public Session(SessionId id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public synchronized void attachTask(TaskId taskId) {
        taskIds.add(taskId);
    }

    public synchronized void addMessage(TaskMessage message) {
        messages.add(message);
    }

    public synchronized SessionId id() {
        return id;
    }

    public synchronized Instant createdAt() {
        return createdAt;
    }

    public synchronized List<TaskId> taskIds() {
        return List.copyOf(taskIds);
    }

    public synchronized List<TaskMessage> messages() {
        return List.copyOf(messages);
    }
}
