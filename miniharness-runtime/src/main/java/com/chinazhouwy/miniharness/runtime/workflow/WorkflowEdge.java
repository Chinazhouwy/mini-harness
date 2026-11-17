package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;

import java.util.Objects;

/** 依赖边：to 节点依赖 from 节点的输出。 */
public record WorkflowEdge(NodeId from, NodeId to) {
    public WorkflowEdge {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
    }

    public static WorkflowEdge of(String from, String to) {
        return new WorkflowEdge(new NodeId(from), new NodeId(to));
    }
}
