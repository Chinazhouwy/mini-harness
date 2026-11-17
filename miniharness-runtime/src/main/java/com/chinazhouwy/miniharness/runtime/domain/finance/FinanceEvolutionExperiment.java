package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.evolution.EvalReport;
import com.chinazhouwy.miniharness.runtime.evolution.TraceAnalyzer;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowEdge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 第一个自进化实验（金融研究 Domain Pack）：
 * v1 受控测试 → 轨迹分析 → v2 候选（插入口径验证）+ 过激对照候选 → 对比评测 → 审批。
 * 实验可重复运行：同一份合成数据、同一套案例、同样的判定规则。
 */
public final class FinanceEvolutionExperiment {

    private FinanceEvolutionExperiment() {
    }

    /** 实验总结：所有数字均来自真实执行结果。 */
    public record ExperimentSummary(
            String candidateId,
            String aggressiveCandidateId,
            boolean candidatePassedGate,
            boolean aggressiveFailedGate,
            EvalReport candidateReport,
            EvalReport aggressiveReport,
            TraceAnalyzer.AnalysisReport analysis,
            String approvedVersion,
            Instant finishedAt
    ) {
    }

    /**
     * 运行完整实验（不包含批准——批准由调用方/前端触发，保证「人工在环」）。
     * 返回候选 ID 与评测报告；调用方据此调用 engine.approveCandidate / rejectCandidate。
     */
    public static ExperimentSummary prepare(HarnessEngine engine) {
        // 1. 在 v1 上执行受控训练案例，沉淀失败经验轨迹。
        engine.collectExperiencesFromCases(FinancePack.WORKFLOW_NAME, 1, FinancePack.trainCases());

        // 2. 轨迹分析 → 自动生成 v2 候选（插入口径验证节点）。
        TraceAnalyzer.AnalysisReport analysis = engine.analyzeAndPropose(FinancePack.WORKFLOW_NAME);
        if (analysis.workflowCandidates().isEmpty()) {
            throw new IllegalStateException("轨迹分析未产生候选：请检查经验标注是否覆盖单位/报告期缺陷");
        }
        WorkflowCandidate candidate = analysis.workflowCandidates().get(0);

        // 3. 构造对照候选（过激校验）：证明评测门禁能拦下「看似改进实则更差」的提案。
        WorkflowDefinition aggressiveDef = new WorkflowDefinition(
                new WorkflowRef(FinancePack.WORKFLOW_NAME, 1), FinancePack.INTENT,
                "对照候选: 过激口径校验（强制要求可选字段）",
                List.of(
                        NodeSpec.of("load-financials", LoadFinancialsHandler.TYPE),
                        NodeSpec.of("data-quality-validation", DataQualityValidationHandler.TYPE,
                                Map.of("loadNode", "load-financials", "aggressive", "true")),
                        NodeSpec.of("compute-margins", ComputeMarginsHandler.TYPE),
                        NodeSpec.of("generate-report", GenerateReportHandler.TYPE,
                                Map.of("computeNode", "compute-margins"))),
                List.of(
                        WorkflowEdge.of("load-financials", "data-quality-validation"),
                        WorkflowEdge.of("load-financials", "compute-margins"),
                        WorkflowEdge.of("data-quality-validation", "compute-margins"),
                        WorkflowEdge.of("compute-margins", "generate-report")));
        WorkflowCandidate aggressive = new WorkflowCandidate(
                "cand-agg-" + UUID.randomUUID().toString().substring(0, 8),
                new WorkflowRef(FinancePack.WORKFLOW_NAME, 1), aggressiveDef,
                WorkflowCandidate.ProposalRule.MANUAL,
                "对照实验: 过激校验把正常数据也拒绝，用于验证评测门禁有效性",
                List.of(), List.of("对照候选，预期评测不通过"), Instant.now());
        engine.candidates().save(aggressive);

        // 4. 对比评测（v1 vs v2 / v1 vs 过激候选；训练+留出）。
        EvalReport candidateReport = engine.evaluateCandidate(candidate.id(), FinancePack.evalCases());
        EvalReport aggressiveReport = engine.evaluateCandidate(aggressive.id(), FinancePack.evalCases());

        return new ExperimentSummary(candidate.id(), aggressive.id(),
                candidateReport.passed(), !aggressiveReport.passed(),
                candidateReport, aggressiveReport, analysis, null, Instant.now());
    }
}
