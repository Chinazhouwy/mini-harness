package com.chinazhouwy.miniharness.finance.market.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 杠杆过热、出清和止跌确认的可解释评分。分数范围为 0 到 100。 */
public record LeverageScoreSnapshot(
        LocalDate tradeDate,
        double leveragePercentile,
        double buyIntensityPercentile,
        double marginGrowth60Percentile,
        double clearancePercentile,
        double repaymentPercentile,
        double volatilityPercentile,
        double heatScore,
        double clearScore,
        double confirmationScore,
        MarketRegime regime,
        List<String> evidence
) {

    public LeverageScoreSnapshot {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        regime = Objects.requireNonNull(regime, "regime");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        requireScore("leveragePercentile", leveragePercentile);
        requireScore("buyIntensityPercentile", buyIntensityPercentile);
        requireScore("marginGrowth60Percentile", marginGrowth60Percentile);
        requireScore("clearancePercentile", clearancePercentile);
        requireScore("repaymentPercentile", repaymentPercentile);
        requireScore("volatilityPercentile", volatilityPercentile);
        requireScore("heatScore", heatScore);
        requireScore("clearScore", clearScore);
        requireScore("confirmationScore", confirmationScore);
    }

    private static void requireScore(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
