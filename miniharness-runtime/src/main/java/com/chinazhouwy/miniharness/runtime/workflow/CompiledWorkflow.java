package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编译结果：定义 + 拓扑执行序 + 下游依赖索引。
 * 拓扑序是执行器逐节点调度的依据；下游索引用于失败/跳过传播。
 */
public record CompiledWorkflow(
        WorkflowDefinition definition,
        List<NodeId> topoOrder,
        Map<NodeId, Set<NodeId>> upstreamOf,
        Map<NodeId, Set<NodeId>> downstreamOf
) {
    public List<NodeId> directDownstream(NodeId id) {
        return List.copyOf(downstreamOf.getOrDefault(id, Set.of()));
    }

    /** 从 seed 开始的传递闭包（含 seed 本身）。 */
    public Set<NodeId> transitiveDownstream(Set<NodeId> seeds) {
        Set<NodeId> visited = new LinkedHashSet<>();
        List<NodeId> queue = new ArrayList<>(seeds);
        while (!queue.isEmpty()) {
            NodeId current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (NodeId next : downstreamOf.getOrDefault(current, Set.of())) {
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }
}
