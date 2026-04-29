package com.quant.strategy.reference.interfaces;

import com.quant.strategy.reference.application.ReferenceBacktestException;
import com.quant.strategy.reference.application.ReferenceBacktestRequest;
import com.quant.strategy.reference.application.ReferenceMvpBacktestService;
import com.quant.strategy.reference.application.ReferenceQualityService;
import com.quant.strategy.reference.domain.ReferenceBacktestTask;
import com.quant.strategy.reference.domain.ReferenceQualityReport;
import com.quant.strategy.reference.domain.ReferenceRunResponse;
import com.quant.strategy.reference.domain.ReferenceBacktestReport;
import com.quant.strategy.reference.domain.ReferenceTaskPage;
import com.quant.strategy.reference.domain.SimulatedOrder;
import com.quant.strategy.reference.domain.TradingSignal;
import com.quant.strategy.reference.infrastructure.ReferenceBacktestStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Reference MVP API。
 *
 * <p>它和现有 {@code /api/strategy/backtest} 并行存在，路径用 {@code /api/reference}
 * 明确表示“学习参考实现”。后续你觉得这版结构更顺手，再把它迁移成正式接口。</p>
 */
@RestController
@CrossOrigin
@RequestMapping("/api/reference")
public class ReferenceStrategyController {

    private final ReferenceMvpBacktestService backtestService;
    private final ReferenceBacktestStore store;
    private final ReferenceQualityService qualityService;

    public ReferenceStrategyController(
        ReferenceMvpBacktestService backtestService,
        ReferenceBacktestStore store,
        ReferenceQualityService qualityService
    ) {
        this.backtestService = backtestService;
        this.store = store;
        this.qualityService = qualityService;
    }

    /**
     * 完整参数回测。
     *
     * <pre>
     * POST /api/reference/backtest
     * {
     *   "stockCode": "000001",
     *   "startDate": "2024-03-01",
     *   "endDate": "2024-04-30",
     *   "fastWindow": 5,
     *   "slowWindow": 20,
     *   "initialCash": 100000,
     *   "maxPositionRatio": 0.8,
     *   "stopLossRatio": 0.08
     * }
     * </pre>
     */
    @PostMapping("/backtest")
    public ResponseEntity<ReferenceRunResponse> run(@RequestBody ReferenceBacktestRequest request) {
        ReferenceRunResponse response = backtestService.runAndSave(request);
        return response.report() == null
            ? ResponseEntity.badRequest().body(response)
            : ResponseEntity.ok(response);
    }

    /**
     * 方便浏览器直接访问的快速回测接口。
     */
    @GetMapping("/backtest/quick")
    public ResponseEntity<ReferenceRunResponse> quick(
        @RequestParam(required = false) String stockCode,
        @RequestParam(required = false) LocalDate startDate,
        @RequestParam(required = false) LocalDate endDate,
        @RequestParam(required = false) Integer fastWindow,
        @RequestParam(required = false) Integer slowWindow
    ) {
        return run(new ReferenceBacktestRequest(
            stockCode,
            startDate,
            endDate,
            fastWindow,
            slowWindow,
            null,
            null,
            null
        ));
    }

    @GetMapping("/tasks")
    public List<ReferenceBacktestTask> tasks(@RequestParam(defaultValue = "20") int limit) {
        return store.findRecent(limit);
    }

    @GetMapping("/tasks/page")
    public ReferenceTaskPage taskPage(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return new ReferenceTaskPage(safePage, safeSize, store.count(), store.findPage(safePage, safeSize));
    }

    @GetMapping("/tasks/{taskId}")
    public ReferenceBacktestTask task(@PathVariable String taskId) {
        return store.findById(taskId)
            .orElseThrow(() -> new ReferenceBacktestException("task not found: " + taskId));
    }

    @GetMapping("/tasks/latest")
    public ReferenceBacktestTask latestTask() {
        return store.latestSuccessfulTask()
            .orElseThrow(() -> new ReferenceBacktestException("no successful task found"));
    }

    @GetMapping("/tasks/{taskId}/signals")
    public List<TradingSignal> taskSignals(@PathVariable String taskId) {
        return task(taskId).report() == null ? List.of() : task(taskId).report().signals();
    }

    @GetMapping("/tasks/{taskId}/orders")
    public List<SimulatedOrder> taskOrders(@PathVariable String taskId) {
        return task(taskId).report() == null ? List.of() : task(taskId).report().orders();
    }

    @GetMapping("/tasks/{taskId}/equity-curve")
    public List<com.quant.strategy.reference.domain.EquityPoint> taskEquityCurve(@PathVariable String taskId) {
        return task(taskId).report() == null ? List.of() : task(taskId).report().equityCurve();
    }

    @GetMapping("/tasks/{taskId}/quality")
    public ReferenceQualityReport taskQuality(@PathVariable String taskId) {
        return qualityService.check(taskId);
    }

    @GetMapping("/signals/latest")
    public List<TradingSignal> latestSignals() {
        return store.latestSuccessfulTask()
            .map(task -> task.report().signals())
            .orElse(List.of());
    }

    @GetMapping("/orders")
    public List<SimulatedOrder> latestOrders() {
        return store.latestSuccessfulTask()
            .map(task -> task.report().orders())
            .orElse(List.of());
    }

    @GetMapping("/quality/{taskId}")
    public ReferenceQualityReport quality(@PathVariable String taskId) {
        return qualityService.check(taskId);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "module", "reference-mvp");
    }

    @ExceptionHandler(ReferenceBacktestException.class)
    public ResponseEntity<Map<String, String>> handleReferenceException(ReferenceBacktestException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
