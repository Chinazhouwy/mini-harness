package com.quant.strategy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * K线数据实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyKline {
    // 交易日期
    private LocalDate tradeDate;
    // 股票代码
    private String stockCode;
    // 前收盘价
    private BigDecimal preClose;
    // 开盘价
    private BigDecimal open;
    // 最高价
    private BigDecimal high;
    // 最低价
    private BigDecimal low;
    // 收盘价
    private BigDecimal close;
    // 成交量（股）
    private Long volume;
    // 成交金额（万元）
    private BigDecimal amount;
    // 换手率
    private BigDecimal turnoverRate;
    // 涨跌额
    private BigDecimal change;
    // 涨跌幅
    private BigDecimal changePct;
    // 振幅
    private BigDecimal amplitude;
}