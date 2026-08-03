package com.chinazhouwy.miniharness.finance.market.domain;

import java.time.LocalDate;
import java.util.Objects;

/** 经过确定性计算后的单日杠杆指标。所有比例为小数，报告层再转换为百分比。 */
public record LeverageMetricSnapshot(
        LocalDate tradeDate,
        long financingBalanceYuan,
        long financingBuyYuan,
        long financingRepaymentYuan,
        long circulatingMarketCapYuan,
        long marketTurnoverYuan,
        double leverageRatio,
        double buyIntensity,
        double marginGrowth5,
        double marginGrowth20,
        double marginGrowth60,
        double clearanceFromPeak250,
        double netRepaymentPressure20,
        double realizedVolatility20,
        double indexClose,
        double indexLow20,
        double upRatio,
        double breadthChange20
) {

    public LeverageMetricSnapshot {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        requireFinite("leverageRatio", leverageRatio);
        requireFinite("buyIntensity", buyIntensity);
        requireFinite("marginGrowth5", marginGrowth5);
        requireFinite("marginGrowth20", marginGrowth20);
        requireFinite("marginGrowth60", marginGrowth60);
        requireFinite("clearanceFromPeak250", clearanceFromPeak250);
        requireFinite("netRepaymentPressure20", netRepaymentPressure20);
        requireFinite("realizedVolatility20", realizedVolatility20);
        requireFinite("indexClose", indexClose);
        requireFinite("indexLow20", indexLow20);
        requireFinite("upRatio", upRatio);
        requireFinite("breadthChange20", breadthChange20);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
