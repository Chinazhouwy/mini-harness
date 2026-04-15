package com.quant.strategy.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 交易信号DTO
 */
public record SignalDto(
    @JsonProperty("signalType") String signalType,
    @JsonProperty("tradeDate") LocalDate tradeDate,
    @JsonProperty("price") BigDecimal price,
    @JsonProperty("indicatorValue") BigDecimal indicatorValue,
    @JsonProperty("reason") String reason
) {
    
    /**
     * 创建买入信号
     */
    public static SignalDto buy(LocalDate tradeDate, BigDecimal price, 
                                BigDecimal indicatorValue, String reason) {
        return new SignalDto("BUY", tradeDate, price, indicatorValue, reason);
    }
    
    /**
     * 创建卖出信号
     */
    public static SignalDto sell(LocalDate tradeDate, BigDecimal price, 
                                 BigDecimal indicatorValue, String reason) {
        return new SignalDto("SELL", tradeDate, price, indicatorValue, reason);
    }
}