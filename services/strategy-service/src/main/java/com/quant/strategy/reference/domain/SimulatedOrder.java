package com.quant.strategy.reference.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 模拟成交订单。
 *
 * <p>MVP 阶段不接真实券商，也不需要撮合引擎。这里把“通过风控的信号”
 * 按收盘价立即成交，目的是让行情、策略、风控、订单、绩效这条链路完整闭合。</p>
 */
public record SimulatedOrder(
    String orderId,
    LocalDate tradeDate,
    String stockCode,
    OrderSide side,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal amount,
    String riskReason
) {
}
