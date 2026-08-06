package com.chinazhouwy.miniharness.finance.research.domain;

import java.time.LocalDate;
import java.util.Objects;

/** 对齐后的单日价格与杠杆观测。杠杆值保留源数据单位，计算层再做标准化。 */
public record MarketObservation(
        LocalDate tradeDate,
        double open,
        double high,
        double low,
        double close,
        double leverageValue,
        boolean leverageImputed
) {

    public MarketObservation {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        requirePositive("open", open);
        requirePositive("high", high);
        requirePositive("low", low);
        requirePositive("close", close);
        if (high < Math.max(open, close) || low > Math.min(open, close) || low > high) {
            throw new IllegalArgumentException("OHLC values are inconsistent");
        }
        if (!Double.isFinite(leverageValue) || leverageValue < 0) {
            throw new IllegalArgumentException("leverageValue must be a non-negative finite number");
        }
    }

    /** 仅兼容旧的确定性调用方；真实 K 线必须使用完整 OHLC。 */
    public MarketObservation(LocalDate tradeDate, double close, double leverageValue, boolean leverageImputed) {
        this(tradeDate, close, close, close, close, leverageValue, leverageImputed);
    }

    private static void requirePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be a positive finite number");
        }
    }
}
