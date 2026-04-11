package com.quant.strategy.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRequestDto {
    private String symbol;
    private StrategyConfigDto strategyConfig;
    private LocalDate startDate;
    private LocalDate endDate;
    private Double initialCapital;
}
