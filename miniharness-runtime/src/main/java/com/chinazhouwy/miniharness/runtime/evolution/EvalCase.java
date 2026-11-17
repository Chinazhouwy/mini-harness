package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测案例：外部给定期望的受控场景。期望值来自案例定义（人写/外部事实），
 * 绝不用被测流程自己的输出当答案——这是「评测基于实际测试」的关键。
 * injectedDefectKind 标明该案例故意注入的缺陷类型（用于失败轨迹的归因标注）。
 */
public record EvalCase(
        String caseId,
        String description,
        String split, // TRAIN 或 HOLDOUT
        Map<String, Object> workflowInput,
        Expected expected,
        String metricKey,          // CORRECT_REPORT 时从 run 输出提取的指标键
        Double metricValue,        // 期望指标值
        Set<String> expectedErrorKinds, // REJECTED 时可接受的错误码集合
        String injectedDefectKind, // 该案例注入的缺陷类型（可为 null）
        String metricProducerNode  // 产出指标/错误结果的节点 ID（用于失败归因标注）
) {
    public enum Expected { CORRECT_REPORT, REJECTED }

    public EvalCase {
        workflowInput = workflowInput == null ? Map.of() : Map.copyOf(workflowInput);
        expectedErrorKinds = expectedErrorKinds == null ? Set.of() : Set.copyOf(expectedErrorKinds);
    }

    public static EvalCase correctReport(String caseId, String description, String split,
                                         Map<String, Object> input, String metricKey, double metricValue,
                                         String metricProducerNode) {
        return new EvalCase(caseId, description, split, input, Expected.CORRECT_REPORT,
                metricKey, metricValue, Set.of(), null, metricProducerNode);
    }

    public static EvalCase rejected(String caseId, String description, String split,
                                    Map<String, Object> input, Set<String> errorKinds,
                                    String injectedDefectKind, String metricProducerNode) {
        return new EvalCase(caseId, description, split, input, Expected.REJECTED,
                null, null, errorKinds, injectedDefectKind, metricProducerNode);
    }
}
