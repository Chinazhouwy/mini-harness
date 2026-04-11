package com.quant.strategy.core.executor;

import com.quant.strategy.core.factory.StrategyFactory;
import com.quant.strategy.api.dto.BacktestRequestDto;
import com.quant.strategy.api.dto.BacktestResultDto;
import com.quant.strategy.api.dto.StrategyConfigDto;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.analysis.criteria.TotalReturnCriterion;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;

@Service
public class BacktestExecutor {
    
    private final StrategyFactory strategyFactory;
    
    public BacktestExecutor(StrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }
    
    public BacktestResultDto run(BacktestRequestDto request, BarSeries series) {
        StrategyConfigDto config = request.getStrategyConfig();
        Strategy strategy = strategyFactory.create(config, series);
        BarSeriesManager manager = new BarSeriesManager(series);
        TradingRecord tradingRecord = manager.run(strategy);
        
        TotalReturnCriterion totalReturn = new TotalReturnCriterion();
        Num returnRatio = totalReturn.calculate(series, tradingRecord);
        
        BacktestResultDto result = new BacktestResultDto();
        result.setTotalReturn(BigDecimal.valueOf(returnRatio.doubleValue()));
        result.setSharpeRatio(calculateSharpeRatio(series, tradingRecord));
        result.setMaxDrawdown(calculateMaxDrawdown(tradingRecord));
        result.setTradeCount(tradingRecord.getTrades().size());
        result.setWinningTrades(countWinningTrades(tradingRecord));
        
        return result;
    }
    
    private BigDecimal calculateSharpeRatio(BarSeries series, TradingRecord tradingRecord) {
        return BigDecimal.ONE;
    }
    
    private BigDecimal calculateMaxDrawdown(TradingRecord tradingRecord) {
        return BigDecimal.ZERO;
    }
    
    private int countWinningTrades(TradingRecord tradingRecord) {
        return (int) tradingRecord.getTrades().stream()
            .filter(trade -> trade.getProfit().isPositive())
            .count();
    }
}
