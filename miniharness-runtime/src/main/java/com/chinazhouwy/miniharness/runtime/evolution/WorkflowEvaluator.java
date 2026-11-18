package com.chinazhouwy.miniharness.runtime.evolution;

import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow 评测器：基线与候选在训练集与留出集上分别真实执行，逐条门禁判定。
 * 门禁未全过的候选保持 EVALUATED_FAIL，只能拒绝或另起新候选。
 *
 * 【评测的可信度来自哪里】
 * 1. 每个案例都由 CaseRunner 真实执行一遍（走与生产完全相同的编译器/执行器/工具），
 *    期望值来自案例定义（人写的外部事实），绝不采信被测流程自己的输出或模型自述；
 * 2. 训练集（提出改进用的案例）与留出集（独立验证）不重叠——
 *    防止“在用于提出改进的案例上评估自己”；
 * 3. 门禁是硬规则，不是“看起来更好就行”：
 *    ① HOLDOUT_NO_REGRESSION      留出集零回归（基线通过而候选失败的案例数=0）
 *    ② HOLDOUT_ACCURACY_NOT_WORSE 留出集正确率不下降
 *    ③ HOLDOUT_ERROR_DETECTION    错误检出率 ≥ 阈值（EvalGatePolicy.minErrorDetectionRate）
 *    ④ COST_BOUNDED               平均额外工具调用 ≤ 上限（改进不能没有成本约束）
 *    ⑤ TRAIN_ACCURACY_NOT_WORSE   训练集正确率不下降（改进不能靠牺牲训练集案例）
 *  全部通过候选才标记 EVALUATED_PASS；之后还要人工批准才会真正发布
 *  （ApprovalService 是唯一发布通道）。
 */
public class WorkflowEvaluator {

    private final CaseRunner caseRunner;
    private final EvalGatePolicy gatePolicy;
    private final EventBus events;

    public WorkflowEvaluator(CaseRunner caseRunner, EvalGatePolicy gatePolicy, EventBus events) {
        this.caseRunner = caseRunner;
        this.gatePolicy = gatePolicy;
        this.events = events;
    }

