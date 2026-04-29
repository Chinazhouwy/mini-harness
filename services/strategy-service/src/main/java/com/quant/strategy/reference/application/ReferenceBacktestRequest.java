package com.quant.strategy.reference.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reference MVP 回测请求。
 *
 * <p>这里故意把参数做得直白：股票、时间、均线窗口、初始资金、仓位上限、
 * 止损线。它比“高级策略参数”更适合学习，因为每个参数都能直接映射到
 * 策略或风控里的代码。</p>
 */
public record ReferenceBacktestRequest(
    @JsonProperty("stockCode") String stockCode,
    @JsonProperty("startDate") LocalDate startDate,
    @JsonProperty("endDate") LocalDate endDate,
    @JsonProperty("fastWindow") Integer fastWindow,
    @JsonProperty("slowWindow") Integer slowWindow,
    @JsonProperty("initialCash") BigDecimal initialCash,
    @JsonProperty("maxPositionRatio") BigDecimal maxPositionRatio,
    @JsonProperty("stopLossRatio") BigDecimal stopLossRatio
) {

    public ReferenceBacktestRequest {
        if (stockCode == null || stockCode.isBlank()) {
            stockCode = "000001";
        }
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusYears(1);
        }
        if (fastWindow == null) {
            fastWindow = 5;
        }
        if (slowWindow == null) {
            slowWindow = 20;
        }
        if (initialCash == null) {
            initialCash = new BigDecimal("100000.00");
        }
        if (maxPositionRatio == null) {
            maxPositionRatio = new BigDecimal("0.80");
        }
        if (stopLossRatio == null) {
            stopLossRatio = new BigDecimal("0.08");
        }
    }
}
