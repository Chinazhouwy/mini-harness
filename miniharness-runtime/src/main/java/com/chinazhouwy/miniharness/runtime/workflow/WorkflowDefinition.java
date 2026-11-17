package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 不可变的 Workflow 定义：名称 + 版本 + 意图 + 节点与依赖边。
 * 定义一经创建不得修改；演化通过创建新版本实例完成。
 * intent 是能力路由的匹配键（例如 PROFITABILITY_RESEARCH）。
 */
public record WorkflowDefinition(
        WorkflowRef ref,
        String intent,
        String description,
        List<NodeSpec> nodes,
        List<WorkflowEdge> edges
) {
    public WorkflowDefinition {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** 4 参便捷构造：intent 缺省为 null（测试与工具类工作流）。 */
    public WorkflowDefinition(WorkflowRef ref, String description,
                              List<NodeSpec> nodes, List<WorkflowEdge> edges) {
        this(ref, null, description, nodes, edges);
    }

    public Optional<NodeSpec> node(NodeId id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public Map<NodeId, NodeSpec> nodeMap() {
        return nodes.stream().collect(Collectors.toMap(NodeSpec::id, Function.identity()));
    }
}
