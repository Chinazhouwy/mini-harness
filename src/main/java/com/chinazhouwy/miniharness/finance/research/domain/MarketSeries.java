package com.chinazhouwy.miniharness.finance.research.domain;

import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一个市场标的的已对齐价格和杠杆时间序列。 */
public record MarketSeries(
        MarketInstrument instrument,
        List<MarketObservation> observations,
        List<SourceEvidence> sources
) {

    public MarketSeries {
        instrument = Objects.requireNonNull(instrument, "instrument");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (observations.size() < 12) {
            throw new IllegalArgumentException("Market series must contain at least 12 observations");
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Market series must contain source evidence");
        }
        Set<LocalDate> dates = new HashSet<>();
        LocalDate previous = null;
        for (MarketObservation observation : observations) {
            if (!dates.add(observation.tradeDate())) {
                throw new IllegalArgumentException("Duplicate trade date: " + observation.tradeDate());
            }
            if (previous != null && observation.tradeDate().isBefore(previous)) {
                throw new IllegalArgumentException("Market observations must be sorted by trade date");
            }
            previous = observation.tradeDate();
        }
    }
}
