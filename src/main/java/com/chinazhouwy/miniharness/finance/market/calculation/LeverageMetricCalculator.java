package com.chinazhouwy.miniharness.finance.market.calculation;

import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.MarketLeverageDataset;
import com.chinazhouwy.miniharness.finance.market.domain.MarketLeverageObservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 将原始市场数据转换成可解释的杠杆指标序列。 */
public final class LeverageMetricCalculator {

    private final int growthShortWindow;
    private final int growthMediumWindow;
    private final int growthLongWindow;
    private final int peakWindow;
    private final int repaymentWindow;
    private final int volatilityWindow;

    public LeverageMetricCalculator() {
        this(5, 20, 60, 250, 20, 20);
    }

    public LeverageMetricCalculator(
            int growthShortWindow,
            int growthMediumWindow,
            int growthLongWindow,
            int peakWindow,
            int repaymentWindow,
            int volatilityWindow
    ) {
        this.growthShortWindow = positive(growthShortWindow, "growthShortWindow");
        this.growthMediumWindow = positive(growthMediumWindow, "growthMediumWindow");
        this.growthLongWindow = positive(growthLongWindow, "growthLongWindow");
        this.peakWindow = positive(peakWindow, "peakWindow");
        this.repaymentWindow = positive(repaymentWindow, "repaymentWindow");
        this.volatilityWindow = positive(volatilityWindow, "volatilityWindow");
        if (this.growthShortWindow > this.growthMediumWindow
                || this.growthMediumWindow > this.growthLongWindow) {
            throw new IllegalArgumentException("Growth windows must be ordered from short to long");
        }
    }

    public List<LeverageMetricSnapshot> calculate(MarketLeverageDataset dataset) {
        List<MarketLeverageObservation> observations = dataset.observations().stream()
                .sorted(Comparator.comparing(MarketLeverageObservation::tradeDate))
                .toList();
        int firstCalculableIndex = Math.max(
                Math.max(growthLongWindow, peakWindow - 1),
                Math.max(repaymentWindow - 1, volatilityWindow - 1)
        );
        if (observations.size() <= firstCalculableIndex) {
            throw new IllegalArgumentException(
                    "Not enough observations: need more than " + firstCalculableIndex
                            + ", got " + observations.size()
            );
        }

        List<LeverageMetricSnapshot> result = new ArrayList<>();
        for (int index = firstCalculableIndex; index < observations.size(); index++) {
            MarketLeverageObservation current = observations.get(index);
            result.add(new LeverageMetricSnapshot(
                    current.tradeDate(),
                    current.financingBalanceYuan(),
                    current.financingBuyYuan(),
                    current.financingRepaymentYuan(),
                    current.circulatingMarketCapYuan(),
                    current.marketTurnoverYuan(),
                    ratio(current.financingBalanceYuan(), current.circulatingMarketCapYuan()),
                    ratio(current.financingBuyYuan(), current.marketTurnoverYuan()),
                    growth(observations, index, growthShortWindow),
                    growth(observations, index, growthMediumWindow),
                    growth(observations, index, growthLongWindow),
                    clearanceFromPeak(observations, index),
                    netRepaymentPressure(observations, index),
                    realizedVolatility(observations, index),
                    current.indexClose(),
                    lowestIndexClose(observations, index, repaymentWindow),
                    current.upRatio(),
                    current.upRatio() - observations.get(index - repaymentWindow).upRatio()
            ));
        }
        return List.copyOf(result);
    }

    private double growth(List<MarketLeverageObservation> observations, int index, int window) {
        long previous = observations.get(index - window).financingBalanceYuan();
        if (previous == 0) {
            throw new IllegalArgumentException("Cannot calculate growth from zero financing balance");
        }
        return (double) observations.get(index).financingBalanceYuan() / previous - 1;
    }

    private double clearanceFromPeak(List<MarketLeverageObservation> observations, int index) {
        int start = Math.max(0, index - peakWindow + 1);
        long peak = observations.subList(start, index + 1).stream()
                .mapToLong(MarketLeverageObservation::financingBalanceYuan)
                .max()
                .orElseThrow();
        return peak == 0 ? 0 : 1 - (double) observations.get(index).financingBalanceYuan() / peak;
    }

    private double netRepaymentPressure(List<MarketLeverageObservation> observations, int index) {
        int start = Math.max(0, index - repaymentWindow + 1);
        long netRepayment = observations.subList(start, index + 1).stream()
                .mapToLong(observation -> observation.financingRepaymentYuan() - observation.financingBuyYuan())
                .sum();
        double averageBalance = observations.subList(start, index + 1).stream()
                .mapToLong(MarketLeverageObservation::financingBalanceYuan)
                .average()
                .orElseThrow();
        return averageBalance == 0 ? 0 : netRepayment / averageBalance;
    }

    private double realizedVolatility(List<MarketLeverageObservation> observations, int index) {
        int start = Math.max(0, index - volatilityWindow + 1);
        List<Double> returns = observations.subList(start, index + 1).stream()
                .map(MarketLeverageObservation::indexReturnPct)
                .toList();
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0);
        return Math.sqrt(variance) / 100 * Math.sqrt(252);
    }

    private double lowestIndexClose(
            List<MarketLeverageObservation> observations,
            int index,
            int window
    ) {
        int start = Math.max(0, index - window + 1);
        return observations.subList(start, index + 1).stream()
                .mapToDouble(MarketLeverageObservation::indexClose)
                .min()
                .orElseThrow();
    }

    private static double ratio(long numerator, long denominator) {
        return (double) numerator / denominator;
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
