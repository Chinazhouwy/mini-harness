package com.chinazhouwy.miniharness.runtime.evolution;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.skill.Skill;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowEdge;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 轨迹分析器：从经验记录中挖掘可复用模式与流程缺陷，产出候选提案。
 *
 * 规则 A（插入口径验证节点）：同一 Workflow 的经验中，数据口径类失败标注
 * 达到阈值 → 在被标注节点之前插入验证节点，产出 WorkflowCandidate。
 * 规则 B（删除冗余节点）：节点在所有经验中均失败且无成功记录 → 提案删除并重连边。
 * 规则 C（沉淀 Skill）：同一 Workflow 成功运行达到阈值 → 提出可复用 Skill（观察性提案）。
 *
 * 分析器只提案、不发布；相同规则对相同 Workflow 的重复提案会被跳过。
 */
public class TraceAnalyzer {

    private final AnalyzerPolicy policy;
    private final WorkflowCompiler compiler;

    public TraceAnalyzer(AnalyzerPolicy policy, WorkflowCompiler compiler) {
        this.policy = policy;
        this.compiler = compiler;
    }

    /** 分析结果：提案 + 观察说明（未触发规则也如实记录）。 */
    public record AnalysisReport(List<WorkflowCandidate> workflowCandidates,
                                 List<Skill> skillProposals,
                                 List<String> notes) {
    }

