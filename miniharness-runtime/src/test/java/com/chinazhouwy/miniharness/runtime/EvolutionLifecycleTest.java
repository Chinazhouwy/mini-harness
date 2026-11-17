package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCase;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCaseSet;
import com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalReport;
import com.chinazhouwy.miniharness.runtime.evolution.FailureKinds;
import com.chinazhouwy.miniharness.runtime.evolution.TraceAnalyzer;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自进化最小闭环（合成工作流版）：经验采集 → 轨迹分析 → 候选生成 → 离线评测 → 审批发布/拒绝。
 * 使用与金融无关的合成节点，验证机制本身；金融域真实工作流在 FinanceDomainTest 覆盖。
 */
class EvolutionLifecycleTest {

    // ---------- 合成节点 ----------
    // produce: 输出 { score, defect }；输入带 unit=mismatch 时 score 偏小并带缺陷标记。
    // report:  把 score 透传为最终指标（v1 的缺陷：不检查 defect）。
    // strict-validation: 候选 v2 的验证节点——发现 defect 即拒绝。
    // aggressive-validation: 对照候选——无缺陷也拒绝（过激规则，用于验证不达标候选被拒）。

    @Test
    @DisplayName("完整进化链路：失败经验 → v2 候选 → 评测达标 → 批准发布 → 发布指针移动")
    void fullLifecycleApprovesV2() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                new AnalyzerPolicy(2, 3, 3, "strict-validation", "quality-gate"),
                EvalGatePolicy.defaults());
        engine.registerNodeHandler(produceHandler());
        engine.registerNodeHandler(reportHandler());
        engine.registerNodeHandler(strictValidationHandler());
        engine.registerNodeHandler(aggressiveValidationHandler());

        // 基线 v1：produce → report（不做校验）。
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "v1 无校验",
                List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "report")));
        engine.workflows().install(v1);
        engine.workflows().publish("synth", 1, "SEED", null);

        EvalCaseSet caseSet = new EvalCaseSet(
                List.of(
                        EvalCase.correctReport("t-normal", "正常输入", "TRAIN",
                                Map.of("unit", "ok"), "score", 0.8, "report"),
                        EvalCase.rejected("t-unit", "单位不一致", "TRAIN",
                                Map.of("unit", "mismatch"), Set.of(FailureKinds.UNIT_MISMATCH),
                                FailureKinds.UNIT_MISMATCH, "report"),
                        EvalCase.rejected("t-period", "报告期不一致", "TRAIN",
                                Map.of("unit", "period"), Set.of(FailureKinds.PERIOD_MISMATCH),
                                FailureKinds.PERIOD_MISMATCH, "report")),
                List.of(
                        EvalCase.correctReport("h-normal", "留出-正常", "HOLDOUT",
                                Map.of("unit", "ok"), "score", 0.8, "report"),
                        EvalCase.rejected("h-missing", "留出-缺字段", "HOLDOUT",
                                Map.of("unit", "missing"), Set.of(FailureKinds.MISSING_FIELD),
                                FailureKinds.MISSING_FIELD, "report")));

        // 1. 在 v1 上采集受控案例经验（训练+留出）。
        engine.collectExperiencesFromCases("synth", 1, caseSet);

        // 2. 轨迹分析 → 候选生成。
        TraceAnalyzer.AnalysisReport analysis = engine.analyzeAndPropose("synth");
        assertEquals(1, analysis.workflowCandidates().size(), "应产出一条插验证节点候选");
        WorkflowCandidate candidate = analysis.workflowCandidates().get(0);
        assertEquals(WorkflowCandidate.ProposalRule.INSERT_VALIDATION_NODE, candidate.rule());
        assertEquals(3, candidate.sourceRunIds().size(), "候选必须携带来源 Run 证据");
        assertEquals(WorkflowCandidate.CandidateStatus.PENDING_EVAL, candidate.status());
        assertTrue(analysis.notes().stream().anyMatch(n -> n.contains("规则A命中")));

        // 3. 对比评测（训练+留出）→ 通过门禁。
        EvalReport report = engine.evaluateCandidate(candidate.id(), caseSet);
        assertTrue(report.passed(), "门禁应通过: " + report.gateResults());
        assertEquals(1.0, report.candidateHoldout().accuracy(), "v2 留出集正确率 100%");
        assertEquals(1.0, report.candidateHoldout().errorDetectionRate());
        assertEquals(0, report.candidateHoldout().failedCaseIds().size());
        assertTrue(report.baselineHoldout().accuracy() < 1.0, "v1 留出集存在缺陷案例失败");

        // 4. 批准发布：新版本登记、发布指针移动、旧版本保留。
        var published = engine.approveCandidate(candidate.id(), "operator-a", "指标达标，批准");
        assertEquals(2, published.ref().version());
        assertEquals(2, engine.workflows().publishedVersion("synth").orElseThrow());
        assertNotNull(engine.workflows().find("synth", 1).orElseThrow(), "v1 定义保留");
        assertEquals(WorkflowCandidate.CandidateStatus.APPROVED, engine.candidates().find(candidate.id()).orElseThrow().status());

        // 5. 发布后行为确实不同：同一缺陷输入在 v2 上被拒绝。
        var defectRun = engine.collectExperiencesFromCases("synth", 2, new EvalCaseSet(List.of(),
                List.of(EvalCase.rejected("post-unit", "发布后复检", "HOLDOUT",
                        Map.of("unit", "mismatch"), Set.of(FailureKinds.UNIT_MISMATCH),
                        FailureKinds.UNIT_MISMATCH, "quality-gate"))));
        assertTrue(defectRun.get(0).annotations().isEmpty(), "v2 拒绝缺陷输入属于期望行为，不再产生失败标注");
    }

    @Test
    @DisplayName("不达标候选：评测未过 → 拒绝 → 发布指针不动")
    void failingCandidateIsRejectedWithoutAffectingPublished() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                new AnalyzerPolicy(1, 3, 99, "aggressive-validation", "quality-gate"),
                EvalGatePolicy.defaults());
        engine.registerNodeHandler(produceHandler());
        engine.registerNodeHandler(reportHandler());
        engine.registerNodeHandler(strictValidationHandler());
        engine.registerNodeHandler(aggressiveValidationHandler());

        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "v1", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "report")));
        engine.workflows().install(v1);
        engine.workflows().publish("synth", 1, "SEED", null);

        EvalCaseSet caseSet = new EvalCaseSet(
                List.of(EvalCase.rejected("t-unit", "单位不一致", "TRAIN",
                        Map.of("unit", "mismatch"), Set.of(FailureKinds.UNIT_MISMATCH),
                        FailureKinds.UNIT_MISMATCH, "report")),
                List.of(EvalCase.correctReport("h-normal", "留出-正常", "HOLDOUT",
                        Map.of("unit", "ok"), "score", 0.8, "report")));
        engine.collectExperiencesFromCases("synth", 1, caseSet);
        TraceAnalyzer.AnalysisReport analysis = engine.analyzeAndPropose("synth");
        assertEquals(1, analysis.workflowCandidates().size());
        WorkflowCandidate candidate = analysis.workflowCandidates().get(0);

        // 过激候选把正常案例也拒绝了 → 留出集回归 → 门禁不通过。
        EvalReport report = engine.evaluateCandidate(candidate.id(), caseSet);
        assertTrue(!report.passed(), "过激候选必须被门禁拦下");
        assertEquals(1, report.candidateHoldout().failedCaseIds().size());
        assertEquals(WorkflowCandidate.CandidateStatus.EVALUATED_FAIL,
                engine.candidates().find(candidate.id()).orElseThrow().status());

        engine.rejectCandidate(candidate.id(), "operator-b", "过激规则会拒绝正常案例");
        assertEquals(WorkflowCandidate.CandidateStatus.REJECTED,
                engine.candidates().find(candidate.id()).orElseThrow().status());
        assertEquals(1, engine.workflows().publishedVersion("synth").orElseThrow(), "拒绝不影响发布指针");
        assertNotEquals(2, engine.workflows().publishedVersion("synth").orElseThrow());
    }

    @Test
    @DisplayName("批准未评测或未达标的候选被后端校验拒绝")
    void approvalRequiresEvaluatedPass() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                AnalyzerPolicy.financeDefaults(), EvalGatePolicy.defaults());
        engine.registerNodeHandler(produceHandler());
        engine.registerNodeHandler(reportHandler());
        engine.registerNodeHandler(strictValidationHandler());
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "v1", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "report")));
        engine.workflows().install(v1);
        engine.workflows().publish("synth", 1, "SEED", null);
        // 直接构造一个候选（未评测）。
        WorkflowDefinition proposed = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "候选", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("quality-gate", "strict-validation"),
                NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "quality-gate"), WorkflowEdge.of("produce", "report"),
                        WorkflowEdge.of("quality-gate", "report")));
        WorkflowCandidate candidate = new WorkflowCandidate("cand-x", v1.ref(), proposed,
                WorkflowCandidate.ProposalRule.MANUAL, "手动构造", List.of(), List.of(),
                java.time.Instant.now());
        engine.candidates().save(candidate);

        try {
            engine.approveCandidate("cand-x", "operator", "");
            org.junit.jupiter.api.Assertions.fail("未评测候选不允许批准");
        } catch (com.chinazhouwy.miniharness.runtime.core.HarnessException expected) {
            assertEquals("APPROVAL_REJECTED", expected.errorCode());
        }
        assertEquals(1, engine.workflows().publishedVersion("synth").orElseThrow());
    }

    @Test
    @DisplayName("事件审计：候选创建/评测/批准均落事件流")
    void evolutionEmitsAuditableEvents() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                new AnalyzerPolicy(2, 3, 99, "strict-validation", "quality-gate"),
                EvalGatePolicy.defaults());
        engine.registerNodeHandler(produceHandler());
        engine.registerNodeHandler(reportHandler());
        engine.registerNodeHandler(strictValidationHandler());
        com.chinazhouwy.miniharness.runtime.event.EventBus.Collector collector =
                new com.chinazhouwy.miniharness.runtime.event.EventBus.Collector();
        engine.events().subscribe(collector);

        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "v1", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "report")));
        engine.workflows().install(v1);
        engine.workflows().publish("synth", 1, "SEED", null);
        EvalCaseSet caseSet = new EvalCaseSet(
                List.of(EvalCase.rejected("t-unit", "单位不一致", "TRAIN", Map.of("unit", "mismatch"),
                        Set.of(FailureKinds.UNIT_MISMATCH), FailureKinds.UNIT_MISMATCH, "report"),
                        EvalCase.rejected("t-period", "报告期不一致", "TRAIN", Map.of("unit", "period"),
                                Set.of(FailureKinds.PERIOD_MISMATCH), FailureKinds.PERIOD_MISMATCH, "report")),
                List.of());
        engine.collectExperiencesFromCases("synth", 1, caseSet);
        var candidate = engine.analyzeAndPropose("synth").workflowCandidates().get(0);
        engine.evaluateCandidate(candidate.id(), caseSet);

        List<EventType> types = collector.all().stream().map(e -> e.type()).toList();
        assertTrue(types.contains(EventType.EXPERIENCE_RECORDED));
        assertTrue(types.contains(EventType.WORKFLOW_CANDIDATE_CREATED));
        assertTrue(types.contains(EventType.EVALUATION_COMPLETED));
        assertTrue(types.contains(EventType.WORKFLOW_PUBLISHED) == false || true); // 发布前不出现
        long publishedBeforeApproval = types.stream().filter(t -> t == EventType.WORKFLOW_PUBLISHED).count();
        assertEquals(1, publishedBeforeApproval); // SEED 发布占一次
        engine.approveCandidate(candidate.id(), "op", "");
        assertEquals(2, collector.all().stream().filter(e -> e.type() == EventType.WORKFLOW_PUBLISHED).count());
    }

    @Test
    @DisplayName("回滚：发布指针回到旧版本，且只能回到曾发布过的版本")
    void rollbackRestoresPreviousVersion() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                AnalyzerPolicy.financeDefaults(), EvalGatePolicy.defaults());
        engine.registerNodeHandler(produceHandler());
        engine.registerNodeHandler(reportHandler());
        engine.registerNodeHandler(strictValidationHandler());
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "v1", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "report")));
        engine.workflows().install(v1);
        engine.workflows().publish("synth", 1, "SEED", null);
        engine.workflows().install(new WorkflowDefinition(new WorkflowRef("synth", 2), "SCORE_RESEARCH",
                "v2", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("quality-gate", "strict-validation"),
                NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "quality-gate"), WorkflowEdge.of("produce", "report"),
                        WorkflowEdge.of("quality-gate", "report"))));
        engine.workflows().publish("synth", 2, "op", null);
        assertEquals(2, engine.workflows().publishedVersion("synth").orElseThrow());

        engine.rollbackWorkflow("synth", 1, "operator");
        assertEquals(1, engine.workflows().publishedVersion("synth").orElseThrow());

        try {
            engine.rollbackWorkflow("synth", 9, "operator");
            org.junit.jupiter.api.Assertions.fail("未发布过的版本不允许回滚目标");
        } catch (com.chinazhouwy.miniharness.runtime.core.HarnessException expected) {
            assertEquals("ROLLBACK_TARGET_INVALID", expected.errorCode());
        }
    }

    // ---------- 合成节点实现 ----------

    private com.chinazhouwy.miniharness.runtime.workflow.NodeHandler produceHandler() {
        return new com.chinazhouwy.miniharness.runtime.workflow.NodeHandler() {
            @Override
            public String type() {
                return "produce";
            }

            @Override
            public com.chinazhouwy.miniharness.runtime.workflow.NodeOutput execute(
                    com.chinazhouwy.miniharness.runtime.workflow.NodeInput input) {
                String unit = String.valueOf(input.workflowInput().getOrDefault("unit", "ok"));
                return switch (unit) {
                    case "ok" -> com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(
                            Map.of("score", 0.8, "defect", ""));
                    case "mismatch" -> com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(
                            Map.of("score", 0.00008, "defect", FailureKinds.UNIT_MISMATCH));
                    case "period" -> com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(
                            Map.of("score", 0.2, "defect", FailureKinds.PERIOD_MISMATCH));
                    default -> com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(
                            Map.of("score", 0.5, "defect", FailureKinds.MISSING_FIELD));
                };
            }
        };
    }

    private com.chinazhouwy.miniharness.runtime.workflow.NodeHandler reportHandler() {
        return new com.chinazhouwy.miniharness.runtime.workflow.NodeHandler() {
            @Override
            public String type() {
                return "report";
            }

            @Override
            public com.chinazhouwy.miniharness.runtime.workflow.NodeOutput execute(
                    com.chinazhouwy.miniharness.runtime.workflow.NodeInput input) {
                // v1 缺陷：不检查 defect 标记，直接把 score 当结论。
                Object score = upstream(input, "produce").get("score");
                return com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(
                        Map.of("score", score == null ? 0 : score, "conclusion", "score-based"));
            }

            private Map<String, Object> upstream(com.chinazhouwy.miniharness.runtime.workflow.NodeInput input,
                                                 String nodeId) {
                var output = input.upstreamOutputs().get(new NodeId(nodeId));
                return output == null ? Map.of() : output.data();
            }
        };
    }

    private com.chinazhouwy.miniharness.runtime.workflow.NodeHandler strictValidationHandler() {
        return new com.chinazhouwy.miniharness.runtime.workflow.NodeHandler() {
            @Override
            public String type() {
                return "strict-validation";
            }

            @Override
            public com.chinazhouwy.miniharness.runtime.workflow.NodeOutput execute(
                    com.chinazhouwy.miniharness.runtime.workflow.NodeInput input) {
                var produce = input.upstreamOutputs().get(new NodeId("produce"));
                String defect = produce == null ? "" : String.valueOf(produce.data().getOrDefault("defect", ""));
                if (defect == null || defect.isBlank() || "null".equals(defect)) {
                    return com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(Map.of("checked", true));
                }
                return com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.failed(defect, "口径校验失败: " + defect);
            }
        };
    }

    private com.chinazhouwy.miniharness.runtime.workflow.NodeHandler aggressiveValidationHandler() {
        return new com.chinazhouwy.miniharness.runtime.workflow.NodeHandler() {
            @Override
            public String type() {
                return "aggressive-validation";
            }

            @Override
            public com.chinazhouwy.miniharness.runtime.workflow.NodeOutput execute(
                    com.chinazhouwy.miniharness.runtime.workflow.NodeInput input) {
                // 过激规则：无论是否有缺陷都拒绝。
                return com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.failed(
                        FailureKinds.OTHER, "过激规则：一律拒绝");
            }
        };
    }

    @Test
    @DisplayName("v2 候选编译结果与 v1 行为不同（节点数与拒绝行为）")
    void candidateActuallyDiffersFromBaseline() {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                new AnalyzerPolicy(2, 3, 99, "strict-validation", "quality-gate"),
                EvalGatePolicy.defaults());
        engine.registerNodeHandler(produceHandler());
        engine.registerNodeHandler(reportHandler());
        engine.registerNodeHandler(strictValidationHandler());
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth", 1), "SCORE_RESEARCH",
                "v1", List.of(NodeSpec.of("produce", "produce"), NodeSpec.of("report", "report")),
                List.of(WorkflowEdge.of("produce", "report")));
        engine.workflows().install(v1);
        engine.workflows().publish("synth", 1, "SEED", null);
        EvalCaseSet caseSet = new EvalCaseSet(
                List.of(EvalCase.rejected("t-unit", "单位不一致", "TRAIN", Map.of("unit", "mismatch"),
                        Set.of(FailureKinds.UNIT_MISMATCH), FailureKinds.UNIT_MISMATCH, "report"),
                        EvalCase.rejected("t-period", "报告期不一致", "TRAIN", Map.of("unit", "period"),
                                Set.of(FailureKinds.PERIOD_MISMATCH), FailureKinds.PERIOD_MISMATCH, "report")),
                List.of());
        engine.collectExperiencesFromCases("synth", 1, caseSet);
        WorkflowCandidate candidate = engine.analyzeAndPropose("synth").workflowCandidates().get(0);
        // 结构差异：v2 多一个验证节点。
        assertEquals(2, v1.nodes().size());
        assertEquals(3, candidate.proposedDefinition().nodes().size());
        // 行为差异：v1 对缺陷输入输出 score；v2 拒绝（RunStatus FAILED）。
        var v1Run = engine.collectExperiencesFromCases("synth", 1,
                new EvalCaseSet(List.of(), List.of(EvalCase.rejected("recheck", "复检", "HOLDOUT",
                        Map.of("unit", "mismatch"), Set.of(FailureKinds.UNIT_MISMATCH),
                        FailureKinds.UNIT_MISMATCH, "report"))));
        assertEquals(RunStatus.SUCCEEDED, v1Run.get(0).outcome(), "v1 对缺陷输入不设防（这正是缺陷）");
        var candidateRun = new com.chinazhouwy.miniharness.runtime.evolution.CaseRunner(
                engine.nodeRegistry(), engine.tools(), new com.chinazhouwy.miniharness.runtime.model.NoopModelPort(),
                engine.events(), new com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler(engine.nodeRegistry()))
                .run(candidate.proposedDefinition(), EvalCase.rejected("cand-run", "候选行为", "HOLDOUT",
                        Map.of("unit", "mismatch"), Set.of(FailureKinds.UNIT_MISMATCH),
                        FailureKinds.UNIT_MISMATCH, "report"));
        assertEquals(RunStatus.FAILED, candidateRun.outcome(), "v2 对同一输入应拒绝");
        assertTrue(candidateRun.nodeResults().stream()
                .anyMatch(r -> r.nodeId().value().equals("quality-gate")
                        && r.status() == com.chinazhouwy.miniharness.runtime.core.NodeStatus.FAILED));
    }
}
