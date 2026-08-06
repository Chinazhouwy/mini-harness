package com.chinazhouwy.miniharness.finance.research.data;

import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;
import com.chinazhouwy.miniharness.finance.research.domain.MarketInstrument;
import com.chinazhouwy.miniharness.finance.research.domain.MarketObservation;
import com.chinazhouwy.miniharness.finance.research.domain.MarketResearchDataset;
import com.chinazhouwy.miniharness.finance.research.domain.MarketSeries;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 只用于计算测试和断网开发的确定性数据，不作为页面真实数据降级源。 */
public final class FixtureMarketResearchDataSource implements MarketResearchDataSource {

    private static final Instant RETRIEVED_AT = Instant.parse("2026-08-05T00:00:00Z");
    private static final int OBSERVATIONS = 280;

    @Override
    public MarketResearchDataset load() {
        List<MarketSeries> series = List.of(
                create("a-share", "A股", "sh000001", "上证综指", "CNY", "MARGIN_BALANCE", "万亿元", 3200, 1.5, 0.004),
                create("hong-kong", "港股", "hk00700", "腾讯控股", "HKD", "SHORT_RATIO", "%", 390, 12, 0.006),
                create("united-states", "美股", "usAAPL", "Apple", "USD", "SHORT_RATIO", "%", 210, 1.2, 0.003)
        );
        List<SourceEvidence> sources = series.stream().flatMap(value -> value.sources().stream()).toList();
        return new MarketResearchDataset(
                RETRIEVED_AT,
                series,
                sources,
                List.of("韩股: Fixture 也不伪造未经验证的韩国短卖源")
        );
    }

    private static MarketSeries create(
            String id,
            String market,
            String symbol,
            String name,
            String currency,
            String leverageType,
            String leverageUnit,
            double initialClose,
            double initialLeverage,
            double leverageAmplitude
    ) {
        MarketInstrument instrument = new MarketInstrument(
                id,
                market,
                symbol,
                name,
                currency,
                leverageType,
                leverageUnit,
                "Fixture " + leverageType
        );
        LocalDate date = LocalDate.of(2025, 1, 2);
        double close = initialClose;
        double leverage = initialLeverage;
        List<MarketObservation> observations = new ArrayList<>();
        for (int index = 0; index < OBSERVATIONS; index++) {
            if (index > 0) date = nextBusinessDay(date);
            double nextLeverage = initialLeverage
                    + index * leverageAmplitude
                    + Math.sin(index / 13.0) * leverageAmplitude * 4;
            double returnValue = 0.0005
                    - (nextLeverage - leverage) * 0.55
                    + Math.sin(index / 17.0) * 0.004;
            double open = close;
            close *= Math.exp(returnValue);
            double intradayRange = 0.002 + Math.abs(Math.sin(index / 9.0)) * 0.003;
            double high = Math.max(open, close) * (1 + intradayRange);
            double low = Math.min(open, close) * (1 - intradayRange);
            observations.add(new MarketObservation(date, open, high, low, close, nextLeverage, false));
            leverage = nextLeverage;
        }
        SourceEvidence evidence = new SourceEvidence(
                "fixture-research-" + id,
                "Fixture " + market + " 杠杆与价格样本",
                "fixture://market-research/" + id,
                observations.getLast().tradeDate(),
                RETRIEVED_AT,
                "open,high,low,close,leverageValue",
                currency + " / " + leverageUnit,
                "仅用于验证方程、JSON 序列化和前端图表"
        );
        return new MarketSeries(instrument, observations, List.of(evidence));
    }

    private static LocalDate nextBusinessDay(LocalDate current) {
        LocalDate next = current.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }
}
