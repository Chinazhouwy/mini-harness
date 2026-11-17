package com.chinazhouwy.miniharness.runtime.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存 Run 仓储：插入序保持稳定。 */
public class InMemoryRunStore implements RunStore {

    private final Map<String, Run> runs = new LinkedHashMap<>();

    @Override
    public synchronized void save(Run run) {
        runs.put(run.id().value(), run);
    }

    @Override
    public synchronized Optional<Run> find(com.chinazhouwy.miniharness.runtime.core.RunId id) {
        return Optional.ofNullable(runs.get(id.value()));
    }

    @Override
    public synchronized List<Run> findAll() {
        return List.copyOf(runs.values());
    }

    @Override
    public synchronized List<Run> findByTask(com.chinazhouwy.miniharness.runtime.core.TaskId taskId) {
        List<Run> matched = new ArrayList<>();
        for (Run run : runs.values()) {
            if (run.taskId().value().equals(taskId.value())) {
                matched.add(run);
            }
        }
        return List.copyOf(matched);
    }
}
