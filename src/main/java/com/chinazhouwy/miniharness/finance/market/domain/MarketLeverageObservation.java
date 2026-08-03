package com.chinazhouwy.miniharness.finance.market.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 一个交易日的公开可观测市场杠杆输入。金额统一使用元。 */
public record MarketLeverageObservation(
        LocalDate tradeDate,
        long financingBalanceYuan,
        long financingBuyYuan,
        long financingRepaymentYuan,
        long circulatingMarketCapYuan,
        long marketTurnoverYuan,
        double indexClose,
        double indexReturnPct,
        double upRatio,
        double below60MaRatio,
        List<SourceEvidence> sources
) {

    public MarketLeverageObservation {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        if (financingBalanceYuan < 0 || financingBuyYuan < 0 || financingRepaymentYuan < 0) {
            throw new IllegalArgumentException("Financing amounts must not be negative");
        }
        if (circulatingMarketCapYuan <= 0 || marketTurnoverYuan <= 0) {
            throw new IllegalArgumentException("Market cap and turnover must be positive");
        }
        if (!Double.isFinite(indexClose) || indexClose <= 0) {
            throw new IllegalArgumentException("Index close must be a positive finite number");
        }
        if (!Double.isFinite(indexReturnPct)) {
            throw new IllegalArgumentException("Index return must be finite");
        }
        requireRatio("upRatio", upRatio);
        requireRatio("below60MaRatio", below60MaRatio);
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Every observation must have source evidence");
        }
    }

    private static void requireRatio(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
