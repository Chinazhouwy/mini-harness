package com.quant.strategy.reference.domain;

import java.math.BigDecimal;

/**
 * 回测核心指标。
 */
public record BacktestMetrics(
    BigDecimal initialCash,
    BigDecimal finalEquity,
    BigDecimal totalReturn,
    BigDecimal maxDrawdown,
    int tradeCount,
    int winningTrades,
    BigDecimal winRate
) {
}
