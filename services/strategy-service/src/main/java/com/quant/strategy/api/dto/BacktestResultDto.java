package com.quant.strategy.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResultDto {
    private Double totalReturn;
    private Double sharpeRatio;
    private Double maxDrawdown;
    private Integer tradeCount;
    private Integer winningTrades;
    private List<Double> equityCurve;
    private String tradingRecordJson;
}
