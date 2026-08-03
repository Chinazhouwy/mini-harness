package com.chinazhouwy.miniharness.finance.market.calculation;

import java.util.List;

/** 用历史窗口把不同量纲的指标统一到 0 到 100。 */
public final class RollingPercentile {

    private RollingPercentile() {
    }

    public static double current(List<Double> values, int window) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Percentile input must not be empty");
        }
        if (window < 1) {
            throw new IllegalArgumentException("Percentile window must be positive");
        }

        int start = Math.max(0, values.size() - window);
        double current = values.getLast();
        int less = 0;
        int equal = 0;
        int count = 0;
        for (int index = start; index < values.size(); index++) {
            double value = values.get(index);
            if (!Double.isFinite(value)) {
                continue;
            }
            count++;
            int comparison = Double.compare(value, current);
            if (comparison < 0) {
                less++;
            } else if (comparison == 0) {
                equal++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("Percentile input contains no finite values");
        }
        return (less + (equal + 1) / 2.0) / count * 100;
    }
}
