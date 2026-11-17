package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.capability.RuleIntentExtractor;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCase;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCaseSet;
import com.chinazhouwy.miniharness.runtime.evolution.FailureKinds;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowEdge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 金融研究 Domain Pack：Workflow v1/v2 定义与受控案例集。
 * 全部为合成数据；真实行情/交易不在 v0.1 范围。
 */
public final class FinancePack {

    public static final String WORKFLOW_NAME = "profitability-research";
    public static final String INTENT = RuleIntentExtractor.INTENT_PROFITABILITY_RESEARCH;
    /** 干净训练案例上的期望汇总净利率：(100000+120000)/(1000000+1000000) = 0.11。 */
    public static final double EXPECTED_MARGIN_TRAIN = 0.11;
    /** 留出集干净案例期望值：(200000+260000)/(2000000+2000000) = 0.115。 */
    public static final double EXPECTED_MARGIN_HOLDOUT = 0.115;

    private FinancePack() {
    }

    /**
     * Workflow v1：读取 → 计算利润率 → 生成报告。
     * 故意不含数据口径验证——这是第一个自进化实验要发现并修复的缺陷。
     */
    public static WorkflowDefinition workflowV1(int version) {
        return new WorkflowDefinition(new WorkflowRef(WORKFLOW_NAME, version), INTENT,
                "盈利能力研究 v1（读取→计算→报告；无口径校验）",
                List.of(
                        NodeSpec.of("load-financials", LoadFinancialsHandler.TYPE),
                        NodeSpec.of("compute-margins", ComputeMarginsHandler.TYPE),
                        NodeSpec.of("generate-report", GenerateReportHandler.TYPE,
                                Map.of("computeNode", "compute-margins"))),
                List.of(
                        WorkflowEdge.of("load-financials", "compute-margins"),
                        WorkflowEdge.of("compute-margins", "generate-report")));
    }

    /** Workflow v2 静态定义（用于对照展示；实际发布走候选+评测+审批流程）。 */
    public static WorkflowDefinition workflowV2(int version) {
        return new WorkflowDefinition(new WorkflowRef(WORKFLOW_NAME, version), INTENT,
                "盈利能力研究 v2（新增数据口径验证节点）",
                List.of(
                        NodeSpec.of("load-financials", LoadFinancialsHandler.TYPE),
                        NodeSpec.of("data-quality-validation", DataQualityValidationHandler.TYPE,
                                Map.of("loadNode", "load-financials")),
                        NodeSpec.of("compute-margins", ComputeMarginsHandler.TYPE),
                        NodeSpec.of("generate-report", GenerateReportHandler.TYPE,
                                Map.of("computeNode", "compute-margins"))),
                List.of(
                        WorkflowEdge.of("load-financials", "data-quality-validation"),
                        WorkflowEdge.of("load-financials", "compute-margins"),
                        WorkflowEdge.of("data-quality-validation", "compute-margins"),
                        WorkflowEdge.of("compute-margins", "generate-report")));
    }

    /**
     * 受控案例集：训练集用于提出改进，留出集独立验证，两集不重叠。
     * 覆盖任务要求的六类场景：正常 / 单位不一致 / 报告期不一致 / 缺失字段 / 矛盾 / 来源不可确认。
     */
    public static EvalCaseSet evalCases() {
        return new EvalCaseSet(
                List.of(
                        EvalCase.correctReport("case-clean", "正常财务数据", "TRAIN",
                                Map.of("caseId", "case-clean"), "netMarginLatest",
                                EXPECTED_MARGIN_TRAIN, "compute-margins"),
                        EvalCase.rejected("case-unit-mismatch", "单位不一致", "TRAIN",
                                Map.of("caseId", "case-unit-mismatch"),
                                Set.of(FailureKinds.UNIT_MISMATCH),
                                FailureKinds.UNIT_MISMATCH, "compute-margins"),
                        EvalCase.rejected("case-period-mismatch", "报告期不一致", "TRAIN",
                                Map.of("caseId", "case-period-mismatch"),
                                Set.of(FailureKinds.PERIOD_MISMATCH),
                                FailureKinds.PERIOD_MISMATCH, "compute-margins")),
                List.of(
                        EvalCase.correctReport("case-clean-2", "留出集正常数据（数值不同）", "HOLDOUT",
                                Map.of("caseId", "case-clean-2"), "netMarginLatest",
                                EXPECTED_MARGIN_HOLDOUT, "compute-margins"),
                        EvalCase.rejected("case-missing-field", "缺失字段", "HOLDOUT",
                                Map.of("caseId", "case-missing-field"),
                                Set.of(FailureKinds.MISSING_FIELD),
                                FailureKinds.MISSING_FIELD, "compute-margins"),
                        EvalCase.rejected("case-contradictory", "互相矛盾的数据", "HOLDOUT",
                                Map.of("caseId", "case-contradictory"),
                                Set.of(FailureKinds.CONTRADICTORY_DATA),
                                FailureKinds.CONTRADICTORY_DATA, "compute-margins"),
                        EvalCase.rejected("case-unverifiable-source", "无法确认的来源", "HOLDOUT",
                                Map.of("caseId", "case-unverifiable-source"),
                                Set.of(FailureKinds.UNVERIFIABLE_SOURCE),
                                FailureKinds.UNVERIFIABLE_SOURCE, "compute-margins")));
    }

    /** 训练集子集（轨迹采集只用训练集，留出集保持独立）。 */
    public static EvalCaseSet trainCases() {
        return new EvalCaseSet(evalCases().trainCases(), List.of());
    }
}
