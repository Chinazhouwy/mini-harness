package com.quant.strategy.core.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.strategy.api.dto.StrategyConfigDto;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.OverUnderRule;
import org.ta4j.core.rules.UnderOverRule;

import java.util.Map;

@Component
public class StrategyFactory {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Strategy create(StrategyConfigDto config, BarSeries series) {
        String type = config.getType();
        
        switch (type.toUpperCase()) {
            case "MACD":
                return createMacdStrategy(config, series);
            case "RSI":
                return createRsiStrategy(config, series);
            default:
                throw new IllegalArgumentException("Unknown strategy type: " + type);
        }
    }

    private Strategy createMacdStrategy(StrategyConfigDto config, BarSeries series) {
        Map<String, Object> params = parseParams(config.getParams());
        int fastPeriod = getIntParam(params, "fastPeriod", 12);
        int slowPeriod = getIntParam(params, "slowPeriod", 26);
        int signalPeriod = getIntParam(params, "signalPeriod", 9);

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, fastPeriod, slowPeriod);
        EMAIndicator emaSignal = new EMAIndicator(macd, signalPeriod);

        Rule entryRule = new OverUnderRule(macd, emaSignal);
        Rule exitRule = new OverUnderRule(emaSignal, macd);

        return new Strategy(entryRule, exitRule);
    }

    private Strategy createRsiStrategy(StrategyConfigDto config, BarSeries series) {
        Map<String, Object> params = parseParams(config.getParams());
        int period = getIntParam(params, "period", 14);
        int overboughtLevel = getIntParam(params, "overbought", 70);
        int oversoldLevel = getIntParam(params, "oversold", 30);

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, period);

        Rule entryRule = new UnderOverRule(rsi, oversoldLevel);
        Rule exitRule = new OverUnderRule(rsi, overboughtLevel);

        return new Strategy(entryRule, exitRule);
    }

    private Map<String, Object> parseParams(String jsonParams) {
        if (jsonParams == null || jsonParams.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonParams, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in strategy params: " + jsonParams, e);
        }
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
