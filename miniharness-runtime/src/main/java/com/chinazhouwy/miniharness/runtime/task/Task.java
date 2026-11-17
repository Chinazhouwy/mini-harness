package com.chinazhouwy.miniharness.runtime.task;

import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.SessionId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.TaskStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期任务：承载用户意图、关联会话、追加消息与历次 Run。
 * 与聊天历史明确区分——Task 是业务状态，Session/消息只是交互痕迹。
 */
public class Task {

    private final TaskId id;
    private final String userQuery;
    private final SessionId sessionId;
    private final Instant createdAt;
    private Instant updatedAt;
    private String title;
    private String company;
    private String intent;
    private TaskStatus status;
    private final List<TaskMessage> messages = new ArrayList<>();
    private final List<RunId> runIds = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public Task(TaskId id, String userQuery, SessionId sessionId, Instant createdAt) {
        this.id = id;
        this.userQuery = userQuery;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.title = defaultTitle(userQuery);
        this.status = TaskStatus.OPEN;
        this.messages.add(TaskMessage.user(userQuery));
    }

    private static String defaultTitle(String query) {
        String compact = query.replaceAll("\\s+", " ").trim();
        return compact.length() <= 40 ? compact : compact.substring(0, 40) + "…";
    }

    public synchronized void transitionTo(TaskStatus target) {
        status.requireTransitionTo(target);
        status = target;
        updatedAt = Instant.now();
    }

    public synchronized void attachIntent(String intent, String company) {
        if (intent != null && !intent.isBlank()) {
            this.intent = intent;
        }
        if (company != null && !company.isBlank()) {
            this.company = company;
        }
        updatedAt = Instant.now();
    }

    public synchronized void addMessage(TaskMessage message) {
        messages.add(message);
        updatedAt = Instant.now();
    }

    public synchronized void linkRun(RunId runId) {
        runIds.add(runId);
        updatedAt = Instant.now();
    }

    public synchronized void putMetadata(String key, Object value) {
        metadata.put(key, value);
        updatedAt = Instant.now();
    }

    public synchronized TaskId id() {
        return id;
    }

    public synchronized String userQuery() {
        return userQuery;
    }

    public synchronized SessionId sessionId() {
        return sessionId;
    }

    public synchronized Instant createdAt() {
        return createdAt;
    }

    public synchronized Instant updatedAt() {
        return updatedAt;
    }

    public synchronized String title() {
        return title;
    }

    public synchronized String company() {
        return company;
    }

    public synchronized String intent() {
        return intent;
    }

    public synchronized TaskStatus status() {
        return status;
    }

    public synchronized List<TaskMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized List<RunId> runIds() {
        return List.copyOf(runIds);
    }

    public synchronized Map<String, Object> metadata() {
        return new LinkedHashMap<>(metadata);
    }
}
