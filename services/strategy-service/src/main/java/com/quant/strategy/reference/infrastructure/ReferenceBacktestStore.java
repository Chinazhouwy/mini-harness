package com.quant.strategy.reference.infrastructure;

import com.quant.strategy.reference.domain.ReferenceBacktestTask;

import java.util.List;
import java.util.Optional;

/**
 * Reference 回测任务仓储边界。
 *
 * <p>Phase 1 使用内存实现，Phase 2 增加 PostgreSQL 实现。业务服务只依赖这个接口，
 * 这样存储介质切换不会污染策略、风控和 Controller 代码。</p>
 */
public interface ReferenceBacktestStore {

    void save(ReferenceBacktestTask task);

    Optional<ReferenceBacktestTask> findById(String taskId);

    Optional<ReferenceBacktestTask> latestSuccessfulTask();

    List<ReferenceBacktestTask> findRecent(int limit);

    default List<ReferenceBacktestTask> findPage(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        return findRecent((safePage + 1) * safeSize).stream()
            .skip((long) safePage * safeSize)
            .limit(safeSize)
            .toList();
    }

    default long count() {
        return findRecent(Integer.MAX_VALUE).size();
    }
}
