package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.List;
import java.util.Set;

/**
 * 轨迹分析器策略：触发阈值全部显式配置，避免「拍脑袋阈值」散落在代码里。
 */
public record AnalyzerPolicy(
        int minOccurrencesForValidationNode,
        int minRunsForRemoval,
        int minSuccessfulRunsForSkill,
        String validationNodeType,
        String validationNodeId
) {
    public static AnalyzerPolicy financeDefaults() {
        return new AnalyzerPolicy(2, 3, 3, "data-quality-validation", "data-quality-validation");
    }

    public AnalyzerPolicy {
        if (minOccurrencesForValidationNode < 1) {
            minOccurrencesForValidationNode = 1;
        }
        if (minRunsForRemoval < 2) {
            minRunsForRemoval = 2;
        }
        if (minSuccessfulRunsForSkill < 2) {
            minSuccessfulRunsForSkill = 2;
        }
        validationNodeType = validationNodeType == null ? "data-quality-validation" : validationNodeType;
        validationNodeId = validationNodeId == null ? "data-quality-validation" : validationNodeId;
    }

    public Set<String> supportedFailureKinds() {
        return FailureKinds.DATA_QUALITY_KINDS;
    }
}
