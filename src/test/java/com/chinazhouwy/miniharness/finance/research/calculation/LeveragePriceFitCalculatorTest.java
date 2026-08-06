package com.chinazhouwy.miniharness.finance.research.calculation;

import com.chinazhouwy.miniharness.finance.research.data.FixtureMarketResearchDataSource;
import com.chinazhouwy.miniharness.finance.research.domain.EquationFitResult;
import com.chinazhouwy.miniharness.finance.research.domain.MarketObservation;
import com.chinazhouwy.miniharness.finance.research.domain.MarketSeries;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

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

    @Test
    void validationTailCannotChangeTrainingEquation() {
        MarketSeries original = new FixtureMarketResearchDataSource().load().series().getFirst();
        MarketSeries changedTail = new MarketSeries(
                original.instrument(),
                IntStream.range(0, original.observations().size())
                        .mapToObj(index -> scaleTail(original.observations().get(index), index >= 220 ? 2.0 : 1.0))
                        .toList(),
                original.sources()
        );

        LeveragePriceFitCalculator calculator = new LeveragePriceFitCalculator();
        EquationFitResult first = calculator.fit(original);
        EquationFitResult second = calculator.fit(changedTail);

        assertEquals(
                first.candidates().stream().map(EquationFitResult.CandidateFit::equation).toList(),
                second.candidates().stream().map(EquationFitResult.CandidateFit::equation).toList()
        );
        assertEquals(
                first.candidates().stream().map(EquationFitResult.CandidateFit::trainRmsePct).toList(),
                second.candidates().stream().map(EquationFitResult.CandidateFit::trainRmsePct).toList()
        );
    }

    private static MarketObservation scaleTail(MarketObservation observation, double factor) {
        return new MarketObservation(
                observation.tradeDate(),
                observation.open() * factor,
                observation.high() * factor,
                observation.low() * factor,
                observation.close() * factor,
                observation.leverageValue(),
                observation.leverageImputed()
        );
    }
}
