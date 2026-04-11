package com.quant.strategy.core.generator;

import com.quant.strategy.core.factory.StrategyFactory;
import com.quant.strategy.api.dto.StrategyConfigDto;
import com.quant.strategy.domain.enums.Signal;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

@Service
public class SignalGenerator {
    
    private final StrategyFactory strategyFactory;
    
    public SignalGenerator(StrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }
    
    public Signal generate(StrategyConfigDto config, BarSeries series) {
        Strategy strategy = strategyFactory.create(config, series);
        int index = series.getEndIndex();
        
        if (strategy.shouldEnter(index)) {
            return Signal.BUY;
        }
        if (strategy.shouldExit(index)) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
