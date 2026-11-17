package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.List;

/**
 * 评测门禁策略：新版本要发布必须同时满足这些硬条件。
 * 阈值集中声明，保证「发布条件」可审计、可调整。
 */
public record EvalGatePolicy(
        double minErrorDetectionRate,
        double maxExtraAvgToolCallsPerCase,
        boolean requireNoHoldoutRegressions,
        boolean requireTrainAccuracyNotWorse
) {
    public static EvalGatePolicy defaults() {
        return new EvalGatePolicy(0.5, 4.0, true, true);
    }
}
