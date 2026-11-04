package com.chinazhouwy.miniharness.finance.research.domain;

import java.time.LocalDate;
import java.util.Objects;

/** 方程在某一交易日给出的价格路径与实际价格。 */
public record EquationFitPoint(
        LocalDate tradeDate,
        double actualClose,
        double fittedClose,
        double actualReturnPct,
        double predictedReturnPct,
        double leverageValue,
        boolean leverageImputed,
        boolean validationPoint
) {

    public EquationFitPoint {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        requirePositive("actualClose", actualClose);
        requirePositive("fittedClose", fittedClose);
        requireFinite("actualReturnPct", actualReturnPct);
        requireFinite("predictedReturnPct", predictedReturnPct);
        requireFinite("leverageValue", leverageValue);
    }

    private static void requirePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be a positive finite number");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
