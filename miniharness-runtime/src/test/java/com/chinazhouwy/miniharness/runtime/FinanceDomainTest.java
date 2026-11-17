package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.capability.RoutingAction;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.TaskStatus;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinanceEvolutionExperiment;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePack;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePackInstaller;
import com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.task.Run;
import com.chinazhouwy.miniharness.runtime.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 金融研究 Domain Pack：真实合成数据上的完整自进化闭环。 */
class FinanceDomainTest {

    private HarnessEngine seededEngine() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                AnalyzerPolicy.financeDefaults(), EvalGatePolicy.defaults());
        FinancePackInstaller.install(engine, "SEED");
        return engine;
    }

    private Run runOn(HarnessEngine engine, Task task, Map<String, Object> extraMetadata, String triggeredBy) {
        extraMetadata.forEach(task::putMetadata);
        var runId = engine.startRun(task.id(), null, triggeredBy);
        return engine.awaitRun(runId, 30_000);
    }

    @Test
    @DisplayName("v1 用户流：正常数据产出正确净利率 0.11")
    void v1CorrectOnCleanData() {
        HarnessEngine engine = seededEngine();
        var result = engine.handleUserInput("研究华夏制造的盈利能力，分析利润率变化",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random());
        assertEquals(RoutingAction.NEW_TASK_WITH_WORKFLOW, result.decision().action());
        Task task = result.task();
        assertEquals(TaskStatus.OPEN, task.status());

        Run run = runOn(engine, task, Map.of("caseId", "case-clean"), "USER");
        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertEquals(0.11, (Double) run.output().get("netMarginLatest"), 1e-9);
        assertNotNull(run.output().get("provenance"), "报告必须带证据链");
        assertEquals(TaskStatus.ACTIVE, task.status());
    }

    @Test
    @DisplayName("v1 已知缺陷：单位不一致数据产出错误净利率（成功状态≠结果正确）")
    void v1WrongOnUnitMismatch() {
        HarnessEngine engine = seededEngine();
        var result = engine.handleUserInput("研究华夏制造的盈利能力",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random());
        Run run = runOn(engine, result.task(), Map.of("caseId", "case-unit-mismatch"), "USER");
        // v1 没有 口径校验 节点：流程"成功"，但算出的净利率是错的。
        assertEquals(RunStatus.SUCCEEDED, run.status());
        Double wrongMargin = (Double) run.output().get("netMarginLatest");
        assertNotEquals(0.11, wrongMargin, 1e-6);
        assertTrue(wrongMargin < 0.11, "混入万元行后汇总净利率被拉低: " + wrongMargin);
        assertTrue(((java.util.List<?>) run.output().get("unitWarnings")).size() > 0,
                "计算节点如实记录了单位警告");
    }

    @Test
    @DisplayName("自进化实验：v2 候选通过门禁，过激候选被门禁拦下")
    void experimentPreparesTwoCandidates() {
        HarnessEngine engine = seededEngine();
        FinanceEvolutionExperiment.ExperimentSummary summary = FinanceEvolutionExperiment.prepare(engine);
        assertTrue(summary.candidatePassedGate(), "v2 候选应通过门禁: "
                + summary.candidateReport().gateResults());
        assertTrue(summary.aggressiveFailedGate(), "过激候选应被门禁拦下");

        // 对比指标来自真实执行：v1 留出集 0.25 → v2 留出集 1.0；错误检出率 0 → 1.0。
        assertEquals(0.25, summary.candidateReport().baselineHoldout().accuracy(), 1e-9);
        assertEquals(1.0, summary.candidateReport().candidateHoldout().accuracy(), 1e-9);
        assertEquals(0.0, summary.candidateReport().baselineHoldout().errorDetectionRate(), 1e-9);
        assertEquals(1.0, summary.candidateReport().candidateHoldout().errorDetectionRate(), 1e-9);
        assertEquals(0, summary.candidateReport().candidateHoldout().failedCaseIds().size(),
                "v2 留出集零回归");
    }

    @Test
    @DisplayName("批准发布 v2 后行为确实不同：同一缺陷输入被拒绝")
    void approvedV2RejectsDefectiveInput() {
        HarnessEngine engine = seededEngine();
        FinanceEvolutionExperiment.ExperimentSummary summary = FinanceEvolutionExperiment.prepare(engine);
        var published = engine.approveCandidate(summary.candidateId(), "operator-a", "评测达标");
        assertEquals(2, published.ref().version());
        assertEquals(2, engine.workflows().publishedVersion(FinancePack.WORKFLOW_NAME).orElseThrow());

        var result = engine.handleUserInput("研究华夏制造的盈利能力",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random());
        Run run = runOn(engine, result.task(), Map.of("caseId", "case-unit-mismatch"), "USER");
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("UNIT_MISMATCH", run.errorCode());
        assertTrue(run.errorSummary().contains("单位不一致"));
    }

    @Test
    @DisplayName("拒绝过激候选：发布指针不动，v2 仍可正常批准")
    void rejectingAggressiveKeepsPointer() {
        HarnessEngine engine = seededEngine();
        FinanceEvolutionExperiment.ExperimentSummary summary = FinanceEvolutionExperiment.prepare(engine);
        engine.rejectCandidate(summary.aggressiveCandidateId(), "operator-b", "过激规则拒绝正常数据");
        assertEquals(1, engine.workflows().publishedVersion(FinancePack.WORKFLOW_NAME).orElseThrow());
        assertEquals(WorkflowCandidate.CandidateStatus.REJECTED,
                engine.candidates().find(summary.aggressiveCandidateId()).orElseThrow().status());

        engine.approveCandidate(summary.candidateId(), "operator-a", "");
        assertEquals(2, engine.workflows().publishedVersion(FinancePack.WORKFLOW_NAME).orElseThrow());
    }

    @Test
    @DisplayName("回滚：发布指针回到 v1，旧逻辑重新生效")
    void rollbackRestoresV1Behavior() {
        HarnessEngine engine = seededEngine();
        FinanceEvolutionExperiment.ExperimentSummary summary = FinanceEvolutionExperiment.prepare(engine);
        engine.approveCandidate(summary.candidateId(), "operator-a", "");
        engine.rollbackWorkflow(FinancePack.WORKFLOW_NAME, 1, "operator-a");
        assertEquals(1, engine.workflows().publishedVersion(FinancePack.WORKFLOW_NAME).orElseThrow());

        var result = engine.handleUserInput("研究华夏制造的盈利能力",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random());
        Run run = runOn(engine, result.task(), Map.of("caseId", "case-unit-mismatch"), "USER");
        assertEquals(RunStatus.SUCCEEDED, run.status(), "回滚后 v1 的行为恢复（包括它的缺陷）");
    }

    @Test
    @DisplayName("用户质疑：关联原 Run 并留下 WRONG_RESULT 标注（证据可追溯）")
    void challengeAnnotatesOriginalRun() {
        HarnessEngine engine = seededEngine();
        var result = engine.handleUserInput("研究华夏制造的盈利能力",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random());
        Run run = runOn(engine, result.task(), Map.of("caseId", "case-unit-mismatch"), "USER");
        assertTrue(run.status() == RunStatus.SUCCEEDED);

        // 用户后续质疑（模拟聊天消息）。唯一活跃任务 → 直接关联。
        var followUp = engine.handleUserInput("这个利润率好像不对",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random());
        assertEquals(com.chinazhouwy.miniharness.runtime.capability.RoutingAction.CONTINUE_TASK,
                followUp.decision().action());
        assertEquals(result.task().id(), followUp.decision().taskId());

        engine.challengeResult(result.task().id(), "这个利润率好像不对", run.id());
        var experience = engine.experiences().byRun(run.id().value()).orElseThrow();
        assertTrue(experience.annotations().stream()
                .anyMatch(a -> "WRONG_RESULT".equals(a.kind())), "质疑必须落入经验记录");
    }
}
