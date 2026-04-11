package com.quant.strategy.web.controller;

import com.quant.strategy.web.service.StrategyApplicationService;
import com.quant.strategy.web.service.BacktestApplicationService;
import com.quant.strategy.api.dto.BacktestRequestDto;
import com.quant.strategy.api.dto.BacktestResultDto;
import com.quant.strategy.api.dto.StrategyConfigDto;
import org.ta4j.core.BarSeries;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    private final StrategyApplicationService strategyApplicationService;
    private final BacktestApplicationService backtestApplicationService;

    public StrategyController(StrategyApplicationService strategyApplicationService,
                             BacktestApplicationService backtestApplicationService) {
        this.strategyApplicationService = strategyApplicationService;
        this.backtestApplicationService = backtestApplicationService;
    }

    @GetMapping("/list")
    public ResponseEntity<List<StrategyConfigDto>> listStrategies() {
        return ResponseEntity.ok(strategyApplicationService.findAllEnabled());
    }

    @PostMapping("/backtest")
    public ResponseEntity<BacktestResultDto> runBacktest(@RequestBody BacktestRequestDto request) {
        BarSeries series = null;
        BacktestResultDto result = backtestApplicationService.runBacktest(request, series);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/signal")
    public ResponseEntity<?> getSignal(@RequestParam String symbol) {
        return ResponseEntity.ok().build();
    }
}
