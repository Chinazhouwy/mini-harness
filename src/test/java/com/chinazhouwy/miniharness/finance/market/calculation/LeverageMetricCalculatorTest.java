package com.chinazhouwy.miniharness.finance.market.calculation;

import com.chinazhouwy.miniharness.finance.market.data.FixtureMarketLeverageDataSource;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeverageMetricCalculatorTest {

    @Test
    void fixtureProducesLongWindowMetricsWithExplicitUnits() {
        var dataset = new FixtureMarketLeverageDataSource().load();
        List<LeverageMetricSnapshot> metrics = new LeverageMetricCalculator().calculate(dataset);

        assertEquals(31, metrics.size());
        LeverageMetricSnapshot latest = metrics.getLast();
        assertEquals(dataset.observations().getLast().tradeDate(), latest.tradeDate());
        assertTrue(latest.leverageRatio() > 0.02 && latest.leverageRatio() < 0.03);
        assertTrue(latest.marginGrowth60() < 0);
        assertTrue(latest.clearanceFromPeak250() > 0.10);
        assertTrue(latest.netRepaymentPressure20() > 1.0);
        assertTrue(latest.upRatio() > 0.5);
    }

    @Test
    void percentileUsesTheCurrentValueAndDoesNotExceedBounds() {
        assertEquals(100.0, RollingPercentile.current(List.of(1.0, 2.0, 3.0), 10));
        assertEquals(87.5, RollingPercentile.current(List.of(1.0, 2.0, 3.0, 3.0), 10));
        assertTrue(RollingPercentile.current(List.of(3.0, 2.0, 1.0), 10) >= 0);
    }
}
