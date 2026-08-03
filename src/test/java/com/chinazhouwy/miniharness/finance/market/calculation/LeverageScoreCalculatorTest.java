package com.chinazhouwy.miniharness.finance.market.calculation;

import com.chinazhouwy.miniharness.finance.market.data.FixtureMarketLeverageDataSource;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageScoreSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeverageScoreCalculatorTest {

    @Test
    void fixtureEndsWithClearanceAndConfirmationRatherThanAnOrder() {
        var dataset = new FixtureMarketLeverageDataSource().load();
        var metrics = new LeverageMetricCalculator().calculate(dataset);
        LeverageScoreSnapshot score = new LeverageScoreCalculator().calculate(metrics);

        assertEquals(9, score.evidence().size());
        assertEquals("POTENTIAL_CLEARANCE", score.regime().name());
        assertTrue(score.clearScore() >= 70);
        assertTrue(score.confirmationScore() >= 60);
        assertTrue(score.heatScore() >= 0 && score.heatScore() <= 100);
    }
}
