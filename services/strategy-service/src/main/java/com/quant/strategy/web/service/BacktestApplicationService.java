package com.quant.strategy.web.service;

import com.quant.strategy.core.executor.BacktestExecutor;
import com.quant.strategy.api.dto.BacktestRequestDto;
import com.quant.strategy.api.dto.BacktestResultDto;
import org.ta4j.core.BarSeries;
import org.springframework.stereotype.Service;

@Service
public class BacktestApplicationService {

    private final BacktestExecutor backtestExecutor;

    public BacktestApplicationService(BacktestExecutor backtestExecutor) {
        this.backtestExecutor = backtestExecutor;
    }

    public BacktestResultDto runBacktest(BacktestRequestDto request, BarSeries series) {
        return backtestExecutor.run(request, series);
    }
}
