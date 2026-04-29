package com.quant.strategy.reference.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每个交易日收盘后的账户权益快照。
 */
public record EquityPoint(
    LocalDate tradeDate,
    BigDecimal cash,
    BigDecimal positionQuantity,
    BigDecimal closePrice,
    BigDecimal totalEquity,
    BigDecimal drawdown
) {
}
