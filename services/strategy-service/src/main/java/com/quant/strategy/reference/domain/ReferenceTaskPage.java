package com.quant.strategy.reference.domain;

import java.util.List;

/**
 * 回测任务分页响应。
 */
public record ReferenceTaskPage(
    int page,
    int size,
    long total,
    List<ReferenceBacktestTask> items
) {
}
