package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCase;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCaseSet;
import com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy;
import com.chinazhouwy.miniharness.runtime.evolution.TraceAnalyzer;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceAnalyzer 规则 B：识别「运行过但从未成功」的错误/冗余节点并提案删除。
 * 语义边界：只标记出现过 FAILED 的节点；因上游失败而 SKIPPED 的下游节点不算死节点，
 * 删除候选需重连边（死节点上游直连其下游）并通过编译校验。
 */
class TraceAnalyzerRuleBTest {

    /** 永远失败的合成节点（模拟配置错误/损坏节点）。 */
    private com.chinazhouwy.miniharness.runtime.workflow.NodeHandler alwaysFailHandler() {
        return new com.chinazhouwy.miniharness.runtime.workflow.NodeHandler() {
            @Override
            public String type() {
                return "always-fail";
            }

            @Override
            public com.chinazhouwy.miniharness.runtime.workflow.NodeOutput execute(
                    com.chinazhouwy.miniharness.runtime.workflow.NodeInput input) {
                return com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.failed(
                        "BROKEN_NODE", "该节点配置损坏，永远失败");
            }
        };
    }

    /** 透传合成节点（对应 EvolutionLifecycleTest 的 produce/report 语义简化版）。 */
    private com.chinazhouwy.miniharness.runtime.workflow.NodeHandler echoHandler(String type) {
        return new com.chinazhouwy.miniharness.runtime.workflow.NodeHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public com.chinazhouwy.miniharness.runtime.workflow.NodeOutput execute(
                    com.chinazhouwy.miniharness.runtime.workflow.NodeInput input) {
                return com.chinazhouwy.miniharness.runtime.workflow.NodeOutput.success(
                        Map.of("echo", type, "score", 0.8, "defect", ""));
            }
        };
    }

    private HarnessEngine engineWith(WorkflowDefinition definition) {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                new AnalyzerPolicy(2, 2, 99, "strict-validation", "quality-gate"),
                EvalGatePolicy.defaults());
        engine.registerNodeHandler(echoHandler("load"));
        engine.registerNodeHandler(echoHandler("tail"));
        engine.registerNodeHandler(alwaysFailHandler());
        engine.registerNodeHandler(echoHandler("report"));
        engine.workflows().install(definition);
        engine.workflows().publish(definition.ref().name(), definition.ref().version(), "SEED", null);
        return engine;
    }

    /** 两案例输入不同但都会触发 dead 节点失败（合成节点无条件失败）。 */
    private EvalCaseSet cases() {
        return new EvalCaseSet(
                List.of(EvalCase.correctReport("b1", "案例1", "TRAIN", Map.of("k", "1"), "score", 0.8, "tail"),
                        EvalCase.correctReport("b2", "案例2", "TRAIN", Map.of("k", "2"), "score", 0.8, "tail")),
                List.of());
    }

    @Test
    @DisplayName("规则B命中：中间坏节点在全部经验中均 FAILED → 提案删除并重连上游到下游")
    void proposesRemovalOfNeverSucceedingNode() {
        // load → dead(坏节点) → tail
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth-b", 1), "SCORE_RESEARCH",
                "v1 含坏节点",
                List.of(NodeSpec.of("load", "load"), NodeSpec.of("dead", "always-fail"),
                        NodeSpec.of("tail", "tail")),
                List.of(WorkflowEdge.of("load", "dead"), WorkflowEdge.of("dead", "tail")));
        HarnessEngine engine = engineWith(v1);
        engine.collectExperiencesFromCases("synth-b", 1, cases());

        TraceAnalyzer.AnalysisReport analysis = engine.analyzeAndPropose("synth-b");
        // 规则 B 命中且只标记 dead（tail 因上游失败是 SKIPPED，不属于死节点）。
        assertEquals(1, analysis.workflowCandidates().size(), "应产出一条删除冗余节点候选");
        WorkflowCandidate candidate = analysis.workflowCandidates().get(0);
        assertEquals(WorkflowCandidate.ProposalRule.REMOVE_REDUNDANT_NODE, candidate.rule());
        assertTrue(analysis.notes().stream().anyMatch(n -> n.contains("规则B命中")));
        assertTrue(analysis.notes().stream().anyMatch(n -> n.contains("[dead]") || n.contains("dead")),
                "notes 应指明死节点是 dead 而非 tail: " + analysis.notes());

        // 候选定义：删除 dead、重连 load→tail、通过编译校验。
        var proposed = candidate.proposedDefinition();
        assertEquals(Set.of("load", "tail"),
                proposed.nodes().stream().map(n -> n.id().value()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(proposed.edges().stream()
                .anyMatch(e -> e.from().value().equals("load") && e.to().value().equals("tail")),
                "删除中间节点后必须重连 load→tail");
        assertEquals(WorkflowCandidate.CandidateStatus.PENDING_EVAL, candidate.status());
        assertNotNull(candidate.rationale());
        assertTrue(candidate.rationale().contains("dead"));
    }

    @Test
    @DisplayName("规则B不误伤：叶子坏节点提案只删除坏节点本身；好节点无失败记录不受影响")
    void onlyFailedNodesAreFlagged() {
        // load → tail；load 分支出叶子 dead（失败不影响主链）
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth-b2", 1), "SCORE_RESEARCH",
                "v1 叶子坏节点",
                List.of(NodeSpec.of("load", "load"), NodeSpec.of("dead", "always-fail"),
                        NodeSpec.of("tail", "tail")),
                List.of(WorkflowEdge.of("load", "dead"), WorkflowEdge.of("load", "tail")));
        HarnessEngine engine = engineWith(v1);
        engine.collectExperiencesFromCases("synth-b2", 1, cases());

        TraceAnalyzer.AnalysisReport analysis = engine.analyzeAndPropose("synth-b2");
        assertEquals(1, analysis.workflowCandidates().size());
        WorkflowCandidate candidate = analysis.workflowCandidates().get(0);
        var proposed = candidate.proposedDefinition();
        // 候选只删 dead，保留 load 与 tail，且仍可通过编译（编译在提案构建时执行）。
        assertTrue(proposed.nodes().stream().noneMatch(n -> n.id().value().equals("dead")));
        assertTrue(proposed.nodes().stream().anyMatch(n -> n.id().value().equals("load")));
        assertTrue(proposed.nodes().stream().anyMatch(n -> n.id().value().equals("tail")));
    }

    @Test
    @DisplayName("经验数不足阈值时不触发规则B（避免单次失败误删）")
    void doesNotFireBelowRunThreshold() {
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowRef("synth-b3", 1), "SCORE_RESEARCH",
                "v1",
                List.of(NodeSpec.of("load", "load"), NodeSpec.of("dead", "always-fail")),
                List.of(WorkflowEdge.of("load", "dead")));
        HarnessEngine engine = engineWith(v1);
        // 只采集 1 条经验（阈值 minRunsForRemoval=2）。
        engine.collectExperiencesFromCases("synth-b3", 1, new EvalCaseSet(
                List.of(EvalCase.correctReport("b1", "仅一条", "TRAIN", Map.of(), "score", 0.8, "load")),
                List.of()));
        TraceAnalyzer.AnalysisReport analysis = engine.analyzeAndPropose("synth-b3");
        assertTrue(analysis.workflowCandidates().isEmpty(), "经验不足不应提案");
        assertTrue(analysis.notes().stream().anyMatch(n -> n.contains("规则B未触发")));
    }
}
