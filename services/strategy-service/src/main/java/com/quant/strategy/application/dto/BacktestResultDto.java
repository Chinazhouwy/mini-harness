package com.quant.strategy.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 回测结果DTO
 */
public record BacktestResultDto(
    @JsonProperty("stockCode") String stockCode,
    @JsonProperty("strategyName") String strategyName,
    @JsonProperty("startDate") LocalDate startDate,
    @JsonProperty("endDate") LocalDate endDate,
    @JsonProperty("totalReturn") BigDecimal totalReturn,
    @JsonProperty("annualReturn") BigDecimal annualReturn,
    @JsonProperty("maxDrawdown") BigDecimal maxDrawdown,
    @JsonProperty("sharpeRatio") BigDecimal sharpeRatio,
    @JsonProperty("tradeCount") Integer tradeCount,
    @JsonProperty("winRate") BigDecimal winRate,
    @JsonProperty("profitFactor") BigDecimal profitFactor,
    @JsonProperty("signals") List<SignalDto> signals,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message
) {
    
    /**
     * 创建成功的回测结果
     */
    public static BacktestResultDto success(String stockCode, String strategyName, 
                                            LocalDate startDate, LocalDate endDate,
                                            BigDecimal totalReturn, BigDecimal annualReturn,
                                            BigDecimal maxDrawdown, BigDecimal sharpeRatio,
                                            Integer tradeCount, BigDecimal winRate,
                                            BigDecimal profitFactor, List<SignalDto> signals) {
        return new BacktestResultDto(
            stockCode, strategyName, startDate, endDate,
            totalReturn, annualReturn, maxDrawdown, sharpeRatio,
            tradeCount, winRate, profitFactor, signals,
            "success", "回测完成"
        );
    }
    
    /**
     * 创建失败的回测结果
     */
    public static BacktestResultDto failure(String stockCode, String message) {
        return new BacktestResultDto(
            stockCode, null, null, null,
            null, null, null, null,
            null, null, null, null,
            "failure", message
        );
    }
}