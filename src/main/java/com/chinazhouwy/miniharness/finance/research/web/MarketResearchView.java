package com.chinazhouwy.miniharness.finance.research.web;

import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;
import com.chinazhouwy.miniharness.finance.research.domain.EquationFitResult;
import com.chinazhouwy.miniharness.finance.research.domain.MarketInstrument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 跨市场杠杆与 K 线拟合的只读 API 视图。 */
public record MarketResearchView(
        Instant retrievedAt,
        String status,
        List<MarketSeriesView> markets,
        List<SourceEvidence> sources
) {

    public MarketResearchView {
        retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        status = Objects.requireNonNull(status, "status");
        markets = List.copyOf(Objects.requireNonNull(markets, "markets"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    public record MarketSeriesView(
            MarketInstrument instrument,
            LocalDate from,
            LocalDate to,
            int observationCount,
            int imputedLeverageCount,
            double latestClose,
            double latestLeverage,
            EquationFitResult fit,
            List<SourceEvidence> sources
    ) {

        public MarketSeriesView {
            instrument = Objects.requireNonNull(instrument, "instrument");
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
            fit = Objects.requireNonNull(fit, "fit");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        }
    }
}