    public EvalReport evaluate(WorkflowDefinition baseline, WorkflowDefinition candidate,
                               EvalCaseSet caseSet, String candidateId) {
        List<CaseRunner.CaseRunResult> baselineResults = new ArrayList<>();
        List<CaseRunner.CaseRunResult> candidateResults = new ArrayList<>();
        for (EvalCase evalCase : caseSet.all()) {
            baselineResults.add(caseRunner.run(baseline, evalCase));
        }
        for (EvalCase evalCase : caseSet.all()) {
            candidateResults.add(caseRunner.run(candidate, evalCase));
        }

        EvalReport.Metrics baselineTrain = metrics(filter(baselineResults, "TRAIN"));
        EvalReport.Metrics candidateTrain = metrics(filter(candidateResults, "TRAIN"));
        EvalReport.Metrics baselineHoldout = metrics(filter(baselineResults, "HOLDOUT"));
        EvalReport.Metrics candidateHoldout = metrics(filter(candidateResults, "HOLDOUT"));

        List<EvalReport.GateResult> gates = new ArrayList<>();
        // 门禁 1：留出集零回归（基线通过而候选失败的案例数 = 0）。
        Set<String> regressions = new HashSet<>(baselineHoldout.failedCaseIds());
        regressions.retainAll(candidateHoldout.failedCaseIds());
        gates.add(new EvalReport.GateResult("HOLDOUT_NO_REGRESSION", regressions.isEmpty(),
                regressions.isEmpty() ? "留出集无回归" : "回归案例: " + regressions));
        // 门禁 2：留出集正确率不下降。
        boolean accuracyNotWorse = candidateHoldout.accuracy() >= baselineHoldout.accuracy();
        gates.add(new EvalReport.GateResult("HOLDOUT_ACCURACY_NOT_WORSE", accuracyNotWorse,
                "基线 " + baselineHoldout.accuracy() + " → 候选 " + candidateHoldout.accuracy()));
        // 门禁 3：错误检出率达到阈值。
        boolean detectionOk = candidateHoldout.errorDetectionRate() >= gatePolicy.minErrorDetectionRate();
        gates.add(new EvalReport.GateResult("HOLDOUT_ERROR_DETECTION", detectionOk,
                "检出率 " + candidateHoldout.errorDetectionRate() + " ≥ 阈值 "
                        + gatePolicy.minErrorDetectionRate()));
        // 门禁 4：额外成本有界。
        double extraCost = candidateHoldout.avgToolCalls() - baselineHoldout.avgToolCalls();
        boolean costOk = extraCost <= gatePolicy.maxExtraAvgToolCallsPerCase();
        gates.add(new EvalReport.GateResult("COST_BOUNDED", costOk,
                "平均额外工具调用 " + extraCost + " ≤ 上限 " + gatePolicy.maxExtraAvgToolCallsPerCase()));
        // 门禁 5：训练集正确率不下降（改进不能只靠牺牲训练集案例）。
        boolean trainNotWorse = !gatePolicy.requireTrainAccuracyNotWorse()
                || candidateTrain.accuracy() >= baselineTrain.accuracy();
        gates.add(new EvalReport.GateResult("TRAIN_ACCURACY_NOT_WORSE", trainNotWorse,
                "基线 " + baselineTrain.accuracy() + " → 候选 " + candidateTrain.accuracy()));

        boolean passed = gates.stream().allMatch(EvalReport.GateResult::passed);
        EvalReport report = new EvalReport(candidateId, baselineResults, candidateResults,
                baselineHoldout, candidateHoldout, baselineTrain, candidateTrain, passed, gates);

        events.publish(HarnessEvent.builder(EventType.EVALUATION_COMPLETED)
                .workflow(candidate.ref())
                .message("候选 " + candidateId + " 评测完成: " + (passed ? "通过门禁" : "未通过门禁"))
                .payload(Map.of(
                        "candidateId", candidateId,
                        "passed", passed,
                        "baselineHoldoutAccuracy", baselineHoldout.accuracy(),
                        "candidateHoldoutAccuracy", candidateHoldout.accuracy(),
                        "candidateErrorDetection", candidateHoldout.errorDetectionRate(),
                        "regressions", String.join(",", regressions))));
        return report;
    }

    private List<CaseRunner.CaseRunResult> filter(List<CaseRunner.CaseRunResult> results, String split) {
        List<CaseRunner.CaseRunResult> matched = new ArrayList<>();
        for (CaseRunner.CaseRunResult result : results) {
            if (split.equals(result.evalCase().split())) {
                matched.add(result);
            }
        }
        return matched;
    }

    private EvalReport.Metrics metrics(List<CaseRunner.CaseRunResult> results) {
        int total = results.size();
        int passedCount = 0;
        int detectionTotal = 0;
        int detectionPassed = 0;
        long durationSum = 0;
        long toolSum = 0;
        List<String> failed = new ArrayList<>();
        for (CaseRunner.CaseRunResult result : results) {
            if (result.evalCase().expected() == EvalCase.Expected.REJECTED) {
                detectionTotal++;
                if (result.passed()) {
                    detectionPassed++;
                }
            }
            durationSum += result.durationMs();
            toolSum += result.toolCalls();
            if (result.passed()) {
                passedCount++;
            } else {
                failed.add(result.evalCase().caseId());
            }
        }
        return new EvalReport.Metrics(
                total,
                passedCount,
                total == 0 ? 1.0 : (double) passedCount / total,
                detectionTotal == 0 ? 1.0 : (double) detectionPassed / detectionTotal,
                detectionTotal,
                total == 0 ? 0 : (double) durationSum / total,
                total == 0 ? 0 : (double) toolSum / total,
                List.copyOf(failed));
    }
}
