package com.chinazhouwy.miniharness.finance.market.calculation;

import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageScoreSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.MarketRegime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 将指标转换成可解释的过热、出清和止跌确认评分。 */
public final class LeverageScoreCalculator {

    private final int percentileWindow;

    public LeverageScoreCalculator() {
        this(1_250);
    }

    public LeverageScoreCalculator(int percentileWindow) {
        if (percentileWindow < 1) {
            throw new IllegalArgumentException("percentileWindow must be positive");
        }
        this.percentileWindow = percentileWindow;
    }

    public LeverageScoreSnapshot calculate(List<LeverageMetricSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            throw new IllegalArgumentException("Metric snapshots must not be empty");
        }
        LeverageMetricSnapshot latest = snapshots.getLast();
        double leveragePercentile = percentile(snapshots, value -> value.leverageRatio());
        double buyIntensityPercentile = percentile(snapshots, value -> value.buyIntensity());
        // 负增长代表去杠杆，不能因为它在“最近 31 个负值”中相对靠后，
        // 就被误判成 Heat。只有融资余额仍在扩张时，增长分位才参与过热评分。
        double marginGrowth60Percentile = latest.marginGrowth60() <= 0
                ? 0
                : percentile(snapshots, value -> value.marginGrowth60());
        double clearancePercentile = percentile(snapshots, value -> value.clearanceFromPeak250());
        double repaymentPercentile = percentile(snapshots, value -> value.netRepaymentPressure20());
        double volatilityPercentile = percentile(snapshots, value -> value.realizedVolatility20());

        double heatScore = weighted(
                leveragePercentile, 0.45,
                buyIntensityPercentile, 0.30,
                marginGrowth60Percentile, 0.25
        );
        double clearScore = weighted(
                clearancePercentile, 0.50,
                repaymentPercentile, 0.35,
                volatilityPercentile, 0.15
        );
        boolean declineSlowing = latest.marginGrowth20() < 0
                && latest.marginGrowth5() > latest.marginGrowth20() / 4;
        boolean indexStabilized = latest.indexClose() > latest.indexLow20() * 1.005;
        boolean breadthRecovering = latest.breadthChange20() > 0.02;
        double confirmationScore = (countTrue(declineSlowing, indexStabilized, breadthRecovering) / 3.0) * 100;
        MarketRegime regime = classify(heatScore, clearScore, confirmationScore, latest.marginGrowth60());

        List<String> evidence = new ArrayList<>();
        evidence.add("leveragePercentile=" + format(leveragePercentile));
        evidence.add("buyIntensityPercentile=" + format(buyIntensityPercentile));
        evidence.add("marginGrowth60Percentile=" + format(marginGrowth60Percentile));
        evidence.add("clearancePercentile=" + format(clearancePercentile));
        evidence.add("repaymentPercentile=" + format(repaymentPercentile));
        evidence.add("volatilityPercentile=" + format(volatilityPercentile));
        evidence.add("declineSlowing=" + declineSlowing);
        evidence.add("indexStabilized=" + indexStabilized);
        evidence.add("breadthRecovering=" + breadthRecovering);

        return new LeverageScoreSnapshot(
                latest.tradeDate(),
                leveragePercentile,
                buyIntensityPercentile,
                marginGrowth60Percentile,
                clearancePercentile,
                repaymentPercentile,
                volatilityPercentile,
                heatScore,
                clearScore,
                confirmationScore,
                regime,
                evidence
        );
    }

    private double percentile(
            List<LeverageMetricSnapshot> snapshots,
            java.util.function.ToDoubleFunction<LeverageMetricSnapshot> extractor
    ) {
        return RollingPercentile.current(
                snapshots.stream().map(value -> extractor.applyAsDouble(value)).toList(),
                percentileWindow
        );
    }

    private static double weighted(double first, double firstWeight, double second, double secondWeight, double third, double thirdWeight) {
        return first * firstWeight + second * secondWeight + third * thirdWeight;
    }

    private static int countTrue(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static MarketRegime classify(
            double heatScore,
            double clearScore,
            double confirmationScore,
            double marginGrowth60
    ) {
        if (heatScore >= 80 && marginGrowth60 > 0) {
            return MarketRegime.OVERHEATED;
        }
        if (clearScore >= 70 && confirmationScore >= 60) {
            return MarketRegime.POTENTIAL_CLEARANCE;
        }
        if (clearScore >= 70) {
            return MarketRegime.CLEARANCE_UNCONFIRMED;
        }
        if (clearScore >= 50 || marginGrowth60 < 0) {
            return MarketRegime.DELEVERAGING;
        }
        if (heatScore <= 30) {
            return MarketRegime.LOW_LEVERAGE;
        }
        return MarketRegime.NORMAL;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
