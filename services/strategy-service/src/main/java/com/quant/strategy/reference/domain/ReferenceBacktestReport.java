package com.quant.strategy.reference.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Reference MVP 回测报告。
 */
public record ReferenceBacktestReport(
    String stockCode,
    LocalDate startDate,
    LocalDate endDate,
    int fastWindow,
    int slowWindow,
    BacktestMetrics metrics,
    List<TradingSignal> signals,
    List<SimulatedOrder> orders,
    List<EquityPoint> equityCurve,
    List<String> warnings
) {
}
