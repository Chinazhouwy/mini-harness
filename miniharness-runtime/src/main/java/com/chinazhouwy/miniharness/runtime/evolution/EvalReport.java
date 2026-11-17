package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.List;
import java.util.Map;

/**
 * 版本对比评测报告：基线与候选在同一案例集上的真实执行指标 + 门禁判定。
 * 所有指标来自 CaseRunner 的真实执行，不采信任何「模型自称成功」。
 */
public record EvalReport(
        String candidateId,
        List<CaseRunner.CaseRunResult> baselineResults,
        List<CaseRunner.CaseRunResult> candidateResults,
        Metrics baselineHoldout,
        Metrics candidateHoldout,
        Metrics baselineTrain,
        Metrics candidateTrain,
        boolean passed,
        List<GateResult> gateResults
) {
    /** 单版本聚合指标。 */
    public record Metrics(
            int totalCases,
            int passedCases,
            double accuracy,
            double errorDetectionRate,
            int errorDetectionCases,
            double avgDurationMs,
            double avgToolCalls,
            List<String> failedCaseIds
    ) {
    }

    /** 单条门禁判定。 */
    public record GateResult(String rule, boolean passed, String detail) {
    }
}
