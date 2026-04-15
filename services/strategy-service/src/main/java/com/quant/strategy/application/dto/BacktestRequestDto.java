package com.quant.strategy.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * 回测请求DTO
 */
public record BacktestRequestDto(
    @JsonProperty("stockCode") String stockCode,
    @JsonProperty("startDate") LocalDate startDate,
    @JsonProperty("endDate") LocalDate endDate,
    @JsonProperty("barCount") Integer barCount,
    @JsonProperty("period") Integer period,
    @JsonProperty("power") Double power,
    @JsonProperty("filter") Integer filter,
    @JsonProperty("phase") Double phase
) {
    
    /**
     * 默认构造函数，提供合理的默认值
     */
    public BacktestRequestDto {
        // 如果没有提供股票代码，使用默认值
        if (stockCode == null || stockCode.isBlank()) {
            stockCode = "600406";
        }
        
        // 默认时间范围：最近250个交易日（约一年）
        if (startDate == null) {
            startDate = LocalDate.now().minusYears(1);
        }
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        
        // 默认数据条数
        if (barCount == null) {
            barCount = 250;
        }
        
        // JMA指标默认参数（将在收到研究结果后调整）
        if (period == null) {
            period = 14;
        }
        if (power == null) {
            power = 2.0;
        }
        if (filter == null) {
            filter = 0;
        }
        if (phase == null) {
            phase = 0.0;
        }
    }
}