    public AnalysisReport analyze(String workflowName,
                                  ExperienceStore experienceStore,
                                  WorkflowRegistry registry,
                                  CandidateStore candidateStore,
                                  java.util.function.Predicate<String> skillNameExists) {
        List<WorkflowCandidate> proposals = new ArrayList<>();
        List<Skill> skillProposals = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        WorkflowDefinition base = registry.find(workflowName, latestVersionOf(registry, workflowName)).orElse(null);
        if (base == null) {
            notes.add("Workflow " + workflowName + " 不存在，跳过分析");
            return new AnalysisReport(proposals, skillProposals, notes);
        }

        List<ExperienceRecord> experiences = experienceStore.byWorkflow(workflowName);
        notes.add("共考察 " + experiences.size() + " 条 " + workflowName + " 经验记录");

        // 规则 A：数据口径类失败聚类。
        List<ExperienceRecord> qualityFailures = new ArrayList<>();
        for (ExperienceRecord record : experiences) {
            boolean hit = record.annotations().stream()
                    .anyMatch(a -> policy.supportedFailureKinds().contains(a.kind()));
            if (hit) {
                qualityFailures.add(record);
            }
        }
        if (qualityFailures.size() >= policy.minOccurrencesForValidationNode()) {
            // 取最常出现的被标注节点作为插入锚点。
            Map<String, Long> byNode = qualityFailures.stream()
                    .flatMap(r -> r.annotations().stream()
                            .filter(a -> policy.supportedFailureKinds().contains(a.kind()))
                            .map(a -> a.nodeId() == null ? "" : a.nodeId()))
                    .filter(n -> !n.isEmpty())
                    .collect(Collectors.groupingBy(n -> n, Collectors.counting()));
            String anchorNode = byNode.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            boolean alreadyProposed = candidateStore.all().stream().anyMatch(c ->
                    c.baseRef().name().equals(workflowName)
                            && c.rule() == WorkflowCandidate.ProposalRule.INSERT_VALIDATION_NODE);
            if (anchorNode != null && !alreadyProposed) {
                proposals.add(buildInsertValidationCandidate(base, anchorNode, qualityFailures));
                notes.add("规则A命中: " + qualityFailures.size() + " 条口径类失败，锚点节点 " + anchorNode);
            } else if (alreadyProposed) {
                notes.add("规则A命中但已有同规则候选，跳过重复提案");
            } else {
                notes.add("规则A命中但失败标注缺少节点信息");
            }
        } else {
            notes.add("规则A未触发: 口径类失败 " + qualityFailures.size()
                    + " 条 < 阈值 " + policy.minOccurrencesForValidationNode());
        }

        // 规则 B：确实运行过且从未成功的节点 → 冗余/错误节点删除提案。
        // 语义边界：只标记出现过 FAILED 的节点；因上游失败而 SKIPPED 的下游节点不算死节点。
        Set<String> everSucceeded = new HashSet<>();
        Set<String> everFailed = new HashSet<>();
        for (ExperienceRecord record : experiences) {
            for (ExperienceRecord.NodeSummary node : record.nodes()) {
                if (node.status() == com.chinazhouwy.miniharness.runtime.core.NodeStatus.SUCCEEDED) {
                    everSucceeded.add(node.nodeId());
                }
                if (node.status() == com.chinazhouwy.miniharness.runtime.core.NodeStatus.FAILED) {
                    everFailed.add(node.nodeId());
                }
            }
        }
        List<String> deadNodes = everFailed.stream()
                .filter(n -> !everSucceeded.contains(n))
                .sorted()
                .toList();
        if (experiences.size() >= policy.minRunsForRemoval() && !deadNodes.isEmpty()) {
            boolean alreadyProposed = candidateStore.all().stream().anyMatch(c ->
                    c.baseRef().name().equals(workflowName)
                            && c.rule() == WorkflowCandidate.ProposalRule.REMOVE_REDUNDANT_NODE);
            if (!alreadyProposed) {
                proposals.add(buildRemoveRedundantCandidate(base, deadNodes, experiences.size()));
                notes.add("规则B命中: 运行过但从未成功的节点 " + deadNodes);
            } else {
                notes.add("规则B命中但已有同规则候选，跳过重复提案");
            }
        } else {
            notes.add("规则B未触发: 死节点=" + deadNodes + "，经验数=" + experiences.size());
        }

        // 规则 C：成功模式沉淀为 Skill（观察性提案，不参与执行）。
        long successfulRuns = experiences.stream()
                .filter(r -> r.outcome() == com.chinazhouwy.miniharness.runtime.core.RunStatus.SUCCEEDED)
                .count();
        String skillName = "skill-" + workflowName + "-proven-pattern";
        if (successfulRuns >= policy.minSuccessfulRunsForSkill() && !skillNameExists.test(skillName)) {
            Skill skill = new Skill(skillName, workflowName + " 已验证执行模式",
                    base.intent() == null ? "UNKNOWN" : base.intent(),
                    null,
                    "该意图已通过 Workflow " + base.ref() + " 稳定执行 " + successfulRuns
                            + " 次；优先复用该流程而非开放推理。来源: 成功 Run 清单见分析报告。",
                    experiences.stream().filter(r -> r.outcome()
                                    == com.chinazhouwy.miniharness.runtime.core.RunStatus.SUCCEEDED)
                            .map(r -> {
                                if (r.runId() != null) {
                                    return r.runId().value();
                                }
                                return r.taskId() != null ? r.taskId().value() : "unknown";
                            }).toList(),
                    Instant.now());
            skillProposals.add(skill);
            notes.add("规则C命中: 成功运行 " + successfulRuns + " 次，提出 Skill 沉淀提案");
        } else {
            notes.add("规则C未触发: 成功运行 " + successfulRuns
                    + " 次 < 阈值 " + policy.minSuccessfulRunsForSkill());
        }

        return new AnalysisReport(proposals, skillProposals, notes);
    }

