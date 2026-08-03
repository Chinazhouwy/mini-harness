package com.chinazhouwy.miniharness.finance.market.web;

import com.chinazhouwy.miniharness.finance.market.calculation.LeverageMetricCalculator;
import com.chinazhouwy.miniharness.finance.market.calculation.LeverageScoreCalculator;
import com.chinazhouwy.miniharness.finance.market.data.FixtureMarketLeverageDataSource;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.MarketLeverageDataset;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 给静态页面提供同一套后端计算结果。当前数据源是 Fixture，接口保持只读。 */
@RestController
@RequestMapping("/api/finance/leverage")
public final class LeverageDashboardController {

    private final FixtureMarketLeverageDataSource dataSource = new FixtureMarketLeverageDataSource();
    private final LeverageMetricCalculator metricCalculator = new LeverageMetricCalculator();
    private final LeverageScoreCalculator scoreCalculator = new LeverageScoreCalculator();

    @GetMapping
    public LeverageDashboardView getDashboard() {
        MarketLeverageDataset dataset = dataSource.load();
        List<LeverageMetricSnapshot> metrics = metricCalculator.calculate(dataset);
        return new LeverageDashboardView(
                dataset.market(),
                dataset.observations().stream().map(observation -> observation.tradeDate()).min(LocalDate::compareTo).orElseThrow(),
                metrics.getLast().tradeDate(),
                metrics.getLast(),
                scoreCalculator.calculate(metrics),
                metrics.stream().map(this::toHistoryPoint).toList(),
                dataset.sources()
        );
    }

    private LeverageHistoryPoint toHistoryPoint(LeverageMetricSnapshot metric) {
        return new LeverageHistoryPoint(
                metric.tradeDate(),
                metric.leverageRatio(),
                metric.clearanceFromPeak250(),
                metric.marginGrowth60(),
                metric.netRepaymentPressure20(),
                metric.upRatio(),
                metric.indexClose()
        );
    }
}
