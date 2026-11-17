package com.chinazhouwy.miniharness.runtime.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存任务仓储：插入序保持稳定，读取返回快照。 */
public class InMemoryTaskStore implements TaskStore {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(Task task) {
        tasks.put(task.id().value(), task);
    }

    @Override
    public Optional<Task> find(com.chinazhouwy.miniharness.runtime.core.TaskId id) {
        return Optional.ofNullable(tasks.get(id.value()));
    }

    @Override
    public List<Task> findAll() {
        return List.copyOf(tasks.values());
    }

    @Override
    public List<Task> findByStatus(com.chinazhouwy.miniharness.runtime.core.TaskStatus status) {
        List<Task> matched = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (task.status() == status) {
                matched.add(task);
            }
        }
        return List.copyOf(matched);
    }
}
