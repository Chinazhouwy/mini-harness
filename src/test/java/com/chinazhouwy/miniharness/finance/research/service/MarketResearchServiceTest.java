package com.chinazhouwy.miniharness.finance.research.service;

import com.chinazhouwy.miniharness.finance.research.calculation.LeveragePriceFitCalculator;
import com.chinazhouwy.miniharness.finance.research.data.FixtureMarketResearchDataSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MarketResearchServiceTest {

    @Test
    void mapsAllFixtureMarketsAndCachesTheCalculatedView() {
        MarketResearchService service = new MarketResearchService(
                new FixtureMarketResearchDataSource(),
                new LeveragePriceFitCalculator(),
                Duration.ofMinutes(5)
        );

        var first = service.getResearch();
        var second = service.getResearch();

        assertEquals("REAL_DATA", first.status());
        assertEquals(3, first.markets().size());
        assertSame(first, second);
        assertEquals("sh000001", first.markets().getFirst().instrument().symbol());
        assertEquals(1, first.unavailableMarkets().size());
        assertEquals(280, first.markets().getFirst().candles().size());
        assertEquals(3, first.markets().getFirst().fit().candidates().size());
    }
}
