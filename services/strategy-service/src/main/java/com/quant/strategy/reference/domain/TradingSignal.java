package com.quant.strategy.reference.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 策略生成的原始交易信号。
 *
 * <p>信号不等于订单。信号只表达“策略想做什么”；订单需要经过风控、资金、
 * 仓位等规则之后才会生成。这个拆分是量化系统里很重要的一条边界。</p>
 */
public record TradingSignal(
    LocalDate tradeDate,
    String stockCode,
    SignalType type,
    BigDecimal price,
    BigDecimal fastAverage,
    BigDecimal slowAverage,
    String reason
) {
}
