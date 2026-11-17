package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.core.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow 编译器：把不可变定义编译成可执行形态。
 * 编译期完成全部静态校验——重复节点、悬空边、环、节点类型未注册——执行期不再做这些检查。
 */
public class WorkflowCompiler {

    private final NodeTypeRegistry registry;

    public WorkflowCompiler(NodeTypeRegistry registry) {
        this.registry = registry;
    }

    public CompiledWorkflow compile(WorkflowDefinition definition) {
        Map<NodeId, NodeSpec> nodeMap = definition.nodeMap();
        if (nodeMap.size() != definition.nodes().size()) {
            throw new HarnessException("WORKFLOW_INVALID", "节点 ID 重复: " + definition.ref());
        }
        if (definition.nodes().isEmpty()) {
            throw new HarnessException("WORKFLOW_INVALID", "Workflow 不能为空: " + definition.ref());
        }

        Map<NodeId, Set<NodeId>> upstream = new HashMap<>();
        Map<NodeId, Set<NodeId>> downstream = new HashMap<>();
        for (NodeId id : nodeMap.keySet()) {
            upstream.put(id, new LinkedHashSet<>());
            downstream.put(id, new LinkedHashSet<>());
        }
        for (WorkflowEdge edge : definition.edges()) {
            if (!nodeMap.containsKey(edge.from()) || !nodeMap.containsKey(edge.to())) {
                throw new HarnessException("WORKFLOW_INVALID",
                        "依赖边引用了不存在的节点: " + edge.from() + " → " + edge.to() + " in " + definition.ref());
            }
            if (edge.from().equals(edge.to())) {
                throw new HarnessException("WORKFLOW_INVALID", "自环依赖: " + edge.from());
            }
            downstream.get(edge.from()).add(edge.to());
            upstream.get(edge.to()).add(edge.from());
        }

        // 节点类型必须已注册（候选 Workflow 编译时提前暴露配置错误）。
        for (NodeSpec spec : definition.nodes()) {
            if (!registry.has(spec.type())) {
                throw new HarnessException("HANDLER_NOT_FOUND",
                        "节点 " + spec.id() + " 的类型未注册: " + spec.type());
            }
        }

        // Kahn 拓扑排序 + 环检测。
        List<NodeId> order = new ArrayList<>();
        Map<NodeId, Integer> remaining = new HashMap<>();
        for (Map.Entry<NodeId, Set<NodeId>> e : upstream.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
        }
        List<NodeId> ready = new ArrayList<>();
        for (Map.Entry<NodeId, Integer> e : remaining.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        Set<NodeId> seen = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            NodeId current = ready.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            order.add(current);
            for (NodeId next : downstream.get(current)) {
                int left = remaining.merge(next, -1, Integer::sum);
                if (left == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() != nodeMap.size()) {
            List<NodeId> cyclic = new ArrayList<>(nodeMap.keySet());
            cyclic.removeAll(seen);
            throw new HarnessException("WORKFLOW_CYCLIC",
                    "Workflow 存在环依赖，涉及节点: " + cyclic + " in " + definition.ref());
        }

        Map<NodeId, Set<NodeId>> frozenUpstream = new HashMap<>();
        Map<NodeId, Set<NodeId>> frozenDownstream = new HashMap<>();
        upstream.forEach((k, v) -> frozenUpstream.put(k, Set.copyOf(v)));
        downstream.forEach((k, v) -> frozenDownstream.put(k, Set.copyOf(v)));
        return new CompiledWorkflow(definition, List.copyOf(order), frozenUpstream, frozenDownstream);
    }
}
