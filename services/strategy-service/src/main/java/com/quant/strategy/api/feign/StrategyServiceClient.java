package com.quant.strategy.api.feign;

import com.quant.strategy.api.dto.BacktestRequestDto;
import com.quant.strategy.api.dto.BacktestResultDto;
import com.quant.strategy.api.dto.SignalDto;
import com.quant.strategy.api.dto.StrategyConfigDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "strategy-service", path = "/api/v1/strategy")
public interface StrategyServiceClient {

    @PostMapping("/backtest")
    BacktestResultDto runBacktest(@RequestBody BacktestRequestDto request);

    @PostMapping("/signal")
    SignalDto generateSignal(@RequestBody StrategyConfigDto config, @RequestParam String symbol);

    @GetMapping("/signal/{strategyId}/{symbol}")
    SignalDto getSignal(@PathVariable String strategyId, @PathVariable String symbol);

    @PutMapping("/config/{id}")
    StrategyConfigDto updateStrategyConfig(@PathVariable String id, @RequestBody StrategyConfigDto config);

    @GetMapping("/config/{id}")
    StrategyConfigDto getStrategyConfig(@PathVariable String id);

    @PostMapping("/config")
    StrategyConfigDto createStrategyConfig(@RequestBody StrategyConfigDto config);
}
