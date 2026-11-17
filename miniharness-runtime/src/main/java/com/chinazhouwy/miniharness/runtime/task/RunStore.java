package com.chinazhouwy.miniharness.runtime.task;

import com.chinazhouwy.miniharness.runtime.core.RunId;

import java.util.List;
import java.util.Optional;

/** Run 仓储接口。 */
public interface RunStore {

    void save(Run run);

    Optional<Run> find(RunId id);

    List<Run> findAll();

    List<Run> findByTask(com.chinazhouwy.miniharness.runtime.core.TaskId taskId);
}
