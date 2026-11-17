package com.chinazhouwy.miniharness.runtime.core;

/** Workflow 节点 ID：在同一个 WorkflowDefinition 内唯一。 */
public record NodeId(String value) {
    @Override
    public String toString() {
        return value;
    }
}
