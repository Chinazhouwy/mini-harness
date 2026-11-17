package com.chinazhouwy.miniharness.runtime.workflow;

/**
 * 节点处理器：一种确定性的节点类型实现（如读取财报、口径校验、计算利润率）。
 * 处理器不感知具体 Workflow 版本；版本差异完全由节点声明组合表达。
 */
public interface NodeHandler {

    /** 处理器类型标识，与 NodeSpec.type 对应。 */
    String type();

    NodeOutput execute(NodeInput input) throws Exception;
}
