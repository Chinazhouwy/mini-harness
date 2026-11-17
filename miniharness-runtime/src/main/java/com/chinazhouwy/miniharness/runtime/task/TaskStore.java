package com.chinazhouwy.miniharness.runtime.task;

import com.chinazhouwy.miniharness.runtime.core.TaskId;

import java.util.List;
import java.util.Optional;

/** 任务仓储接口。v0.1 提供内存实现；持久化策略（SQLite/文件）后续按需替换。 */
public interface TaskStore {

    void save(Task task);

    Optional<Task> find(TaskId id);

    List<Task> findAll();

    List<Task> findByStatus(com.chinazhouwy.miniharness.runtime.core.TaskStatus status);
}
