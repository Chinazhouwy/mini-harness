package com.chinazhouwy.miniharness.finance.research.domain;

import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 一次跨市场研究采集的可回放数据集。 */
public record MarketResearchDataset(
        Instant retrievedAt,
        List<MarketSeries> series,
        List<SourceEvidence> sources,
        List<String> unavailableMarkets
) {

    public MarketResearchDataset {
        retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        series = List.copyOf(Objects.requireNonNull(series, "series"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        unavailableMarkets = List.copyOf(Objects.requireNonNull(unavailableMarkets, "unavailableMarkets"));
        if (series.isEmpty()) {
            throw new IllegalArgumentException("Research dataset must contain a market series");
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Research dataset must contain source evidence");
        }
    }
}
