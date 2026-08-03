package com.chinazhouwy.miniharness.finance.market.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一段可回放的市场杠杆数据集。 */
public record MarketLeverageDataset(
        String market,
        List<MarketLeverageObservation> observations,
        List<SourceEvidence> sources
) {

    public MarketLeverageDataset {
        if (market == null || market.isBlank()) {
            throw new IllegalArgumentException("Market must not be blank");
        }
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("Dataset must contain observations");
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Dataset must contain source evidence");
        }
        Set<java.time.LocalDate> dates = new HashSet<>();
        for (MarketLeverageObservation observation : observations) {
            if (!dates.add(observation.tradeDate())) {
                throw new IllegalArgumentException("Duplicate trade date: " + observation.tradeDate());
            }
        }
    }
}
