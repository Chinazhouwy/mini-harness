package com.quant.strategy.reference.infrastructure;

import com.quant.strategy.reference.domain.ReferenceBacktestTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reference MVP 的内存任务仓储。
 *
 * <p>它不是最终生产形态，但非常适合 MVP 学习阶段：没有额外数据库配置，
 * 端到端链路可以先跑通。后续替换成 PostgreSQL Repository 时，Controller
 * 和大部分 service 代码不需要动。</p>
 */
@Repository
@ConditionalOnProperty(name = "reference.store.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryReferenceBacktestStore implements ReferenceBacktestStore {

    private final ConcurrentMap<String, ReferenceBacktestTask> tasks = new ConcurrentHashMap<>();

    public void save(ReferenceBacktestTask task) {
        tasks.put(task.taskId(), task);
    }

    public Optional<ReferenceBacktestTask> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<ReferenceBacktestTask> latestSuccessfulTask() {
        return tasks.values().stream()
            .filter(task -> task.report() != null)
            .max(Comparator.comparing(ReferenceBacktestTask::createdAt));
    }

    public List<ReferenceBacktestTask> findRecent(int limit) {
        return tasks.values().stream()
            .sorted(Comparator.comparing(ReferenceBacktestTask::createdAt).reversed())
            .limit(Math.max(1, limit))
            .toList();
    }
}
