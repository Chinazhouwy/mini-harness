package com.chinazhouwy.miniharness.finance.research.calculation;

import com.chinazhouwy.miniharness.finance.research.data.FixtureMarketResearchDataSource;
import com.chinazhouwy.miniharness.finance.research.domain.EquationFitResult;
import com.chinazhouwy.miniharness.finance.research.domain.MarketSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeveragePriceFitCalculatorTest {

    @Test
    void selectsAnEquationAndKeepsTheLaterPeriodForValidation() {
        MarketSeries series = new FixtureMarketResearchDataSource().load().series().getFirst();

        EquationFitResult result = new LeveragePriceFitCalculator().fit(series);

        assertEquals(259, result.sampleCount());
        assertTrue(result.trainCount() < result.sampleCount());
        assertTrue(result.validationRmsePct() >= 0);
        assertTrue(result.validationCorrelation() >= -1 && result.validationCorrelation() <= 1);
        assertFalse(result.equation().isBlank());
        assertEquals(result.sampleCount(), result.points().size());
        assertTrue(result.points().stream().anyMatch(point -> point.validationPoint()));
    }
}
