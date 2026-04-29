package com.quant.strategy.reference.application;

import com.quant.strategy.reference.domain.BacktestMetrics;
import com.quant.strategy.reference.domain.ReferenceBacktestReport;
import com.quant.strategy.reference.domain.ReferenceBacktestTask;
import com.quant.strategy.reference.domain.ReferenceQualityCheck;
import com.quant.strategy.reference.domain.ReferenceQualityReport;
import com.quant.strategy.reference.infrastructure.ReferenceBacktestStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference MVP 质检服务。
 *
 * <p>质检的价值不是判断策略一定赚钱，而是把“这次回测是否值得继续看”
 * 变成结构化规则。后面可以把这些结果交给 Agent 解释或生成调参建议。</p>
 */
@Service
public class ReferenceQualityService {

    private static final BigDecimal MIN_TOTAL_RETURN = new BigDecimal("-0.10");
    private static final BigDecimal MAX_DRAWDOWN = new BigDecimal("0.20");
    private static final BigDecimal MIN_TRADE_COUNT = BigDecimal.ONE;

    private final ReferenceBacktestStore store;

    public ReferenceQualityService(ReferenceBacktestStore store) {
        this.store = store;
    }

    public ReferenceQualityReport check(String taskId) {
        ReferenceBacktestTask task = store.findById(taskId)
            .orElseThrow(() -> new ReferenceBacktestException("task not found: " + taskId));
        if (task.report() == null) {
            throw new ReferenceBacktestException("task has no report: " + taskId);
        }
        return check(task.taskId(), task.report());
    }

    public ReferenceQualityReport check(String taskId, ReferenceBacktestReport report) {
        BacktestMetrics metrics = report.metrics();
        List<ReferenceQualityCheck> checks = List.of(
            new ReferenceQualityCheck(
                "total_return_floor",
                metrics.totalReturn().compareTo(MIN_TOTAL_RETURN) >= 0,
                metrics.totalReturn(),
                MIN_TOTAL_RETURN,
                "total return should not be worse than -10%"
            ),
            new ReferenceQualityCheck(
                "max_drawdown_ceiling",
                metrics.maxDrawdown().compareTo(MAX_DRAWDOWN) <= 0,
                metrics.maxDrawdown(),
                MAX_DRAWDOWN,
                "max drawdown should be within 20%"
            ),
            new ReferenceQualityCheck(
                "trade_count_floor",
                BigDecimal.valueOf(metrics.tradeCount()).compareTo(MIN_TRADE_COUNT) >= 0,
                BigDecimal.valueOf(metrics.tradeCount()),
                MIN_TRADE_COUNT,
                "strategy should produce at least one completed trade"
            )
        );

        boolean passed = checks.stream().allMatch(ReferenceQualityCheck::passed);
        return new ReferenceQualityReport(
            taskId,
            passed,
            checks,
            suggestions(checks),
            LocalDateTime.now()
        );
    }

    private List<String> suggestions(List<ReferenceQualityCheck> checks) {
        List<String> suggestions = new ArrayList<>();
        for (ReferenceQualityCheck check : checks) {
            if (check.passed()) {
                continue;
            }
            switch (check.name()) {
                case "total_return_floor" -> suggestions.add("回测收益过低：先检查行情样本是否太短，再尝试增大 slowWindow 降低噪声交易。");
                case "max_drawdown_ceiling" -> suggestions.add("最大回撤过高：降低 maxPositionRatio，或收紧 stopLossRatio。");
                case "trade_count_floor" -> suggestions.add("交易次数不足：缩小 fastWindow/slowWindow 间距，或扩大回测时间范围。");
                default -> suggestions.add("检查未通过：" + check.message());
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("质检通过：可以进入参数对比或接入 Agent 解释层。");
        }
        return suggestions;
    }
}
