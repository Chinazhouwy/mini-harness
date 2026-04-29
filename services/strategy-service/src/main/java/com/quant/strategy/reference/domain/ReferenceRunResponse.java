package com.quant.strategy.reference.domain;

/**
 * 触发回测后的响应。
 */
public record ReferenceRunResponse(
    String taskId,
    BacktestTaskStatus status,
    ReferenceBacktestReport report,
    String errorMessage
) {
}
