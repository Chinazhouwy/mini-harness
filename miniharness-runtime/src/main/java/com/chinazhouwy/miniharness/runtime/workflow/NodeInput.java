package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;

import java.util.Map;

/** 传入节点的数据：workflow 输入 + 节点声明参数 + 上游节点输出。 */
public record NodeInput(
        NodeId nodeId,
        Map<String, Object> workflowInput,
        Map<String, Object> params,
        Map<NodeId, NodeOutput> upstreamOutputs,
        NodeContext context
) {
}
