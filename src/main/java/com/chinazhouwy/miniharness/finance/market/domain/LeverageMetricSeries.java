package com.chinazhouwy.miniharness.finance.market.domain;

import java.util.List;
import java.util.Objects;

/** 可持久化的指标序列 Artifact。 */
public record LeverageMetricSeries(List<LeverageMetricSnapshot> snapshots) {

    public LeverageMetricSeries {
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("Metric series must not be empty");
        }
    }
}
