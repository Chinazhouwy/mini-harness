package com.quant.strategy.interfaces.controller;

import com.quant.strategy.application.dto.BacktestRequestDto;
import com.quant.strategy.application.dto.BacktestResultDto;
import com.quant.strategy.application.service.JmaBacktestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 策略回测API控制器
 */
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {
    
    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);
    
    private final JmaBacktestService backtestService;
    
    public StrategyController(JmaBacktestService backtestService) {
        this.backtestService = backtestService;
    }
    
    /**
     * 执行JMA回测
     * POST /api/strategy/backtest
     */
    @PostMapping("/backtest")
    public ResponseEntity<BacktestResultDto> runBacktest(@RequestBody BacktestRequestDto request) {
        log.info("接收回测请求: stockCode={}", request.stockCode());
        
        BacktestResultDto result = backtestService.runBacktest(request);
        
        if ("success".equals(result.status())) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }
    
    /**
     * 快速回测接口（简化参数）
     * GET /api/strategy/backtest/quick?stockCode=600406
     */
    @GetMapping("/backtest/quick")
    public ResponseEntity<BacktestResultDto> quickBacktest(
            @RequestParam(required = false) String stockCode,
            @RequestParam(required = false) Integer period) {
        
        // 使用record的默认构造方法
        BacktestRequestDto request = new BacktestRequestDto(
            stockCode,
            null,  // startDate会自动设置为一年前
            null,  // endDate会自动设置为今天
            null,  // barCount会自动设置为250
            period,
            null,  // power会自动设置为2.0
            null,  // filter会自动设置为0
            null   // phase会自动设置为0.0
        );
        
        BacktestResultDto result = backtestService.runBacktest(request);
        
        if ("success".equals(result.status())) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }
    
    /**
     * 健康检查接口
     * GET /api/strategy/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Strategy Service is running");
    }
}