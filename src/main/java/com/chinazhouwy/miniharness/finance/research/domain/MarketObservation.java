package com.chinazhouwy.miniharness.finance.research.domain;

import java.time.LocalDate;
import java.util.Objects;

/** 对齐后的单日价格与杠杆观测。杠杆值保留源数据单位，计算层再做标准化。 */
public record MarketObservation(
        LocalDate tradeDate,
        double close,
        double leverageValue,
        boolean leverageImputed
) {

    public MarketObservation {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        if (!Double.isFinite(close) || close <= 0) {
            throw new IllegalArgumentException("close must be a positive finite number");
        }
        if (!Double.isFinite(leverageValue) || leverageValue < 0) {
            throw new IllegalArgumentException("leverageValue must be a non-negative finite number");
        }
    }
}
