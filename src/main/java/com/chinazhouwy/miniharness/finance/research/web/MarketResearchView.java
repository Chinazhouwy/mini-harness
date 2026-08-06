package com.chinazhouwy.miniharness.finance.research.web;

import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;
import com.chinazhouwy.miniharness.finance.research.domain.EquationFitResult;
import com.chinazhouwy.miniharness.finance.research.domain.MarketInstrument;
import com.chinazhouwy.miniharness.finance.research.domain.MarketObservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 跨市场杠杆与 K 线拟合的只读 API 视图。 */
public record MarketResearchView(
        Instant retrievedAt,
        String status,
        List<MarketSeriesView> markets,
        List<SourceEvidence> sources,
        List<String> unavailableMarkets
) {

    public MarketResearchView {
        retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        status = Objects.requireNonNull(status, "status");
        markets = List.copyOf(Objects.requireNonNull(markets, "markets"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        unavailableMarkets = List.copyOf(Objects.requireNonNull(unavailableMarkets, "unavailableMarkets"));
    }

    public record MarketSeriesView(
            MarketInstrument instrument,
            LocalDate from,
            LocalDate to,
            int observationCount,
            int imputedLeverageCount,
            double latestClose,
            double latestLeverage,
            List<PriceBarView> candles,
            EquationFitResult fit,
            List<SourceEvidence> sources
    ) {

        public MarketSeriesView {
            instrument = Objects.requireNonNull(instrument, "instrument");
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
            candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
            fit = Objects.requireNonNull(fit, "fit");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        }
    }

    public record PriceBarView(
            LocalDate tradeDate,
            double open,
            double high,
            double low,
            double close,
            double leverageValue,
            boolean leverageImputed
    ) {

        public PriceBarView {
            tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        }

        public static PriceBarView from(MarketObservation observation) {
            return new PriceBarView(
                    observation.tradeDate(),
                    observation.open(),
                    observation.high(),
                    observation.low(),
                    observation.close(),
                    observation.leverageValue(),
                    observation.leverageImputed()
            );
        }
    }
}