    /**
     * 规则 A 候选构建：在锚点节点前插入验证节点。
     * 验证节点依赖锚点的全部上游（保证数据已就绪），锚点额外依赖验证节点。
     */
    private WorkflowCandidate buildInsertValidationCandidate(WorkflowDefinition base,
                                                             String anchorNode,
                                                             List<ExperienceRecord> failures) {
        List<NodeSpec> nodes = new ArrayList<>(base.nodes());
        nodes.add(NodeSpec.of(policy.validationNodeId(), policy.validationNodeType()));
        List<WorkflowEdge> edges = new ArrayList<>(base.edges());
        NodeId anchor = new NodeId(anchorNode);
        Set<NodeId> anchorUpstream = base.edges().stream()
                .filter(e -> e.to().equals(anchor))
                .map(WorkflowEdge::from)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (NodeId up : anchorUpstream) {
            edges.add(new WorkflowEdge(up, new NodeId(policy.validationNodeId())));
        }
        edges.add(new WorkflowEdge(new NodeId(policy.validationNodeId()), anchor));

        WorkflowDefinition proposed = new WorkflowDefinition(
                new WorkflowRef(base.ref().name(), base.ref().version()), // 占位版本，发布时由账本分配
                base.intent(),
                "候选版本: 在 " + anchorNode + " 前插入口径验证节点",
                nodes, edges);
        compiler.compile(proposed); // 编译校验：候选必须是合法 DAG

        List<String> sourceRunIds = failures.stream()
                .map(r -> {
                    if (r.runId() != null) {
                        return r.runId().value();
                    }
                    // 评测案例经验没有 RunId，用 eval-case 任务标识作为证据引用。
                    return r.taskId() != null ? r.taskId().value() : "unknown";
                })
                .toList();
        List<String> conditions = List.of(
                "适用条件: 数据口径类缺陷(" + policy.supportedFailureKinds() + ")反复出现",
                "证据来源: " + sourceRunIds.size() + " 条失败 Run",
                "不适用: 结构性缺失上游数据的情况需另行处理");
        return new WorkflowCandidate(
                "cand-" + UUID.randomUUID().toString().substring(0, 8),
                base.ref(), proposed,
                WorkflowCandidate.ProposalRule.INSERT_VALIDATION_NODE,
                "在 " + anchorNode + " 前插入 " + policy.validationNodeId()
                        + " 节点：先验证单位/报告期/字段完整性/数据一致性，再进入计算，拦截口径错误",
                sourceRunIds, conditions, Instant.now());
    }

    /** 规则 B 候选构建：删除从未成功的节点并把上游直连下游。 */
    private WorkflowCandidate buildRemoveRedundantCandidate(WorkflowDefinition base,
                                                            List<String> deadNodes,
                                                            int experienceCount) {
        Set<String> dead = new HashSet<>(deadNodes);
        List<NodeSpec> nodes = base.nodes().stream()
                .filter(n -> !dead.contains(n.id().value()))
                .toList();
        List<WorkflowEdge> edges = new ArrayList<>();
        for (WorkflowEdge edge : base.edges()) {
            String from = edge.from().value();
            String to = edge.to().value();
            if (!dead.contains(from) && !dead.contains(to)) {
                edges.add(edge);
            } else if (dead.contains(from) && !dead.contains(to)) {
                // 死节点的上游直连其下游（无上游的纯死节点直接消失）。
                for (WorkflowEdge in : base.edges()) {
                    if (in.to().value().equals(from) && !dead.contains(in.from().value())) {
                        edges.add(new WorkflowEdge(in.from(), edge.to()));
                    }
                }
            }
            // dead.contains(to) 的边整体丢弃（下游也被删）。
        }
        WorkflowDefinition proposed = new WorkflowDefinition(
                new WorkflowRef(base.ref().name(), base.ref().version()),
                base.intent(),
                "候选版本: 删除从未成功的节点 " + deadNodes,
                nodes, edges);
        compiler.compile(proposed);
        return new WorkflowCandidate(
                "cand-" + UUID.randomUUID().toString().substring(0, 8),
                base.ref(), proposed,
                WorkflowCandidate.ProposalRule.REMOVE_REDUNDANT_NODE,
                "节点 " + deadNodes + " 在 " + experienceCount + " 条经验中从未成功，疑似冗余或配置错误，提案删除",
                List.of(), List.of("适用条件: 节点在全部历史经验中均失败", "验证: 需通过回归评测"),
                Instant.now());
    }

    private int latestVersionOf(WorkflowRegistry registry, String name) {
        int max = 0;
        for (var definition : registry.versions(name)) {
            max = Math.max(max, definition.ref().version());
        }
        return max;
    }
}
