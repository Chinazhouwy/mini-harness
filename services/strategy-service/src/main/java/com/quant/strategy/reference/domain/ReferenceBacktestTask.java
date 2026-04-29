package com.quant.strategy.reference.domain;

import com.quant.strategy.reference.application.ReferenceBacktestRequest;

import java.time.LocalDateTime;

/**
 * 一次回测任务的完整记录。
 *
 * <p>参考版先使用内存仓储保存。迁移到 PostgreSQL 时，这个 record 可以拆成
 * backtest_task 主表、orders 明细表、signals 明细表、equity_curve 明细表。</p>
 */
public record ReferenceBacktestTask(
    String taskId,
    BacktestTaskStatus status,
    ReferenceBacktestRequest request,
    ReferenceBacktestReport report,
    String errorMessage,
    LocalDateTime createdAt
) {

    public static ReferenceBacktestTask success(
        String taskId,
        ReferenceBacktestRequest request,
        ReferenceBacktestReport report,
        LocalDateTime createdAt
    ) {
        return new ReferenceBacktestTask(taskId, BacktestTaskStatus.SUCCESS, request, report, null, createdAt);
    }

    public static ReferenceBacktestTask failed(
        String taskId,
        ReferenceBacktestRequest request,
        String errorMessage,
        LocalDateTime createdAt
    ) {
        return new ReferenceBacktestTask(taskId, BacktestTaskStatus.FAILED, request, null, errorMessage, createdAt);
    }
}
